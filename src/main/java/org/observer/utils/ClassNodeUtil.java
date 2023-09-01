package org.observer.utils;

import com.github.benmanes.caffeine.cache.Caffeine;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

public class ClassNodeUtil {
    public final static String jdkFileName = "rt.jar";
    private final static Map<String, Map<Object, ClassNode>> fileNodesMap = new ConcurrentHashMap<>();

    /**
     * ClassReader.SKIP_CODE: skip the Code attributes
     * <p>
     * ClassReader.SKIP_DEBUG: skip the
     * SourceFile, SourceDebugExtension, LocalVariableTable,
     * LocalVariableTypeTable, LineNumberTable and MethodParameters attributes
     * <p>
     * ClassReader.SKIP_FRAMES:skip the StackMap and StackMapTable attributes
     */
    private static int flag = ClassReader.SKIP_FRAMES;

    public static void loadClassNodeFromDir(String dir) throws Exception {
        Files.walk(Paths.get(dir)).filter(f -> f.endsWith(".jar")).forEach(f -> {
            try {
                loadAllClassNodeFromFile(f.toString());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        System.out.println("[+] Load ClassNodes From Dir Successfully");
    }

    /**
     * 加载 File 内所有的 Class
     */
    public static boolean loadAllClassNodeFromFile(String file) throws Exception {
        return loadClassNode(file, f -> f.getName().endsWith(".class"));
    }

    public static ClassNode getClassNodeFromJDK(String cName) {
        Map<Object, ClassNode> classNodeMap = fileNodesMap.computeIfAbsent(jdkFileName, k -> newExpiringMap());
        ClassNode node = classNodeMap.get(cName);
        if (node == null) {
            try {
                ClassReader reader = new ClassReader(cName);
                node = new ClassNode();
                reader.accept(node, flag);
                classNodeMap.put(cName, node);
            } catch (IOException e) {
                // 尝试加载的类不存在于 classpath 和 rt.jar
                System.out.println("[-] can not load class: " + cName);
                return null;
            }
        }
        return node;
    }

    /**
     * 加载 JDK 中的 Classes
     */
    public static boolean loadAllClassNodeFromJDK() throws Exception {
        FileSystem fileSystem = FileSystems.getFileSystem(URI.create("jrt:/"));
        PathMatcher matcher = fileSystem.getPathMatcher("glob:**/*.class");
        Map<Object, ClassNode> classNodeMap = fileNodesMap.computeIfAbsent(jdkFileName, k -> newExpiringMap());
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
     * 根据 className 加载对应的 ClassNode 对象
     *
     * @param cName a.b.c
     */
    private static ClassNode lazyLoadClassNodeByClassName(String cName) throws Exception {
        String filePath = DependencyUtil.getFilePathByFullQualifiedName(cName);
        if (filePath != null) {
            if (!loadClassNode(filePath, f -> {
                String name = f.getName();
                return name.endsWith(".class") && name.substring(0, name.lastIndexOf(".class")).replace("/", ".").equals(cName);
            })) {
                throw new RuntimeException(String.format("can not load class `%s` from file `%s`", cName, filePath));
            }
            return fileNodesMap.get(filePath).get(cName);
        } else {
            return null;
        }
    }

    /**
     * 根据 filter 进行 ClassNode 加载
     *
     * @return 是否成功加载 ClassNode
     */
    private static boolean loadClassNode(String file, Predicate<ZipEntry> filter) throws Exception {
        JarFile jarFile = new JarFile(file);
        Map<Object, ClassNode> classNodeMap = fileNodesMap.computeIfAbsent(file, k -> newExpiringMap());
        // jar 中不包含 .class 文件则直接返回 true
        if (jarFile.stream().noneMatch(f -> f.getName().endsWith(".class"))) {
            return true;
        }
        AtomicBoolean loaded = new AtomicBoolean(false);
        jarFile.stream().filter(filter).filter(f -> !f.getName().contains("/test/")).forEach(entry -> {
            try {
                InputStream stream = jarFile.getInputStream(entry);
                ClassReader reader = new ClassReader(stream);
                ClassNode node = new ClassNode();
                reader.accept(node, flag);
                classNodeMap.put(node.name.replace("/", "."), node);
                loaded.set(true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        return loaded.get();
    }

    private static ConcurrentMap<Object, ClassNode> newExpiringMap() {
        return Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(5))
                .build(cName -> lazyLoadClassNodeByClassName(cName.toString())).asMap();
    }

    public static ClassNode getClassNodeByClassName(String cName) throws Exception {
        String file = DependencyUtil.getFilePathByFullQualifiedName(cName);
        if (file != null) {
            return getClassNodesByFileName(file).get(cName);
        } else {
            return null;
        }
    }

    public static Map<Object, ClassNode> getClassNodesByFileName(String file) throws Exception {
        Map<Object, ClassNode> nodeMap = fileNodesMap.computeIfAbsent(file, k -> newExpiringMap());
        if (nodeMap.isEmpty()) {
            // 无需考虑是否成功从 file 中获取到 ClassNodes
            loadAllClassNodeFromFile(file);
        }
        return nodeMap;
    }

    /**
     * 搜索与 cName 同 package 的 ClassNode
     *
     * @param nodeMap 类名 -> ClassNode 映射
     * @param cName   类名
     * @return ClassNode 列表
     */
    public static List<ClassNode> getClassNodesByPkgName(Map<Object, ClassNode> nodeMap, String cName) {
        String pkgName = cName.substring(0, cName.lastIndexOf("."));
        return nodeMap.values().stream().filter(node -> node.name.startsWith(pkgName)).toList();
    }

    public static Map<String, Map<Object, ClassNode>> getFileNodesMap() {
        return fileNodesMap;
    }

    public static void setFlag(int flag) {
        ClassNodeUtil.flag = flag;
    }

    public static int getFlag() {
        return flag;
    }
}
