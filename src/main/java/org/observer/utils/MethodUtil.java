package org.observer.utils;

import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.InsnList;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MethodUtil {
    private final static Pattern lambdaPattern = Pattern.compile(".*\\$([^$]+)\\$\\d+$");
    private final static Map<String, String> relationCache = new HashMap<>();

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
                            如下场景需要考虑：
                            1. a 继承 b，a 调用 b 中方法 x，当搜索 b.x 方法调用时，此时 miNode.owner 为 a
                         */
                        String owner = miNode.owner.replace("/", ".");
                        String key = String.format("%s#%s", owner, cName);
                        if (owner.equals(cName) || (relationCache.containsKey(key) && relationCache.get(key).equals("true"))) {
                            found = true;
                            break;
                        } else {
                            if (!relationCache.containsKey(key)) {
                                String status = String.valueOf(HierarchyUtil.isChildren(owner, cName));
                                relationCache.put(key, status);
                                if (status.equals("true")) {
                                    found = true;
                                    break;
                                } else {
                                    System.out.printf("[-] no match[%s]: %s !>> %s%n", fName, owner, cName);
                                }
                            }
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
