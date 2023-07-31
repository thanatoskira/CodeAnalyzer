package org.observer.utils;

import net.jodah.expiringmap.ExpirationPolicy;
import net.jodah.expiringmap.ExpiringMap;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;

/**
 * 用于解析 Jar 包并提取 packageName 及 dependencies
 */
public class DependencyUtil {
    /**
     * 建立 Jar 包与依赖间的映射
     */
    private final static Map<String, List<String>> fileDependencyMap = new HashMap<>();
    private final static Map<String, List<String>> pkgNameFileMap = new HashMap<>();
    /**
     * 记录无法加载的类
     */
    private final static List<String> unKnownClassNameList = new CopyOnWriteArrayList<>();
    /**
     * 缓存 class -> file 映射
     */
    private final static Map<String, String> clsNameFileMap = ExpiringMap.builder()
            // 过期时间
            .expiration(5, TimeUnit.MINUTES)
            // 过期策略
            .expirationPolicy(ExpirationPolicy.ACCESSED).build();
    private final static MavenXpp3Reader reader = new MavenXpp3Reader();
    private final static int minCommonPrefixLen = 2;

    public static void resolveDir(String dir) throws Exception {
        Files.walk(Paths.get(dir)).filter(f -> f.toFile().getName().endsWith(".jar")).forEach(f -> {
            try {
                resolve(f.toString());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 通过 pom.xml 建立 packageName -> dependencies 和 packageName -> files 映射
     */
    public static void resolve(String file) throws Exception {
        JarFile jarFile = new JarFile(new File(file));

        AtomicReference<String> packageName = new AtomicReference<>(null);
        jarFile.stream().filter(f -> {
            String name = f.getName();
            return name.startsWith("META-INF") && name.endsWith("pom.xml");
        }).findFirst().ifPresent(xml -> {
            try {
                Model model = reader.read(jarFile.getInputStream(xml));
                String groupId = model.getGroupId() == null ? model.getParent().getGroupId() : model.getGroupId();
                packageName.set(String.format("%s.%s", groupId, model.getArtifactId()));
                if (jarFile.getJarEntry(packageName.get().replace(".", "/")) == null) {
                    packageName.set(null);
                } else {
                    addPkgFileMap(packageName.get(), file);
                }
                List<String> depList = model.getDependencies().stream().map(dep -> String.format("%s.%s", dep.getGroupId().equals("${project.groupId}") ? groupId : dep.getGroupId(), dep.getArtifactId())).toList();
                fileDependencyMap.put(file, depList);
            } catch (IOException | XmlPullParserException e) {
                throw new RuntimeException(e);
            }
        });

        /**
         * 1 .存在 groupId.artifactId 与 packageName 不一致的情况(已解决)
         * eg: cglib-3.2.12.jar: cglib.cglib -> net.sf.cglib
         * 2. 存在 jar 包含多个 packageName 的情况(已解决)
         * eg: atlassian-plugins-core-5.7.14.jar -> org/codehaus/classworlds/uberjar/protocol/jar/ 和 com/atlassian/plugin/
         * 3. 存在多个 jar 包存在相同 packageName 的情况(已解决)
         * eg: org.apache.lucene -> lucene-core-4.4.0-atlassian-6.jar / lucene-misc-4.4.0-atlassian-6.jar
         * 4. 存在 org/ org/apache/felix org/osgi/ 目录分组错误情况(已解决)
         * right：[org/apache/felix, org/osgi/]
         * error：[org/]
         * 5. 存在不包含目录的 Class 文件(已解决)
         * eg: module-info.class
         * 6. 存在 jar 中不包含 pom.xml 文件的情况
         */
        if (packageName.get() == null) {
            List<String> groups = new ArrayList<>();
            jarFile.stream().filter(f -> {
                String name = f.getName().replace("/", ".");
                return name.endsWith(".class") &&
                        groups.stream().noneMatch(name::startsWith) &&
                        new File(f.getName()).getParentFile() != null;
            }).forEach(f -> {
                String path = new File(f.getName()).getParentFile().getPath().replace("/", ".");
                Optional<String> result = groups.stream().map(group -> {
                    String prefix = StringUtils.getCommonPrefix(group, path);
                    if (prefix == null) {
                        return path;
                    } else if (prefix.split("\\.").length > minCommonPrefixLen) {
                        return prefix;
                    }
                    return null;
                }).filter(Objects::nonNull).findFirst();
                if (result.isPresent()) {
                    groups.removeAll(groups.stream().filter(group -> group.startsWith(result.get())).toList());
                    groups.add(result.get());
                } else {
                    groups.add(path);
                }
            });
            groups.forEach(pkgName -> addPkgFileMap(pkgName.replace("/", "."), file));
        }
    }

    /**
     * 根据 pkgName 获取所在的文件，应返回最长匹配结果
     */
    public static String[] getFileByPkgName(String owner) {
        if (pkgNameFileMap.isEmpty()) {
            throw new UnsupportedOperationException("pkgNameFileMap is empty");
        }
        AtomicInteger maxLen = new AtomicInteger();
        List<String> fileList = new ArrayList<>();
        pkgNameFileMap.entrySet().stream().filter(
                entry -> owner.startsWith(entry.getKey())
        ).forEach(entry -> {
            String pkgName = entry.getKey();
            if (pkgName.length() > maxLen.get()) {
                fileList.clear();
                fileList.addAll(pkgNameFileMap.get(pkgName));
                maxLen.set(pkgName.length());
            } else if (pkgName.length() == maxLen.get()) {
                fileList.addAll(pkgNameFileMap.get(pkgName));
            }
        });
        return fileList.toArray(new String[0]);
    }

    /**
     * 获取类所在的 Jar 包文件路径
     *
     * @param cName 查询的类名: a.b.c
     * @return jar 包文件路径 or null
     */
    public static String getFilePathByFullQualifiedName(String cName) {
        if (pkgNameFileMap.isEmpty()) {
            throw new UnsupportedOperationException("pkgNameFileMap is empty");
        }
        if (unKnownClassNameList.contains(cName)) {
            return null;
        }
        /**
         * 使用 pkgName 代替 className 减少搜索次数
         * 例外：两个 jar 包 x, y 同时存在 a.b.c pkgName，但是类只存在于 y 中，可能先缓存了 a.b.c -> x
         * 将可能导致通过 clsNameFileMap 返回的 jar 包并不包含该 类 的情况，可能比较特例暂不解决
         */
        String pkgName = cName.substring(0, cName.lastIndexOf("."));
        String filePath = clsNameFileMap.get(pkgName);
        if (filePath == null) {
            Optional<String> result = pkgNameFileMap.entrySet().stream().filter(
                    entry -> cName.startsWith(entry.getKey())
            ).map(entry -> entry.getValue().stream().filter(dir -> {
                        try {
                            return new JarFile(dir).stream().anyMatch(f -> {
                                String name = f.getName();
                                return name.endsWith(".class") && name.substring(0, name.lastIndexOf(".class")).replace("/", ".").equals(cName);
                            });
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }).findFirst().orElse(null)
            ).filter(Objects::nonNull).findFirst();
            if (result.isPresent()) {
                filePath = result.get();
                clsNameFileMap.put(pkgName, filePath);
            } else {
                unKnownClassNameList.add(cName);
            }
        }
        return filePath;
    }

    private static void addPkgFileMap(String pkgName, String file) {
        List<String> list = pkgNameFileMap.computeIfAbsent(pkgName, k -> new ArrayList<>());
        list.add(file);
    }


    public static Map<String, List<String>> getFileDependencyMap() {
        return fileDependencyMap;
    }

    public static Map<String, List<String>> getPkgNameFileMap() {
        return pkgNameFileMap;
    }

}
