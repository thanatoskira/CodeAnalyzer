package org.observer.utils;

import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.observer.utils.StringUtil.x;


public class SearchUtil {
    private static final Set<String> allowPrefix = new HashSet<>();
    private static final Set<String> stopInterfaces = new HashSet<>();

    static {
        allowPrefix.addAll(Arrays.asList(
                "javax.sql.",
                "javax.naming."
        ));
    }

    public static Map<String, List> getBTCaller(String calee) {
        Map<String, List> btTree = new ConcurrentHashMap<>();
        getBTCallerInner(calee, new CopyOnWriteArrayList<>(), btTree, false);
        return btTree;
    }

    public static Map<String, List> getBTUpgradeCaller(String calee) {
        Map<String, List> btTree = new ConcurrentHashMap<>();
        getBTCallerInner(calee, new CopyOnWriteArrayList<>(), btTree, true);
        return btTree;
    }

    /**
     * 递归搜索所有 call 的 caller
     * 当 upgrade 至 父类/接口 方法时，添加 {x -> [super(x)]} 输出
     */
    private static void getBTCallerInner(String callee, List<String> group, Map<String, List> root, boolean upgrade) {
        String[] callItems = callee.split("#");
        Map<String, List> finalRoot = root;

        if (upgrade) {
            String owner = DependencyUtil.getCalleeOwnerInterfaceName(callee);
            if (owner != null && !owner.equals(callItems[0])) {
                if (stopInterfaces.contains(owner)) {
                    return;
                }
                try {
                    Class.forName(owner);
                    String method = String.format("%s#%s", owner, callItems[1]);
                    // 非白名单的 jdk 接口则不进行回溯
                    if (!callItems[2].equals("null") && allowPrefix.stream().noneMatch(method::startsWith)) {
                        stopInterfaces.add(owner);
                        root.computeIfAbsent(callee, k -> new ArrayList<Map>());
                        System.out.printf("[!] jdk interface stop: %s#%s, from %s%n", owner, callItems[1], callItems[0]);
                        return;
                    }
                } catch (ClassNotFoundException ignored) {
                }
                finalRoot = new ConcurrentHashMap<>();
                List upList = root.computeIfAbsent(callee, k -> new ArrayList<Map>());
                upList.add(finalRoot);
                callItems[0] = owner;
            }
        }
        String finalCall = String.join("#", callItems);
        if (!group.contains(finalCall)) {
            if (System.getProperty("log.print", "false").equals("true")) {
                System.out.println("Scan: " + finalCall + (!finalCall.equals(callee) ? " | From: " + callee : ""));
            }
            group.add(finalCall);
            List elements = finalRoot.computeIfAbsent(finalCall, k -> new ArrayList<Map>());
            DependencyUtil.getCallDependencies(finalCall).stream().map(f -> {
                try {
                    return getCallerFromFile(f, finalCall);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).forEach(pCallers -> pCallers.forEach(pCaller -> {
                try {
                    Map<String, List> element = new ConcurrentHashMap<>();
                    getBTCallerInner(pCaller, group, element, upgrade);
                    if (!element.isEmpty()) {
                        elements.add(element);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        /*
          清除 upgrade 过程中添加的空 map
          eg: {"com.opensymphony.webwork.views.xslt.XSLTResult#execute#(Lcom/opensymphony/xwork/ActionInvocation;)V#1": [{}]}
         */
        if (finalRoot != root && finalRoot.isEmpty()) {
            root.get(callee).remove(finalRoot);
        }
    }

    /**
     * 从单 jar 包中搜索 call 的 caller
     */
    public static List<String> getCallerFromFile(String file, String call) {
        String[] splits = call.split("#");
        String cName = splits[0];
        String fName = splits[1];
        String fDesc = splits[2];

        List<String> results = new ArrayList<>();
        // 跳过空参数函数回溯
        if (System.getProperty("params.empty.scan", "false").equals("false")) {
            if (!fDesc.equals("null") && fDesc.contains("()")) {
                return results;
            }
        }

        int fAccess = Integer.parseInt(splits[3]);
        boolean isPrivate = (fAccess & Opcodes.ACC_PRIVATE) != 0;
        boolean isProtected = (fAccess & Opcodes.ACC_PROTECTED) != 0;

        if (isPrivate) {
            // 从 cName 类中搜索 Caller
            results.addAll(getCallersFromClassNode(ClassNodeUtil.getClassNodeFromCache(cName), cName, fName, fDesc));
        } else if (isProtected) {
            // 从 cName 同 pkgName 类中搜索 Caller
            ClassNodeUtil.loadAllPkgClassNodeFromFile(file, ClassNodeUtil.getPkgName(cName)).stream().map(classNode -> getCallersFromClassNode(classNode, cName, fName, fDesc)).forEach(results::addAll);
        } else {
            // 从所有 ClassNode 中进行搜索
            ClassNodeUtil.loadAllClassNodeFromFile(file).values().stream().map(classNode -> getCallersFromClassNode(classNode, cName, fName, fDesc)).forEach(results::addAll);
        }

        return results;
    }

    /**
     * 判断 classNode 中的 methods 是否包含对 cName.fName(fDesc) 的调用
     *
     * @param cName a.b.c
     * @param fName xxx
     * @param fDesc (Ljava/lang/String;)V or null
     */
    private static List<String> getCallersFromClassNode(ClassNode classNode, String cName, String fName, String fDesc) {
        if (classNode == null || ClassNodeUtil.isInterface(classNode)) {
            return new ArrayList<>();
        }
        return classNode.methods.stream().filter(methodNode -> MethodUtil.isValidMethod(methodNode.name) &&
                !x(classNode.name).equals(cName) || !methodNode.name.equals(fName) ||
                (!fDesc.equals("null") && !methodNode.desc.equals(fDesc))
        ).filter(methodNode -> {
            try {
                return MethodUtil.isCaller(methodNode, cName, fName, fDesc);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).map(methodNode -> {
            /*
             1. 存在方法调用位于 lambda 中的情况：lambda$getCombinationOfhead$0，实际对应的方法为 getCombinationOfhead
             为避免存在重载函数的问题，这里直接忽略 desc 和 access
             2. 存在 seq_containsBI.this.checkMethodArgCount 方法调用，fName = access$1000
            */
            String newName = MethodUtil.lambdaTrim(methodNode.name);
            String name = x(classNode.name);
            if (!newName.equals(methodNode.name)) {
                if (System.getProperty("log.print", "false").equals("true")) {
                    System.out.printf("[!] is lambda method: %s.%s -> %s%n", classNode.name, methodNode.name, newName);
                }
                List<MethodNode> methods = MethodUtil.getMaxParamMatchMethods(newName, classNode);
                if (methods.size() > 1) {
                    return String.format("%s#%s#null#1", name, newName);
                } else if (methods.size() == 1) {
                    return String.format("%s#%s#%s#1", name, newName, methods.get(0).desc);
                } else {
                    // 存在 lambda$null$2 命名情况
                    return String.format("%s#%s#null#%s", name, newName, methodNode.access);
                }
            } else {
                return String.format("%s#%s#%s#%s", name, MethodUtil.lambdaTrim(methodNode.name), methodNode.desc, methodNode.access);
            }
        }).toList();
    }

    public static void addAllowPrefix(String prefix) {
        allowPrefix.add(prefix);
    }
}
