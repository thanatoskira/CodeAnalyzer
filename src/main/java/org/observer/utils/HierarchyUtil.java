package org.observer.utils;

import jdk.internal.org.objectweb.asm.tree.ClassNode;

import java.util.Map;
import java.util.Objects;

public class HierarchyUtil {
    public static String getMatchClzName(String cName, String fName, String fDesc, Map<Object, ClassNode> loadedClassNodeMap) {
        cName = cName.replace("/", ".");
        ClassNode current = loadedClassNodeMap.get(cName);
        if (current == null) {
            try {
                Class.forName(cName);
                current = ClassNodeUtil.getSingleClassNodeFromJDK(cName);
            } catch (ClassNotFoundException e) {
                String filePath = DependencyUtil.getFilePathByFullQualifiedName(cName);
                if (filePath != null) {
                    loadedClassNodeMap.putAll(ClassNodeUtil.loadAllClassNodeFromFile(filePath));
                    current = loadedClassNodeMap.get(cName);
                }
            }
        }
        if (current == null) {
            return null;
        }
        String name = current.interfaces.stream().map(iname -> getMatchClzName(iname, fName, fDesc, loadedClassNodeMap)).filter(Objects::nonNull).findFirst().orElse(null);
        if (name == null) {
            String superName = current.superName;
            if (!superName.equals("java/lang/Object")) {
                name = getMatchClzName(superName, fName, fDesc, loadedClassNodeMap);
            }
        }
        if (name == null) {
            if (current.methods.stream().anyMatch(method -> method.name.equals(fName) && (fDesc.equals("null") || method.desc.equals(fDesc)))) {
                return cName;
            }
        }
        return name;
    }

    public static boolean isChildren(String child, String parent) {
        ClassNode childNode = ClassNodeUtil.getSingleClassNodeByClassName(child);
        if (childNode == null) {
            return false;
        }
        String superName = childNode.superName.replace("/", ".");
        if (superName == null || superName.equals("java.lang.Object")) {
            return false;
        }
        return superName.equals(parent) || isChildren(superName, parent);
    }
}
