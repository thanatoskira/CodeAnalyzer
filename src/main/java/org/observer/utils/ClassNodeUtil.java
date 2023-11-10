package org.observer.utils;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.ClassNode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import static org.observer.utils.StringUtil.x;
import static org.observer.utils.StringUtil.y;

public class ClassNodeUtil {
    public final static String jdkFileName = "rt.jar";
    private final static int cacheMaxSize = 3000;
    private final static Set<String> overMaxSizeJars = new HashSet<>();
    private final static Map<String, LoadingCache<String, ClassNode>> fileNodesMap = Caffeine.newBuilder()
            .maximumWeight(100000)
            .weigher((String key, LoadingCache<String, ClassNode> value) -> Long.valueOf(value.estimatedSize()).intValue())
            .build().asMap();

    // 缓存加载失败的 Class
    private final static Set<String> loadFailedClasses = new HashSet<>();
    // 缓存加载失败的 JarFile
    private final static Set<String> loadFailedJarFiles = new HashSet<>();
    /**
     * ClassReader.SKIP_CODE: skip the Code attributes
     * <p>
     * ClassReader.SKIP_DEBUG: skip the
     * SourceFile, SourceDebugExtension, LocalVariableTable,
     * LocalVariableTypeTable, LineNumberTable and MethodParameters attributes
     * <p>
     * ClassReader.SKIP_FRAMES:skip the StackMap and StackMapTable attributes
     */
    private final static int flag = ClassReader.SKIP_FRAMES;

