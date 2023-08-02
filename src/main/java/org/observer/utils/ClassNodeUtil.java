package org.observer.utils;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import net.jodah.expiringmap.ExpirationPolicy;
import net.jodah.expiringmap.ExpiringMap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

public class ClassNodeUtil {
    private final static Map<String, Map<String, ClassNode>> fileNodesMap = new ConcurrentHashMap<>();

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
    }

    /**
     * 加载 File 内所有的 Class
     */
    public static boolean loadAllClassNodeFromFile(String file) throws Exception {
        return loadClassNode(file, f -> f.getName().endsWith(".class"));
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
        Map<String, ClassNode> classNodeMap = fileNodesMap.computeIfAbsent(file, k -> newExpiringMap());
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

    private static Map<String, ClassNode> newExpiringMap() {
        return ExpiringMap.builder()
                // 过期时间
                .expiration(5, TimeUnit.MINUTES)
                // 过期策略
                .expirationPolicy(ExpirationPolicy.ACCESSED)
                // 延迟加载
                .entryLoader(cName -> {
                    try {
                        return lazyLoadClassNodeByClassName(cName.toString());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .build();
    }

    public static ClassNode getClassNodeByClassName(String cName) throws Exception {
        String file = DependencyUtil.getFilePathByFullQualifiedName(cName);
        if (file != null) {
            return getClassNodesByFileName(file).get(cName);
        } else {
            return null;
        }
    }

    public static Map<String, ClassNode> getClassNodesByFileName(String file) throws Exception {
        Map<String, ClassNode> nodeMap = fileNodesMap.computeIfAbsent(file, k -> newExpiringMap());
        if (nodeMap.isEmpty()) {
            if (!loadAllClassNodeFromFile(file)) {
                throw new RuntimeException(String.format("can not load jar: %s", file));
            }
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
    public static List<ClassNode> getClassNodesByPkgName(Map<String, ClassNode> nodeMap, String cName) {
        String pkgName = cName.substring(0, cName.lastIndexOf("."));
        return nodeMap.values().stream().filter(node -> node.name.startsWith(pkgName)).toList();
    }

    public static Map<String, Map<String, ClassNode>> getFileNodesMap() {
        return fileNodesMap;
    }

    public static void setFlag(int flag) {
        ClassNodeUtil.flag = flag;
    }

    public static int getFlag() {
        return flag;
    }
}
