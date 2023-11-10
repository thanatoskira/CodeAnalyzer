package org.observer.utils;

import jdk.internal.org.objectweb.asm.tree.ClassNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.observer.utils.StringUtil.x;

public class HierarchyUtil {
    public static String getMatchSuperName(String cName, String fName, String fDesc) {
        return getMatchSuperName(cName, fName, fDesc, false);
    }

    public static String getMatchSuperName(String cName, String fName, String fDesc, boolean shouldInterface) {
        Map<Object, ClassNode> loadedClassNodeMap = new HashMap<>();
        ClassNode classNode = getMatchSuperClassNode(cName, fName, fDesc, loadedClassNodeMap);
        return classNode == null ? null : shouldInterface ? ClassNodeUtil.isInterface(classNode) ? x(classNode.name) : null : x(classNode.name);
    }

    // 获取 call 所属的父类或接口类名
    private static ClassNode getMatchSuperClassNode(String cName, String fName, String fDesc, Map<Object, ClassNode> loadedClassNodeMap) {
        ClassNode classNode = loadedClassNodeMap.getOrDefault(cName, ClassNodeUtil.getClassNodeFromCache(x(cName)));
        ClassNode parent = null;
        if (classNode != null) {
            loadedClassNodeMap.putIfAbsent(cName, classNode);
            parent = classNode.interfaces.stream().map(interfaceName -> getMatchSuperClassNode(interfaceName, fName, fDesc, loadedClassNodeMap)).filter(Objects::nonNull).findFirst().orElse(null);
            if (parent == null) {
                String superName = classNode.superName;
                if (!superName.equals("java/lang/Object")) {
                    parent = getMatchSuperClassNode(superName, fName, fDesc, loadedClassNodeMap);
                }
            }
            if (parent == null) {
                if (classNode.methods.stream().anyMatch(method -> method.name.equals(fName) && (fDesc.equals("null") || method.desc.equals(fDesc)))) {
                    return classNode;
                }
            }
        }
        return parent;
    }

    // 判断 child 是否是 parent 的子类或实现类
    public static boolean isChildrenOrImpl(String child, String parent) {
        assert !child.contains("/") && !parent.contains("/");
        if (child.equals(parent)) {
            return true;
        }
        ClassNode childNode = ClassNodeUtil.getClassNodeFromCache(child);
        if (childNode == null) {
            return false;
        }
        if (childNode.interfaces.stream().anyMatch(name -> isChildrenOrImpl(x(name), parent))) {
            return true;
        }
        String superName = childNode.superName;
        if (superName == null || superName.equals("java/lang/Object")) {
            return false;
        }
        return isChildrenOrImpl(x(superName), parent);
    }

    public static boolean isChildren(ClassNode child, ClassNode parent) {
        if (child == null || child.superName == null || child.superName.equals("java/lang/Object")) {
            return false;
        }
        if (child.superName.equals(parent.name)) {
            return true;
        }
        return isChildren(ClassNodeUtil.getClassNodeFromCache(x(child.superName)), parent);
    }
}
