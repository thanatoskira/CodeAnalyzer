package org.observer.utils;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jdk.internal.org.objectweb.asm.Opcodes;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.observer.utils.StringUtil.x;
import static org.observer.utils.StringUtil.y;

// 用于解析 Jar 包并提取 packageName 及 dependencies
public class DependencyUtil {
    /**
     * dependency(groupId.artifactId) -> file 映射
     * artifactId 被哪些文件所依赖
     */
    private final static Map<String, Set<String>> artifactIdGroupFileMap = new ConcurrentHashMap<>();
    // jar 依赖哪些 artifactId
    private final static Map<String, Set<String>> fileArtifactIdGroupMap = new ConcurrentHashMap<>();
    // 不包含 pom.xml 文件的 jar 包，无法确认哪些包依赖该文件
    // 包含无法正确解析出 artifactId 的 jar 包
    private final static Set<String> missArtifactIdFiles = new HashSet<>();
    // 缓存缺失 pom.xml 的 jar
    private final static Set<String> missPomFiles = new HashSet<>();
    // packageName(和 groupId.artifactId 可能一致) -> file 映射
    private final static Map<String, Set<String>> pkgNameFileMap = new HashMap<>();
    // file -> groupId.artifactId 映射
    private final static Map<String, String> fileArtifactIdMap = new HashMap<>();
    // file 包含哪些 packageName
    private final static Map<String, Set<String>> filePkgNameMap = new HashMap<>();
    // 缓存加载失败的 JarFile
    private final static Set<String> loadFailedJarFiles = new HashSet<>();
    // 记录无法加载的类
    private final static Set<String> loadPathFailedClasses = new HashSet<>();
    // 缓存 call -> 父类/接口 类名映射
    private final static Map<Object, String> callOwnerCache = new ConcurrentHashMap<>();
    // 缓存 relatedDependencies 解析结果
    private final static Map<Object, Set<String>> relatedDependenciesCache = new ConcurrentHashMap<>();
    // 存储 lib 中 /rt.jar jdk 文件，后续用于排除
    private static String jdkFilePath = null;
    // 缓存 class -> 所在文件位置 映射
    private final static LoadingCache<String, String> clsNameFileMap = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(5))
            .expireAfterWrite(Duration.ofMinutes(2))
            .initialCapacity(300)
            .maximumSize(1000)
            .build(DependencyUtil::getJarPathByClassName);
    private final static MavenXpp3Reader reader = new MavenXpp3Reader();
    private final static int minCommonPrefixLen = 2;
    private final static Pattern artifactIdPattern = Pattern.compile("^[\\w.-]+$");
    private static int loadedJarCount = 0;
    public final static Pattern antFilePattern = Pattern.compile("^([\\w-.]+)_([\\w-]+)-((\\d+\\.\\d+(\\.\\d+)*|\\d+)[\\w-+.]*\\.jar)$");

    public static void resolveDir(String dir) throws Exception {
        if (!new File(dir).isDirectory()) {
            throw new RuntimeException(String.format("%s must be dir", dir));
        }
        try (Stream<Path> entries = Files.walk(Paths.get(dir))) {
            entries.filter(f -> f.toFile().getName().endsWith(".jar")).forEach(f -> {
                try {
                    resolve(f.toString());
                    loadedJarCount += 1;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        System.out.println("[!] loadedJar Count: " + loadedJarCount);
        System.out.println("[!] missArtifactIdFiles count: " + missArtifactIdFiles.size());
        System.out.println("[!] missPomFiles count: " + missPomFiles.size());
        System.out.println("[+] Resolve Dependencies Dir Successfully");
    }

    // 通过 pom.xml 建立 packageName -> dependencies 和 packageName -> files 映射
    public static void resolve(String file) throws Exception {
        try (JarFile jarFile = new JarFile(file)) {
            // 不包含 .class 文件直接跳过处理
            if (jarFile.stream().noneMatch(f -> f.getName().endsWith(".class"))) {
                loadFailedJarFiles.add(file);
                return;
            }
            if (isJDK(file)) {
                jdkFilePath = file;
                System.out.println("[!] Found rt.jar: " + jdkFilePath);
            }
            AtomicBoolean pomExist = new AtomicBoolean(false);
            AtomicReference<String> packageName = new AtomicReference<>(null);
            // TODO: 存在两个 pom.xml 的情况
            jarFile.stream().filter(f -> {
                String name = f.getName();
                return name.startsWith("META-INF") && name.endsWith("pom.xml");
            }).findFirst().ifPresent(xml -> {
                try {
                    pomExist.set(true);
                    Model model = reader.read(jarFile.getInputStream(xml));
                    String groupId = model.getGroupId() == null ? model.getParent().getGroupId() : model.getGroupId();
                    String artifactId = String.format("%s.%s", groupId, model.getArtifactId());
                    fileArtifactIdMap.put(file, artifactId);
                    // 判断 artifactId 与 packageName 是否一致
                    if ((jarFile.getJarEntry(y(artifactId)) != null)) {
                        packageName.set(artifactId);
                        addPkgFileMap(artifactId, file);
                    }
                    // 构建依赖链
                    Set<String> dependencies = model.getDependencies().stream().map(dep -> String.format("%s.%s", dep.getGroupId().equals("${project.groupId}") ? groupId : dep.getGroupId(), dep.getArtifactId())).collect(Collectors.toSet());
                    dependencies.forEach(dep -> {
                        Set<String> files = artifactIdGroupFileMap.computeIfAbsent(dep, k -> new HashSet<>());
                        files.add(file);
                    });
                    fileArtifactIdGroupMap.put(file, dependencies);
                } catch (IOException | XmlPullParserException e) {
                    throw new RuntimeException(e);
                }
            });

        /*
          不存在 pom.xml 文件情况，则通过 MANIFEST.MF 解析 artifactId
          如果可以通过 MANIFEST.MF 解析出 artifactId，则视为该依赖只依赖 JDK
          缺失映射关系:
           文件与包名的映射关系(fileArtifactMap)
           其他 jar 包与该文件的依赖的关系(dependencyFileMap)
         */
            if (!pomExist.get()) {
                missPomFiles.add(jarFile.getName());
                Manifest manifest = jarFile.getManifest();
                String artifactId = null;
                if (manifest != null) {
                /*
                    属于 Ant-Version 打包，文件名格式为 net.jcip_jcip-annotations-1.0.jar
                 */
                    String fileName = new File(jarFile.getName()).getName();
                    Matcher matcher = antFilePattern.matcher(fileName);
                    if (StringUtils.countMatches(fileName, "_") == 1 && matcher.matches()) {
                        artifactId = String.format("%s.%s", matcher.group(1), matcher.group(2));
                    }
                    if (!isValidArtifactId(artifactId)) {
                        artifactId = manifest.getMainAttributes().getValue("Bundle-SymbolicName");
                    }
                    if (!isValidArtifactId(artifactId) && artifactId != null && artifactIdPattern.matcher(artifactId).matches()) {
                        String importPackage = manifest.getMainAttributes().getValue("Import-Package");
                        if (importPackage != null) {
                            artifactId = String.format("%s.%s", importPackage.split(";")[0], artifactId);
                        }
                    }

                    if (!isValidArtifactId(artifactId)) {
                        artifactId = manifest.getMainAttributes().getValue("Automatic-Module-Name");
                    }

                    if (!isValidArtifactId(artifactId)) {
                        artifactId = manifest.getMainAttributes().getValue("Implementation-Vendor-Id");
                    }
                    if (!isValidArtifactId(artifactId)) {
                        artifactId = manifest.getMainAttributes().getValue("Implementation-Title");
                        if (artifactId != null) {
                            artifactId = artifactId.contains(";") ? artifactId.split(";")[0] : artifactId;
                            artifactId = artifactId.replace("#", ".");
                        }
                    }

                    if (!isValidArtifactId(artifactId)) {
                        // 从 entries 中搜索
                        artifactId = manifest.getEntries().values().stream().filter(
                                value -> isValidArtifactId(value.getValue("Implementation-Title"))
                        ).map(value -> value.getValue("Implementation-Title")).findFirst().orElse(null);
                    }
                }
                if (isValidArtifactId(artifactId)) {
                    fileArtifactIdMap.put(file, artifactId);
                } else {
                    if (System.getProperty("log.print", "false").equals("true")) {
                        System.out.println("[-] artifactId is not valid: " + artifactId + ", " + file);
                    }
                    missArtifactIdFiles.add(file);
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
                    String name = x(f.getName());
                    // 排除特例：log4j-api-2.13.2.jar!/META-INF/versions/9/module-info.class
                    return !name.contains("META-INF.") && name.endsWith(".class") &&
                            groups.stream().noneMatch(name::startsWith) &&
                            new File(f.getName()).getParentFile() != null;
                }).forEach(f -> {
                    String path = x(new File(f.getName()).getParentFile().getPath());
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
                groups.forEach(pkgName -> addPkgFileMap(x(pkgName), file));
            }
        }
    }

    private static boolean isValidArtifactId(String artifactId) {
        return artifactId != null && artifactId.contains(".") && artifactIdPattern.matcher(artifactId).matches();
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

    public static String getJarPathFromCache(String cName) {
        return clsNameFileMap.get(cName);
    }

    private static String getJarPathByClassName(String cName) {
        String filePath = null;
        if (!loadPathFailedClasses.contains(cName)) {
            try {
                Class.forName(cName, false, ClassLoader.getSystemClassLoader().getParent());
                filePath = ClassNodeUtil.jdkFileName;
            } catch (ClassNotFoundException ignored) {
                filePath = DependencyUtil.getFilesByPkgName(cName).stream().filter(path -> {
                    try (URLClassLoader urlClassLoader = new URLClassLoader(new URL[]{new File(path).toURI().toURL()})) {
                        return urlClassLoader.getResourceAsStream(y(cName) + ".class") != null;
                    } catch (Exception e) {
                        System.out.println("xxx: " + path + ", " + cName);
                        return false;
                    }
                }).findFirst().orElse(null);
                if (filePath == null) {
                    if (System.getProperty("log.print", "false").equals("true")) {
                        System.out.println("[-] can not get file by class name: " + cName);
                    }
                    loadPathFailedClasses.add(cName);
                }
            }
        }
        return filePath;
    }


    // 获取 callee 对应的接口
    public static String getCalleeOwnerInterfaceName(String callee) {
        String[] callItems = callee.split("#");
        if (!MethodUtil.isValidMethod(callItems[1])) {
            return null;
        }
        if (callOwnerCache.containsKey(callee)) {
            String owner = callOwnerCache.get(callee);
            return owner.isEmpty() ? null : owner;
        }
        String callOwner = HierarchyUtil.getMatchSuperName(callItems[0], callItems[1], callItems[2], true);
        callOwnerCache.put(callee, callOwner == null ? "" : callOwner);
        return callOwner;
    }

    // 获取依赖 call 所在 jar 包的依赖项
    public static Set<String> getCallDependencies(String finalCall) {
        String[] callItems = finalCall.split("#");

        int fAccess = Integer.parseInt(callItems[3]);
        boolean isPublic = (fAccess & Opcodes.ACC_PUBLIC) != 0;

        String jarPath = getJarPathFromCache(callItems[0]);
        Set<String> retSet = new HashSet<>();
        if (jarPath != null) {
            if (isPublic) {
                if (isJDK(jarPath)) {
                    retSet.addAll(getAllDependencies());
                } else {
                    retSet.addAll(relatedDependencies(jarPath, false));
                    retSet.addAll(missPomFiles);
                }
            } else {
                retSet.add(jarPath);
            }
            retSet.removeAll(loadFailedJarFiles);
            if (System.getProperty("jdk.scan", "false").equals("false") && jdkFilePath != null) {
                retSet.remove(jdkFilePath);
            }
        }
        return retSet;
    }

    private static boolean isJDK(String file) {
        return file.equals(ClassNodeUtil.jdkFileName) || file.endsWith("/rt.jar");
    }

    // 待扫描的 lib 应只存在于 unCertainFiles 或 fileArtifactMap 中
    private static Set<String> getAllDependencies() {
        Set<String> retSet = new HashSet<>();
        retSet.addAll(missArtifactIdFiles);
        retSet.addAll(fileArtifactIdMap.keySet());
        return retSet;
    }

    // 递归获取所有相关依赖，down: true 表示向下搜索所有所需的依赖项，false 表示向上搜索依赖当前 Jar 包的依赖项
    private static Set<String> relatedDependencies(String jarPath, boolean isDown) {
        if (relatedDependenciesCache.containsKey(jarPath)) {
            return relatedDependenciesCache.get(jarPath);
        }
        Set<String> results = new HashSet<>();
        collect(jarPath, isDown, results);
        relatedDependenciesCache.put(jarPath, results);
        return results;
    }

    private static void collect(String jarPath, boolean isDown, Set<String> loaded) {
        if (!loaded.contains(jarPath)) {
            Set<String> result = new HashSet<>();
            loaded.add(jarPath);
            if (isDown) {
                // 先获取 jar 依赖的 pkgName，再根据 pkgName 获取对应的 Jar，递归获取所有相关依赖
                result.addAll(fileArtifactIdGroupMap.get(jarPath).stream().map(DependencyUtil::getFilesByPkgName).flatMap(Collection::stream).collect(Collectors.toSet()));
            } else {
                // 先获取 jar 的 artifactId，再获取依赖该 artifactId 的依赖项文件，递归获取所有相关依赖
                String artifactId = fileArtifactIdMap.get(jarPath);
                Set<String> files = artifactId != null ? artifactIdGroupFileMap.get(artifactId) : null;
                // artifactId 正确且正确获取对其依赖 jar 包
                if (files != null) {
                    result.addAll(files);
                } else {
                    if (artifactId == null || missPomFiles.contains(jarPath)) {
                        filePkgNameMap.get(jarPath).forEach(pkgName -> artifactIdGroupFileMap.keySet().stream().filter(
                                id -> id.startsWith(pkgName)
                        ).flatMap(id -> artifactIdGroupFileMap.get(id).stream()).forEach(result::add));
                    }
                }
            }
            if (!result.isEmpty()) {
                Set<String> copyResult = new HashSet<>(result);
                copyResult.removeAll(loaded);
                copyResult.forEach(f -> collect(f, isDown, loaded));
            }
        }
    }

    private static void addPkgFileMap(String pkgName, String file) {
        filePkgNameMap.computeIfAbsent(file, k -> new HashSet<>()).add(pkgName);
        pkgNameFileMap.computeIfAbsent(pkgName, k -> new HashSet<>()).add(file);
    }

    public static void printSize() {
        System.out.println("pkgNameFileMap size: " + sum(pkgNameFileMap));
        System.out.println("dependencyFileMap size: " + sum(artifactIdGroupFileMap));
        System.out.println("fileDependencyMap size: " + sum(fileArtifactIdGroupMap));
        System.out.println("unCertainFiles size: " + missArtifactIdFiles.size());
        System.out.println("loadFailedJarFiles size: " + loadFailedJarFiles.size());
        System.out.println("callOwnerCache size: " + callOwnerCache.size());
        System.out.println("clsNameFileMap size: " + clsNameFileMap.estimatedSize());
    }

    private static int sum(Map map) {
        AtomicInteger sum = new AtomicInteger();
        map.values().forEach(x -> sum.addAndGet(((Collection) x).size()));
        return sum.get();
    }
}