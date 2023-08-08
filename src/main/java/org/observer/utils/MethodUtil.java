package org.observer.utils;

import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.InsnList;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;
import java.util.List;

public class MethodUtil {
    /**
     * 判断方法中是否存在 cName#fName 方法调用
     */
    public static boolean isCaller(MethodNode methodNode, String cName, String fName, String fDesc) {
        InsnList ins = methodNode.instructions;
        boolean found = false;
        for (int i = 0; i < ins.size(); i++) {
            AbstractInsnNode inode = ins.get(i);
            if (inode.getType() == AbstractInsnNode.METHOD_INSN) {
                MethodInsnNode miNode = (MethodInsnNode) inode;
                if (miNode.name.equals(fName) && (fDesc.equals("null") || miNode.desc.equals(fDesc)) && HierarchyUtil.hasCommonAncestor(miNode.owner.replace("/", "."), cName)) {
                    found = true;
                    break;
                }
            }
        }
        return found;
    }

    public static boolean isBlackMethod(MethodNode methodNode) {
        final List<String> blackMethods = Arrays.asList("main", "<clinit>");
        return blackMethods.contains(methodNode.name);
    }
}
