package org.observer.utils;

import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// 用于解析 Jar 包并提取 packageName 及 dependencies
public class DependencyUtil {
    /**
     * dependency(groupId.artifactId) -> file 映射
     * artifactId 被哪些文件所依赖
     */
    private final static Map<String, Set<String>> artifactIdGroupFileMap = new HashMap<>();
    // jar 依赖哪些 artifactId
    private final static Map<String, Set<String>> fileArtifactIdGroupMap = new HashMap<>();
    // 不包含 pom.xml 文件的 jar 包，无法确认哪些包依赖该文件
    private final static Set<String> unCertainFiles = new HashSet<>();
    // packageName(和 groupId.artifactId 可能一致) -> file 映射
    private final static Map<String, List<String>> pkgNameFileMap = new HashMap<>();
    // file -> groupId.artifactId 映射
    private final static Map<String, String> fileArtifactMap = new HashMap<>();
    // 缓存加载失败的 JarFile
    private final static Set<String> loadFailedJarFiles = new CopyOnWriteArraySet<>();
    // 记录无法加载的类
    private final static List<String> loadPathFailedClasses = new CopyOnWriteArrayList<>();
    // 缓存 call -> 父类/接口 类名映射
    private final static Map<Object, String> callOwnerCache = new ConcurrentHashMap<>();
    // 存储 lib 中 /rt.jar jdk 文件，后续用于排除
    private static String jdkFilePath = null;
    // 缓存 class -> 所在文件位置 映射
    private final static Map<Object, String> clsNameFileMap = new ConcurrentHashMap<>();
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

    // 通过 pom.xml 建立 packageName -> dependencies 和 packageName -> files 映射
    public static void resolve(String file) throws Exception {
        JarFile jarFile = new JarFile(file);

        AtomicReference<String> packageName = new AtomicReference<>(null);
        AtomicBoolean pomExist = new AtomicBoolean(false);
        // 不包含 .class 文件直接跳过处理
        if (jarFile.stream().noneMatch(f -> f.getName().endsWith(".class"))) {
            loadFailedJarFiles.add(file);
            return;
        }
        if (isJDK(file)) {
            jdkFilePath = file;
            System.out.println("[!] Found rt.jar: " + jdkFilePath);
        }
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
                Set<String> depList = model.getDependencies().stream().map(dep -> String.format("%s.%s", dep.getGroupId().equals("${project.groupId}") ? groupId : dep.getGroupId(), dep.getArtifactId())).collect(Collectors.toSet());
                depList.forEach(dep -> {
                    Set<String> files = artifactIdGroupFileMap.computeIfAbsent(dep, k -> new HashSet<>());
                    files.add(file);
                });
                fileArtifactIdGroupMap.put(file, depList);
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
            unCertainFiles.add(file);
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
            if (pkgName != null && !pkgNamePattern.matcher(pkgName).matches()) {
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

    // 根据 pkgName 获取所在的文件，应返回最长匹配结果，返回集合的原因在于存在同前缀的情况
    public static Set<String> getFilesByPkgName(String owner) {
        if (pkgNameFileMap.isEmpty()) {
            throw new UnsupportedOperationException("pkgNameFileMap is empty");
        }
        AtomicInteger maxLen = new AtomicInteger();
        Set<String> fileList = new HashSet<>();
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
        return fileList;
    }

    /**
     * 获取类所在的 Jar 包文件路径
     *
     * @param cName 查询的类名: a.b.c
     * @return jar 包文件路径 or null
     */
    public static String getFilePathByFullQualifiedName(String cName) {
        if (pkgNameFileMap.isEmpty()) {
            throw new UnsupportedOperationException("[-] pkgNameFileMap is empty");
        }
        if (loadPathFailedClasses.contains(cName)) {
            return null;
        }
        /*
          不可采用 pkgName 代替 className 减少搜索次数
          例外：两个 jar 包 x, y 同时存在 a.b.c pkgName，但是类只存在于 y 中，可能先缓存了 a.b.c -> x
          将可能导致通过 clsNameFileMap 返回的 jar 包并不包含该 类 的情况
         */
        String filePath = clsNameFileMap.get(cName);
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
            }
        }
        // load from jdk
        if (filePath == null) {
            try {
                Class.forName(cName);
                filePath = ClassNodeUtil.jdkFileName;
                clsNameFileMap.put(cName, filePath);
            } catch (ClassNotFoundException ignored) {
            }
        }
        if (filePath == null) {
            System.out.println("[-] can not get file by class name: " + cName);
            loadPathFailedClasses.add(cName);
        }
        return filePath;
    }

