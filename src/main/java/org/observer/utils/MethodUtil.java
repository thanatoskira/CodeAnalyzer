package org.observer.utils;

import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.InsnList;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MethodUtil {
    private final static Pattern lambdaPattern = Pattern.compile(".*\\$([^$]+)\\$\\d+$");

    public static String lambdaTrim(String name) {
        Matcher matcher = lambdaPattern.matcher(name);
        if (matcher.matches()) {
            return matcher.group(1);
        } else {
            return name;
        }
    }

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
                if (miNode.name.equals(fName) && (fDesc.equals("null") || miNode.desc.equals(fDesc))) {
                    try {
                        if (miNode.owner.replace("/", ".").equals(cName)) {
                            found = true;
                            break;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        return found;
    }

    public static boolean isValidMethod(MethodNode methodNode) {
        final List<String> blackMethods = Arrays.asList("main", "<clinit>");
        return !blackMethods.contains(methodNode.name);
    }
}
