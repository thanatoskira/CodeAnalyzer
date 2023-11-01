package org.observer.utils;

import com.github.benmanes.caffeine.cache.Caffeine;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.tree.ClassNode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

public class ClassNodeUtil {
    public final static String jdkFileName = "rt.jar";
    private final static ConcurrentMap<Object, Map<Object, ClassNode>> fileNodesMap = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(5))
            .initialCapacity(30)
            .maximumSize(100)
            .build(k -> (Map<Object, ClassNode>) new HashMap()).asMap();

    // 缓存加载失败的 Class
    private final static Set<String> loadFailedClasses = new CopyOnWriteArraySet<>();
    // 缓存加载失败的 JarFile
    private final static Set<String> loadFailedJarFiles = new CopyOnWriteArraySet<>();
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

    public static void loadAllClassNodeFromDir(String dir) throws Exception {
        Files.walk(Paths.get(dir)).filter(f -> f.endsWith(".jar")).forEach(f ->
                loadAllClassNodeFromFile(f.toString())
        );
        System.out.println("[+] Load ClassNodes From Dir Successfully");
    }

    /**
     * 加载 File 内所有的 Class
     */
    public static Map<Object, ClassNode> loadAllClassNodeFromFile(String file) {
        return loadClassNodeByFilter(file, f -> f.getName().endsWith(".class"));
    }

    public static ClassNode getSingleClassNodeFromJDK(String cName) {
        if (loadFailedClasses.contains(cName)) {
            return null;
        }
        Map<Object, ClassNode> classNodeMap = fileNodesMap.computeIfAbsent(jdkFileName, k -> new HashMap<>());
        ClassNode classNode = classNodeMap.get(cName);
        if (classNode == null) {
            try {
                ClassReader reader = new ClassReader(cName);
                classNode = new ClassNode();
                reader.accept(classNode, flag);
                classNodeMap.put(cName, classNode);
            } catch (IOException e) {
                // 尝试加载的类不存在于 classpath 和 rt.jar
                System.out.println("[-] can not load class: " + cName + ", Error: " + e.getMessage());
                loadFailedClasses.add(cName);
                return null;
            }
        }
        return classNode;
    }

    /**
     * 加载 JDK 中的 Classes
     */
    public static boolean loadAllClassNodeFromJDK() throws Exception {
        FileSystem fileSystem = FileSystems.getFileSystem(URI.create("jrt:/"));
        PathMatcher matcher = fileSystem.getPathMatcher("glob:**/*.class");
        Map<Object, ClassNode> classNodeMap = fileNodesMap.get(jdkFileName);
        Files.walkFileTree(fileSystem.getPath("/modules"), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (matcher.matches(file)) {
                    ClassReader reader = new ClassReader(Files.newInputStream(file));
                    ClassNode node = new ClassNode();
                    reader.accept(node, flag);
                    classNodeMap.put(node.name.replace("/", "."), node);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return true;
    }

    /**
     * 根据 filter 进行 ClassNode 加载
     */
    private static Map<Object, ClassNode> loadClassNodeByFilter(String filePath, Predicate<ZipEntry> filter) {
        Map<Object, ClassNode> classNodeMap = fileNodesMap.computeIfAbsent(filePath, k -> new HashMap<>());
        if (!classNodeMap.isEmpty()) {
            return classNodeMap;
        }
        File file = new File(filePath);
        try {
            if (loadFailedJarFiles.contains(filePath)) {
                return classNodeMap;
            }
            JarFile jarFile = new JarFile(file);
            // 不包含 .class 文件直接抛出异常
            if (jarFile.stream().noneMatch(f -> f.getName().endsWith(".class"))) {
                throw new RuntimeException("jar is empty");
            }
            AtomicInteger errorSize = new AtomicInteger(0);
            jarFile.stream().filter(filter).filter(f -> !f.getName().contains("/test/")).forEach(entry -> {
                try {
                    InputStream stream = jarFile.getInputStream(entry);
                    ClassReader reader = new ClassReader(stream);
                    ClassNode node = new ClassNode();
                    reader.accept(node, flag);
                    classNodeMap.put(node.name.replace("/", "."), node);
                } catch (IOException e) {
                    // 存在 5 个以上的 Class 加载失败则直接抛出异常
                    if (errorSize.addAndGet(1) > 3) {
                        throw new RuntimeException(e);
                    }
                    System.out.println("[-] can not load class: " + entry.getName() + "in: " + jarFile.getName() + ", error: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            System.out.println("[-] jar loaded failed: " + file.getName() + ", error: " + e.getMessage());
            classNodeMap.clear();
            loadFailedJarFiles.add(filePath);
        }
        return classNodeMap;
    }

    /**
     * 搜索与 cName 同 package 的 ClassNode
     *
     * @param nodeMap 类名 -> ClassNode 映射
     * @param cName   类名
     * @return ClassNode 列表
     */
    public static List<ClassNode> getAllClassNodeByPkgName(Map<Object, ClassNode> nodeMap, String cName) {
        String pkgName = cName.substring(0, cName.lastIndexOf("."));
        return nodeMap.values().stream().filter(node -> node.name.startsWith(pkgName)).toList();
    }

    public static void printSize() {
        AtomicInteger sum = new AtomicInteger(0);
        fileNodesMap.values().forEach(x -> sum.addAndGet(x.size()));
        System.out.println("fileNodesMap size: " + sum);
        System.out.println("loadFailedClasses size: " + loadFailedClasses.size());
        System.out.println("loadFailedJarFiles size: " + loadFailedJarFiles.size());
    }

}
