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
                        /*
                            使用 HierarchyUtil.hasCommonAncestor(miNode.owner.replace("/", "."), cName) 会导致两个不相关但存在相同父类或接口的类被关联在一起
                            一个比较常见的场景就是 <init> 方法，如:
                            搜索 cn.hutool.core.map.multi.CollectionValueMap#<init>()V 方法时会与 com.alibaba.fastjson.parser.JSONScanner#<init>()V 方法关联
                            原因在于两者均实现了 java.lang.Cloneable 接口且 方法名 和 Desc 均一致
                            解决方法为获取对应的接口方法类名，判断两者是否一致
                         */
                        String leftName = HierarchyUtil.getIfaceMethodClzName(miNode.owner.replace("/", "."), miNode.name, miNode.desc);
                        String rightName = HierarchyUtil.getIfaceMethodClzName(cName, fName, fDesc);
                        if (leftName.equals(rightName)) {
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

    public static boolean isBlackMethod(MethodNode methodNode) {
        return isBlackMethod(methodNode.name);
    }

    public static boolean isBlackMethod(String fName) {
        final List<String> blackMethods = Arrays.asList("main", "<clinit>");
        return blackMethods.contains(fName);
    }
}