    private static LoadingCache<String, ClassNode> newCaffeineMap() {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(30))
                .expireAfterAccess(Duration.ofMinutes(2))
                .build(ClassNodeUtil::getClassNodeByClassName);
    }

    public static List<ClassNode> loadAllPkgClassNodeFromFile(String file, String pkgName) {
        Map<String, ClassNode> classNodeMap = getAllClassNodeByFilterAndCache(file, f -> {
            String name = f.getName();
            return name.endsWith(".class") && !name.contains("/test/") && !name.contains("_") && name.startsWith(pkgName);
        });
        return classNodeMap.values().stream().toList();
    }

    // 加载 File 内所有的 Class
    public static Map<String, ClassNode> loadAllClassNodeFromFile(String file) {
        return getAllClassNodeByFilterAndCache(file, f -> {
            String name = f.getName();
            // 忽略如 clojure.core$_ 形式的类名
            return name.endsWith(".class") && !name.contains("/test/") && !name.contains("_");
        });
    }

    public static String getPkgName(String cName) {
        return y(cName.substring(0, cName.lastIndexOf(".")));
    }

    public static ClassNode getClassNodeFromCache(String cName) {
        String filePath = DependencyUtil.getJarPathFromCache(cName);
        return filePath != null ? fileNodesMap.computeIfAbsent(filePath, k -> newCaffeineMap()).get(cName) : null;
    }

    // 根据 className 加载对应的 ClassNode 对象
    private static ClassNode getClassNodeByClassName(String cName) {
        ClassNode classNode = null;
        if (!loadFailedClasses.contains(cName)) {
            // 从 JDK 中加载
            try {
                ClassReader reader = new ClassReader(cName);
                classNode = new ClassNode();
                reader.accept(classNode, flag);
            } catch (IOException ignored) {
                // 根据 className 加载
                String filePath = DependencyUtil.getJarPathFromCache(cName);
                if (filePath != null) {
                    try (URLClassLoader urlClassLoader = new URLClassLoader(new URL[]{new File(filePath).toURI().toURL()})) {
                        InputStream inputStream = urlClassLoader.getResourceAsStream(String.format("%s.class", y(cName)));
                        if (inputStream != null) {
                            ClassReader reader = new ClassReader(inputStream);
                            classNode = new ClassNode();
                            reader.accept(classNode, flag);
                        }
                    } catch (IOException e) {
                        System.out.println("[-] can not load class: " + cName + ", Error: " + e.getMessage());
                        loadFailedClasses.add(cName);
                    }
                }
            }
        }
        return classNode;
    }


    // 根据 filter 进行 ClassNode 加载
    private static Map<String, ClassNode> getAllClassNodeByFilterAndCache(String filePath, Predicate<ZipEntry> filter) {
        Map<String, ClassNode> classNodeMap = new HashMap<>();
        File file = new File(filePath);
        try {
            if (loadFailedJarFiles.contains(filePath)) {
                return classNodeMap;
            }
            try (JarFile jarFile = new JarFile(file)) {
                // 不包含 .class 文件直接抛出异常
                if (jarFile.stream().noneMatch(f -> f.getName().endsWith(".class"))) {
                    throw new RuntimeException("jar is empty");
                }
                AtomicInteger errorSize = new AtomicInteger(0);
                jarFile.stream().filter(filter).forEach(entry -> {
                    try {
                        InputStream stream = jarFile.getInputStream(entry);
                        ClassReader reader = new ClassReader(stream);
                        ClassNode node = new ClassNode();
                        reader.accept(node, flag);
                        classNodeMap.put(x(node.name), node);
                    } catch (IOException e) {
                        // 存在 5 个以上的 Class 加载失败则直接抛出异常
                        if (errorSize.addAndGet(1) > 3) {
                            throw new RuntimeException(e);
                        }
                        System.out.println("[-] can not load class: " + entry.getName() + "in: " + jarFile.getName() + ", error: " + e);
                    }
                });
            }
        } catch (Exception e) {
            System.out.println("[-] jar loaded failed: " + file.getName() + ", error: " + e);
            classNodeMap.clear();
            loadFailedJarFiles.add(filePath);
        }
        if (!classNodeMap.isEmpty() && !overMaxSizeJars.contains(filePath)) {
            if (classNodeMap.size() > cacheMaxSize) {
                overMaxSizeJars.add(filePath);
                System.out.printf("[!] execeed cacheMaxSize: %s, filePath: %s%n", classNodeMap.size(), filePath);
            } else {
                fileNodesMap.computeIfAbsent(filePath, k -> newCaffeineMap()).putAll(classNodeMap);
            }
        }
        return classNodeMap;
    }

    public static boolean isInterface(ClassNode classNode) {
        return (classNode.access & Opcodes.ACC_INTERFACE) != 0;
    }

    public static Map<String, ClassNode> loadAllClassNodeFromDir(String dir) throws Exception {
        Map<String, ClassNode> classNodeMap = new HashMap<>();
        try (Stream<Path> entries = Files.walk(Paths.get(dir))) {
            entries.filter(f -> f.endsWith(".jar")).forEach(f ->
                    classNodeMap.putAll(loadAllClassNodeFromFile(f.toString()))
            );
        }
        System.out.println("[+] Load ClassNodes From Dir Successfully");
        return classNodeMap;
    }

    public static Map<String, ClassNode> loadAllClassNodeFromJDK() throws Exception {
        FileSystem fileSystem = FileSystems.getFileSystem(URI.create("jrt:/"));
        PathMatcher matcher = fileSystem.getPathMatcher("glob:**/*.class");
        Map<String, ClassNode> classNodeMap = new HashMap<>();
        Files.walkFileTree(fileSystem.getPath("/modules"), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (matcher.matches(file)) {
                    ClassReader reader = new ClassReader(Files.newInputStream(file));
                    ClassNode node = new ClassNode();
                    reader.accept(node, flag);
                    classNodeMap.put(x(node.name), node);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        if (!classNodeMap.isEmpty()) {
            fileNodesMap.computeIfAbsent(jdkFileName, k -> newCaffeineMap()).putAll(classNodeMap);
        }
        return classNodeMap;
    }

    public static void printSize() {
        AtomicLong sum = new AtomicLong(0);
        fileNodesMap.values().forEach(x -> sum.addAndGet(x.estimatedSize()));
        System.out.println("fileNodesMap size: " + sum);
        System.out.println("loadFailedClasses size: " + loadFailedClasses.size());
        System.out.println("loadFailedJarFiles size: " + loadFailedJarFiles.size());
    }

}
