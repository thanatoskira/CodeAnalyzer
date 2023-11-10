package org.observer.utils;

import jdk.internal.org.objectweb.asm.tree.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.observer.utils.StringUtil.x;

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
                        String owner = x(miNode.owner);
                        String key = String.format("%s#%s", owner, cName);
                        if (owner.equals(cName) || (relationCache.containsKey(key) && relationCache.get(key).equals("true"))) {
                            found = true;
                            break;
                        } else {
                            /*
                                1. 如果 cName 为接口，则 miNode.owner 应同为接口
                                2. 如果 cName 为类，则 miNode.owner 应为类，而非接口
                                3. 只有 miNode.owner 和 cName 同为类时，才进行继承判断
                             */
                            if (!relationCache.containsKey(key) && MethodUtil.isValidMethod(fName)) {
                                ClassNode ownerClassNode = ClassNodeUtil.getClassNodeFromCache(owner);
                                ClassNode parentClassNode = ClassNodeUtil.getClassNodeFromCache(cName);
                                if (ownerClassNode != null && parentClassNode != null && !ClassNodeUtil.isInterface(ownerClassNode) && !ClassNodeUtil.isInterface(parentClassNode)) {
                                    boolean isChildren = HierarchyUtil.isChildren(ownerClassNode, parentClassNode);
                                    relationCache.put(key, String.valueOf(isChildren));
                                    if (isChildren) {
                                        found = true;
                                        break;
                                    } else {
                                        if (System.getProperty("log.print", "false").equals("true")) {
                                            System.out.printf("[-] no match[%s|%s]: %s !>> %s%n", fName, fDesc.equals("null"), owner, cName);
                                        }
                                    }
                                } else {
                                    relationCache.put(key, "false");
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

    public static boolean isValidMethod(String methodName) {
        final List<String> blackMethods = Arrays.asList("main", "<clinit>");
        return !blackMethods.contains(methodName);
    }

    // 获取 desc 参数格式
    public static int getMethodDescSize(String desc) {
        int start = desc.indexOf("(");
        int end = desc.indexOf(")", start);
        return desc.substring(start + 1, end).split(";").length;
    }

    // 获取匹配且参数数量最长的方法，如存在多个参数长度相同的方法，则一起返回
    public static List<MethodNode> getMaxParamMatchMethods(String methodName, ClassNode classNode) {
        AtomicInteger maxSize = new AtomicInteger(-1);
        List<MethodNode> results = new ArrayList<>();
        classNode.methods.stream().filter(m -> m.name.equals(methodName)).forEach(m -> {
            int size = getMethodDescSize(m.desc);
            if (size > maxSize.get()) {
                maxSize.set(size);
                results.clear();
                results.add(m);
            } else if (size == maxSize.get()) {
                results.add(m);
            }
        });
        return results;
    }
}