    // 获取 callee 对应的 父类/接口
    public static String getCallOwner(String callee) {
        if (callOwnerCache.containsKey(callee)) {
            String owner = callOwnerCache.get(callee);
            return owner.isEmpty() ? null : owner;
        }
        String[] callItems = callee.split("#");
        Map<Object, ClassNode> loadedClassNodeMap = new ConcurrentHashMap<>();

        String callOwner = HierarchyUtil.getMatchClzName(callItems[0], callItems[1], callItems[2], loadedClassNodeMap);
        if (callOwner != null) {
            // 如果实现的为 JDK 的接口则不进行回溯，如 java.lang.Runnable#run 会造成大量回溯结构
            try {
                Class.forName(callOwner);
                if (System.getProperty("log.print", "false").equals("true")) {
                    System.out.printf("[!] JDK Interface Skip %s#%s#%s, From: %s%n", callOwner, callItems[1], callItems[2], callee);
                }
                callOwner = null;
            } catch (ClassNotFoundException ignored) {
            }
            // callOwnerCache value 无法设置 null
            callOwnerCache.put(callee, callOwner == null ? "" : callOwner);
        }
        return callOwner;
    }

    // 获取依赖 call 所在 jar 包的依赖项
    public static Set<String> getCallDependencies(String finalCall) {
        String[] callItems = finalCall.split("#");

        int fAccess = Integer.parseInt(callItems[3]);
        boolean isPublic = (fAccess & Opcodes.ACC_PUBLIC) != 0;

        String jarPath = getFilePathByFullQualifiedName(callItems[0]);
        Set<String> retSet = new HashSet<>();
        if (jarPath == null) {
            return retSet;
        }
        if (isPublic) {
            if (isJDK(jarPath)) {
                retSet.addAll(getAllDependencies());
            } else {
                retSet.addAll(unCertainFiles);
                retSet.addAll(relatedDependencies(jarPath, false));
            }
        } else {
            retSet.add(jarPath);
        }
        retSet.removeAll(loadFailedJarFiles);
        // 过滤 jdk 回溯
        if (System.getProperty("jdk.scan", "false").equals("false") && jdkFilePath != null) {
            retSet.remove(jdkFilePath);
        }
        return retSet;
    }

    private static boolean isJDK(String file) {
        return file.equals(ClassNodeUtil.jdkFileName) || file.endsWith("/rt.jar");
    }

    // 待扫描的 lib 应只存在于 unCertainFiles 或 fileArtifactMap 中
    private static Set<String> getAllDependencies() {
        Set<String> retSet = new HashSet<>();
        retSet.addAll(unCertainFiles);
        retSet.addAll(fileArtifactMap.keySet());
        return retSet;
    }

    // 递归获取所有相关依赖，down: true 表示向下搜索所有所需的依赖项，false 表示向上搜索依赖当前 Jar 包的依赖项
    private static Set<String> relatedDependencies(String jarPath, boolean isDown) {
        Set<String> results = new HashSet<>();
        collect(jarPath, isDown, results);
        return results;
    }

    private static void collect(String jarPath, boolean isDown, Set<String> loaded) {
        if (!loaded.contains(jarPath)) {
            Set<String> result;
            loaded.add(jarPath);
            if (isDown) {
                // 先获取 jar 依赖的 pkgName，再根据 pkgName 获取对应的 Jar，递归获取所有相关依赖
                result = fileArtifactIdGroupMap.get(jarPath).stream().map(DependencyUtil::getFilesByPkgName).flatMap(Collection::stream).collect(Collectors.toSet());
            } else {
                // 先获取 jar 的 artifactId，再获取依赖该 artifactId 的依赖项文件，递归获取所有相关依赖
                result = artifactIdGroupFileMap.get(fileArtifactMap.get(jarPath));
                if (result == null) {
                    if (System.getProperty("log.print", "false").equals("true")) {
                        System.out.println("[-] can not get file by artifactId: " + fileArtifactMap.get(jarPath));
                    }
                    return;
                }
            }
            Set<String> copyResult = new HashSet<>(result);
            copyResult.removeAll(loaded);
            copyResult.forEach(f -> collect(f, isDown, loaded));
        }
    }

    private static void addPkgFileMap(String pkgName, String file) {
        List<String> list = pkgNameFileMap.computeIfAbsent(pkgName, k -> new ArrayList<>());
        list.add(file);
    }

    public static void printSize() {
        System.out.println("pkgNameFileMap size: " + sum(pkgNameFileMap));
        System.out.println("dependencyFileMap size: " + sum(artifactIdGroupFileMap));
        System.out.println("fileDependencyMap size: " + sum(fileArtifactIdGroupMap));
        System.out.println("unCertainFiles size: " + unCertainFiles.size());
        System.out.println("loadFailedJarFiles size: " + loadFailedJarFiles.size());
        System.out.println("callOwnerCache size: " + callOwnerCache.size());
        System.out.println("clsNameFileMap size: " + clsNameFileMap.size());
    }

    private static int sum(Map map) {
        AtomicInteger sum = new AtomicInteger();
        map.values().forEach(x -> sum.addAndGet(((Collection) x).size()));
        return sum.get();
    }
}