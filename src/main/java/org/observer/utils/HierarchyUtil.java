package org.observer.utils;

import com.github.benmanes.caffeine.cache.Caffeine;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.ClassNode;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class HierarchyUtil {
    // 缓存方法所对应的接口方法
    private final static Map<Object, Object> ifaceMethodCache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(5))
            .build().asMap();

    /**
     * 判断两个类是否存在公共的父类/接口
     *
     * @param leftName  callee.owner
     * @param rightName Call or upgradeCall
     */
    public static boolean hasCommonAncestor(String leftName, String rightName) {
        if (leftName.equals(rightName)) {
            return true;
        } else {
            try {
                Set<String> leftSet = getAllSupers(leftName);
                Set<String> rightSet = getAllSupers(rightName);
                leftSet.retainAll(rightSet);
                if (leftSet.isEmpty()) {
                    leftSet = getAllInterfaces(leftName);
                    rightSet = getAllInterfaces(rightName);
                    leftSet.retainAll(rightSet);
                    return !leftSet.isEmpty();
                } else {
                    return true;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 获取 cName#fName 对应的接口类名
     *
     * @param cName 方法所属类名 a.b.c
     * @param fName 方法名
     * @param fDesc 方法 Desc
     * @return 接口方法类名
     */
    public static String getIfaceMethodClzName(String cName, String fName, String fDesc) throws Exception {
        if (cName.equals("java.lang.Object")) {
            return null;
        }
        String key = String.format("%s#%s#%s", cName, fName, fDesc);
        String result = (String) ifaceMethodCache.get(key);
        if (result != null) {
            return result;
        }
        ClassNode node = ClassNodeUtil.getClassNodeByClassName(cName);
        if (node == null) {
            node = ClassNodeUtil.getClassNodeFromJDK(cName);
        }
        // 如果当前为接口类，则直接返回
        if (node == null || (node.access & Opcodes.ACC_INTERFACE) != 0) {
            ifaceMethodCache.put(key, cName);
            return cName;
        }

        // 寻找接口方法
        Set<String> ifaces = getAllInterfaces(cName);
        result = ifaces.stream().map(iface -> iface.replace("/", ".")).filter(iface -> {
            try {
                ClassNode ifaceNode = ClassNodeUtil.getClassNodeByClassName(iface);
                // 如果无法获取接口对应的 ClassNode 则跳过判断，fDesc 允许为 null 只判断方法名是否一致
                return ifaceNode != null && ifaceNode.methods.stream().anyMatch(method -> method.name.equals(fName) && (fDesc.equals("null") || method.desc.equals(fDesc)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).findFirst().orElse(null);

        // 寻找父类方法，父类优先
        if (result == null) {
            result = getAllSupers(cName).stream().filter(superClz -> {
                try {
                    ClassNode superNode = ClassNodeUtil.getClassNodeByClassName(superClz.replace("/", "."));
                    return superNode != null && superNode.methods.stream().anyMatch(method -> method.name.equals(fName) && method.desc.equals(fDesc));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).findFirst().orElse(cName);
        }
        ifaceMethodCache.put(key, result);
        return result;
    }

    /**
     * 获取类的所有接口，包含自身实现接口及父类实现接口
     *
     * @param cName 类名 a.b.c
     * @return 类的递归实现接口
     */
    public static Set<String> getAllInterfaces(String cName) throws Exception {
        Set<String> ifaces = new HashSet<>();
        if (!cName.equals("java.lang.Object")) {
            ClassNode node = ClassNodeUtil.getClassNodeByClassName(cName);
            if (node != null) {
                node.interfaces.forEach(iface -> {
                    try {
                        ifaces.add(iface);
                        ifaces.addAll(getAllInterfaces(iface.replace("/", ".")));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                String superName = node.superName.replace("/", ".");
                if (!superName.equals("java.lang.Object")) {
                    ifaces.addAll(getAllInterfaces(superName));
                }
            }
        }
        return ifaces;
    }


    /**
     * 获取所有父类，父类优先
     *
     * @param cName 类名 a.b.c
     * @return 父类列表
     */
    public static Set<String> getAllSupers(String cName) throws Exception {
        Set<String> supers = new LinkedHashSet<>();
        ClassNode node = ClassNodeUtil.getClassNodeByClassName(cName);
        if (node != null) {
            String superName = node.superName.replace("/", ".");
            if (!superName.equals("java.lang.Object")) {
                supers.addAll(getAllSupers(superName));
                supers.add(superName);
            }
        }
        return supers;
    }
}
