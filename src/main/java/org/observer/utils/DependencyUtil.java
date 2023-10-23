package org.observer.utils;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * 用于解析 Jar 包并提取 packageName 及 dependencies
 */
public class DependencyUtil {
    /**
     * dependency(groupId.artifactId) -> file 映射
     * 当前 jar 包被哪些文件所依赖
     */
    private final static Map<String, List<String>> dependencyFileMap = new HashMap<>();

    /**
     * 不包含 pom.xml 文件的 jar 包，无法确认哪些包依赖该文件
     */
    private final static List<String> unCertainFiles = new ArrayList<>();
    /**
     * packageName(和 groupId.artifactId 可能一致) -> file 映射
     */
    private final static Map<String, List<String>> pkgNameFileMap = new HashMap<>();
    /**
     * file -> groupId.artifactId 映射
     */
    private final static Map<String, String> fileArtifactMap = new HashMap<>();
    /**
     * 记录无法加载的类
     */
    private final static List<String> unKnownClassNameList = new CopyOnWriteArrayList<>();
    /**
     * 缓存 class -> file 映射
     */
    private final static Map<Object, Object> clsNameFileMap = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(5))
            .build().asMap();
    private final static MavenXpp3Reader reader = new MavenXpp3Reader();
    private final static int minCommonPrefixLen = 2;
    private final static String[] pkgPrefix = new String[]{"Automatic-Module-Name", "Implementation-Title", "Implementation-Vendor-Id", "Bundle-SymbolicName"};
    private final static Pattern pkgNamePattern = Pattern.compile("[\\w\\-;.\\d#]+");

    public static void resolveDir(String dir) throws Exception {
        if (!new File(dir).isDirectory()) {
            throw new RuntimeException(String.format("%s must be dir", dir));
        }
        Files.walk(Paths.get(dir)).filter(f -> f.toFile().getName().endsWith(".jar")).forEach(f -> {
            try {
                resolve(f.toString());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        System.out.println("[+] Resolve Dependencies Dir Successfully");
    }

    /**
     * 通过 pom.xml 建立 packageName -> dependencies 和 packageName -> files 映射
     */
    public static void resolve(String file) throws Exception {
        JarFile jarFile = new JarFile(file);

        AtomicReference<String> packageName = new AtomicReference<>(null);
        AtomicBoolean pomExist = new AtomicBoolean(false);
        // TODO: 存在两个 pom.xml 的情况
        jarFile.stream().filter(f -> {
            String name = f.getName();
            return name.startsWith("META-INF") && name.endsWith("pom.xml");
        }).findFirst().ifPresent(xml -> {
            try {
                pomExist.set(true);
                Model model = reader.read(jarFile.getInputStream(xml));
                String groupId = model.getGroupId() == null ? model.getParent().getGroupId() : model.getGroupId();
                packageName.set(String.format("%s.%s", groupId, model.getArtifactId()));
                fileArtifactMap.put(file, packageName.get());
                if (jarFile.getJarEntry(packageName.get().replace(".", "/")) == null) {
                    packageName.set(null);
                } else {
                    addPkgFileMap(packageName.get(), file);
                }
                List<String> depList = model.getDependencies().stream().map(dep -> String.format("%s.%s", dep.getGroupId().equals("${project.groupId}") ? groupId : dep.getGroupId(), dep.getArtifactId())).toList();
                depList.forEach(dep -> {
                    List<String> files = dependencyFileMap.computeIfAbsent(dep, k -> new ArrayList<>());
                    files.add(file);
                });
            } catch (IOException | XmlPullParserException e) {
                throw new RuntimeException(e);
            }
        });

        /*
          不存在 pom.xml 文件情况，则对 MANIFEST.MF 进行解析
          缺失:
           文件与包名的映射关系(fileArtifactMap)
           其他 jar 包与该文件的依赖的关系(dependencyFileMap)
         */
        if (!pomExist.get()) {
            Map<String, String> mfs = new ConcurrentHashMap<>();
            jarFile.stream().filter(f -> {
                String name = f.getName();
                return name.startsWith("META-INF") && name.endsWith("MANIFEST.MF");
            }).findFirst().ifPresent(mf -> {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(jarFile.getInputStream(mf)));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (Arrays.stream(pkgPrefix).anyMatch(line::startsWith)) {
                            String[] splits = line.split(":");
                            mfs.put(splits[0].trim(), splits[1].trim());
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            String pkgName = null;
            if (mfs.containsKey("Automatic-Module-Name")) {
                pkgName = mfs.get("Automatic-Module-Name");
            } else if (mfs.containsKey("Implementation-Vendor-Id")) {
                pkgName = mfs.get("Implementation-Vendor-Id");
            } else if (mfs.containsKey("Implementation-Title")) {
                pkgName = mfs.get("Implementation-Title");
                pkgName = pkgName.contains(";") ? pkgName.split(";")[0] : pkgName;
                pkgName = pkgName.replace("#", ".");
            } else if (mfs.containsKey("Bundle-SymbolicName")) {
                pkgName = mfs.get("Bundle-SymbolicName");
            }
            if (pkgName == null || !pkgNamePattern.matcher(pkgName).matches()) {
                unCertainFiles.add(file);
            } else {
                fileArtifactMap.put(file, pkgName);
            }
        }

        /*
          1 .存在 groupId.artifactId 与 packageName 不一致的情况(已解决)
          eg: cglib-3.2.12.jar: cglib.cglib -> net.sf.cglib
          2. 存在 jar 包含多个 packageName 的情况(已解决)
          eg: atlassian-plugins-core-5.7.14.jar -> org/codehaus/classworlds/uberjar/protocol/jar/ 和 com/atlassian/plugin/
          3. 存在多个 jar 包存在相同 packageName 的情况(已解决)
          eg: org.apache.lucene -> lucene-core-4.4.0-atlassian-6.jar / lucene-misc-4.4.0-atlassian-6.jar
          4. 存在 org/ org/apache/felix org/osgi/ 目录分组错误情况(已解决)
          right：[org/apache/felix, org/osgi/]
          error：[org/]
          5. 存在不包含目录的 Class 文件(已解决)
          eg: module-info.class
          6. 存在 jar 中不包含 pom.xml 文件的情况

         */
        if (packageName.get() == null) {
            List<String> groups = new ArrayList<>();
            jarFile.stream().filter(f -> {
                String name = f.getName().replace("/", ".");
                // 排除特例：log4j-api-2.13.2.jar!/META-INF/versions/9/module-info.class
                return !name.contains("META-INF.") && name.endsWith(".class") &&
                        groups.stream().noneMatch(name::startsWith) &&
                        new File(f.getName()).getParentFile() != null;
            }).forEach(f -> {
                String path = new File(f.getName()).getParentFile().getPath().replace("/", ".");
                Optional<String> result = groups.stream().map(group -> {
                    String prefix = StringUtils.getCommonPrefix(group, path);
                    // prefix 不会返回 null，返回 ""
                    if (prefix.isEmpty()) {
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
        /*
          不可采用 pkgName 代替 className 减少搜索次数
          例外：两个 jar 包 x, y 同时存在 a.b.c pkgName，但是类只存在于 y 中，可能先缓存了 a.b.c -> x
          将可能导致通过 clsNameFileMap 返回的 jar 包并不包含该 类 的情况
         */
        String filePath = (String) clsNameFileMap.get(cName);
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
                clsNameFileMap.put(cName, filePath);
            } else {
                unKnownClassNameList.add(cName);
            }
        }
        return filePath;
    }

    /**
     * 获取 cName 所在及依赖该 jar 包的文件
     */
    public static Set<String> getAllDependencies(String cName) {
        // 当只有一个 jar 包的情况下，允许存在 dependencyFileMap.isEmpty 的情况
        // 所有缺失 pom.xml 文件的 jar 包均视为对当前 cName 进行依赖，cName 所在 Jar 包可能位于 unCertainFiles 中，因此使用 Set
        Set<String> result = new HashSet<>(unCertainFiles);
        String file = DependencyUtil.getFilePathByFullQualifiedName(cName);
        if (file == null) {
            // 属于 JDK 中的类，但是未手动调用 ClasNodeUtil.loadAllClassNodeFromJDK() 进行类加载
            // 这时 dependencies 中不应包含 rt.jar
            try {
                Class.forName(cName);
                file = ClassNodeUtil.jdkFileName;
            } catch (ClassNotFoundException e) {
                System.out.println("[-] can not get file by class name: " + cName);
                return result;
            }
        } else {
            result.add(file);
        }
        if (file.equals(ClassNodeUtil.jdkFileName)) {
            /*
              如果 cName 来自 JDK，则所有 jar 包应作为依赖进行返回
             */
            result.addAll(fileArtifactMap.keySet());
        } else {
            /*
              应使用 jar 的 groupId.artifactID 进行依赖搜索
              artifactName == null 表示当前 jar 包缺失 pom.xml
              存在 pom.xml 的 jar 包正常应不会包含缺失 pom.xml 的依赖
             */
            String artifactName = fileArtifactMap.get(file);
            if (artifactName != null) {
                dependencyFileMap.entrySet().stream().filter(entry -> artifactName.startsWith(entry.getKey())).map(Map.Entry::getValue).forEach(result::addAll);
            }
        }
        return result;
    }

    private static void addPkgFileMap(String pkgName, String file) {
        List<String> list = pkgNameFileMap.computeIfAbsent(pkgName, k -> new ArrayList<>());
        list.add(file);
    }


    public static Map<String, List<String>> getDependencyFileMap() {
        return dependencyFileMap;
    }

    public static Map<String, List<String>> getPkgNameFileMap() {
        return pkgNameFileMap;
    }
}
