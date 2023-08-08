package org.observer.utils;

import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;


public class SearchUtil {
    public static Map<String, List> getBTCaller(String call) {
        Map<String, List> btTree = new ConcurrentHashMap<>();
        getBTCallerInner(call, new CopyOnWriteArrayList<>(), btTree, false);
        return btTree;
    }

    public static Map<String, List> getBTUpgradeCaller(String call) {
        Map<String, List> btTree = new ConcurrentHashMap<>();
        getBTCallerInner(call, new CopyOnWriteArrayList<>(), btTree, true);
        return btTree;
    }

    /**
     * 递归搜索所有 call 的 caller
     * 当 upgrade 至 父类/接口 方法时，添加 {x -> [super(x)]} 输出
     */
    private static void getBTCallerInner(String call, List<String> group, Map<String, List> root, boolean upgrade) {
        String[] callItems = call.split("#");
        Map<String, List> finalRoot = root;
        if (upgrade) {
            // upgrade 至 接口/父类 方法
            try {
                String finalName = HierarchyUtil.getIfaceMethodClzName(callItems[0], callItems[1], callItems[2]);
                if (!finalName.equals(callItems[0])) {
                    finalRoot = new ConcurrentHashMap<>();
                    List<Map> upList = root.computeIfAbsent(call, k -> new ArrayList<Map>());
                    upList.add(finalRoot);
                    callItems[0] = finalName;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        final String upgradeCall = String.join("#", callItems);
        if (!group.contains(upgradeCall)) {
            group.add(upgradeCall);
            List<Map> elements = finalRoot.computeIfAbsent(upgradeCall, k -> new ArrayList<Map>());
            DependencyUtil.getAllDependencies(callItems[0]).stream().map(f -> {
                try {
                    return getCallerFromFile(f, upgradeCall);
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
        /**
         * 清除 upgrade 过程中添加的空 map
         * eg:
         * {
         *   "com.opensymphony.webwork.views.xslt.XSLTResult#execute#(Lcom/opensymphony/xwork/ActionInvocation;)V#1": [
         *     {}
         *   ]
         * }
         */
        if (finalRoot != root && finalRoot.isEmpty()) {
            root.get(call).remove(finalRoot);
        }
    }

    /**
     * 从单 jar 包中搜索 call 的 caller
     */
    public static List<String> getCallerFromFile(String file, String call) throws Exception {
        String[] splits = call.split("#");
        String cName = splits[0];
        String fName = splits[1];
        String fDesc = splits[2];
        int fAccess = Integer.parseInt(splits[3]);
        boolean isPrivate = (fAccess & Opcodes.ACC_PRIVATE) != 0;
        boolean isProtected = (fAccess & Opcodes.ACC_PROTECTED) != 0;

        Map<Object, ClassNode> nodeMap = ClassNodeUtil.getClassNodesByFileName(file);
        List<String> callerList = new ArrayList<>();

        // 未加载 file jar 包 或 private/proteced 方法所在文件与 file 不匹配
        if (nodeMap.isEmpty() || ((isPrivate | isProtected) && !nodeMap.containsKey(cName))) {
            return callerList;
        }

        if (isPrivate) {
            // 从 cName 类中搜索 Caller
            callerList.addAll(getCallersFromClassNode(nodeMap.get(cName), cName, fName, fDesc));
        } else if (isProtected) {
            // 从 cName 同 pkgName 类中搜索 Caller
            ClassNodeUtil.getClassNodesByPkgName(nodeMap, cName).stream().map(classNode -> getCallersFromClassNode(classNode, cName, fName, fDesc)).forEach(callerList::addAll);
        } else {
            // 从所有 ClassNode 中进行搜索
            nodeMap.values().stream().map(classNode -> getCallersFromClassNode(classNode, cName, fName, fDesc)).forEach(callerList::addAll);
        }
        return callerList;
    }

    /**
     * 判断 classNode 中的 methods 是否包含对 cName.fName(fDesc) 的调用
     *
     * @param cName a.b.c
     * @param fName xxx
     * @param fDesc (Ljava/lang/String;)V or null
     */
    private static List<String> getCallersFromClassNode(ClassNode classNode, String cName, String fName, String fDesc) {
        return classNode.methods.stream().filter(methodNode -> !MethodUtil.isBlackMethod(methodNode) &&
                !classNode.name.replace("/", ".").equals(cName) || !methodNode.name.equals(fName) ||
                (!fDesc.equals("null") && !methodNode.desc.equals(fDesc))
        ).filter(methodNode -> {
            try {
                return MethodUtil.isCaller(methodNode, cName, fName, fDesc);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).map(methodNode -> String.format("%s#%s#%s#%s", classNode.name.replace("/", "."), methodNode.name, methodNode.desc, methodNode.access)).toList();
    }
}
