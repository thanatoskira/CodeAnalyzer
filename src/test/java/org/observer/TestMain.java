package org.observer;

import com.google.gson.Gson;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import org.junit.Test;
import org.observer.utils.ClassNodeUtil;
import org.observer.utils.DependencyUtil;
import org.observer.utils.HierarchyUtil;
import org.observer.utils.SearchUtil;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class TestMain {
    private final static Gson gson = new Gson();

    static {
        try {
            SearchUtil.addAllowPrefix("org.example");
            DependencyUtil.resolve("out/artifacts/example_jar/example.jar");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // HierarchyUtil.getMatchClzName: 获取 call 所属的父类或接口类名
    @Test
    public void test1() {
        String call = "org.example.ModuleA#common#null#1";
        String[] callItems = call.split("#");
        assertEquals("org.example.Common", HierarchyUtil.getMatchSuperName(callItems[0], callItems[1], callItems[2], true));
        assertEquals("org.example.Common", HierarchyUtil.getMatchSuperName(callItems[0], callItems[1], callItems[2], false));

        call = "org.example.ModuleA#test1#null#1";
        callItems = call.split("#");
        assertEquals("org.example.ModuleBase", HierarchyUtil.getMatchSuperName(callItems[0], callItems[1], callItems[2]));
        assertNull(HierarchyUtil.getMatchSuperName(callItems[0], callItems[1], callItems[2], true));
    }

    // HierarchyUtil.isChildrenOrImpl: 判断 child 是否是 parent 的子类或实现类
    @Test
    public void test2() {
        assertTrue(HierarchyUtil.isChildrenOrImpl("org.example.ModuleAChildren", "org.example.ModuleA"));
        assertTrue(HierarchyUtil.isChildrenOrImpl("org.example.ModuleAChildren", "org.example.ModuleBase"));
        assertTrue(HierarchyUtil.isChildrenOrImpl("org.example.ModuleAChildren", "org.example.Common"));
        assertFalse(HierarchyUtil.isChildrenOrImpl("org.example.ModuleC", "org.example.Common"));
    }

    /*
        scan("java.io.File#renameTo#(Ljava/io/File;)Z#1")
        应只包含："org.example.ModuleA#test1"，而不包含 "org.example.ModuleB#test1"
     */
    @Test
    public void test3() {
        // {"java.io.File#renameTo#(Ljava/io/File;)Z#1":[{"org.example.ModuleA#test1#()V#1":[]}]}
        String call = "java.io.File#renameTo#(Ljava/io/File;)Z#1";
        String result = gson.toJson(SearchUtil.getBTUpgradeCaller(call));
        assertTrue(result.contains("org.example.ModuleA#test1#()V#1"));
        assertFalse(result.contains("org.example.ModuleB#test1#()V#1"));

        // {"java.io.FileOutputStream#write#null#1":[{"org.example.ModuleA#common#()V#1":[{"org.example.Common#common#()V#1":[]}]}]}
        call = "java.io.FileOutputStream#write#null#1";
        result = gson.toJson(SearchUtil.getBTUpgradeCaller(call));
        assertTrue(result.contains("org.example.ModuleA#common#()V#1"));
        assertFalse(result.contains("org.example.ModuleB#common#()V#1"));
    }


    /*
        scan("org.example.ModuleA.moduleATest1#null#1")
        应包含："org.example.ModuleAChildren#moduleAChildrenTest1"
     */
    @Test
    public void test4() {
        // {"org.example.ModuleA#moduleATest1#null#1":[{"org.example.ModuleAChildren#moduleAChildrenTest1#()V#1":[]}]}
        String call = "org.example.ModuleA#moduleATest1#null#1";
        String result = gson.toJson(SearchUtil.getBTUpgradeCaller(call));
        assertEquals("{\"org.example.ModuleA#moduleATest1#null#1\":[{\"org.example.ModuleAChildren#moduleAChildrenTest1#()V#1\":[]}]}", result);
    }

    @Test
    public void test5() {
        ClassNode childNode = ClassNodeUtil.getClassNodeFromCache("org.example.ModuleAChildren");
        ClassNode parentNode = ClassNodeUtil.getClassNodeFromCache("org.example.ModuleA");
        assertTrue(HierarchyUtil.isChildren(childNode, parentNode));
        parentNode = ClassNodeUtil.getClassNodeFromCache("org.example.Common");
        assertFalse(HierarchyUtil.isChildren(childNode, parentNode));
    }

    @Test
    public void test6() {
        // {"org.example.ModuleBase#test1#null#1":[{"org.example.ModuleA#test1#()V#1":[]},{"org.example.ModuleB#test1#()V#1":[]}]}
        String call = "org.example.ModuleBase#test1#null#1";
        String result = gson.toJson(SearchUtil.getBTUpgradeCaller(call));
        assertTrue(result.contains("org.example.ModuleA#test1#()V#1"));
        assertTrue(result.contains("org.example.ModuleB#test1#()V#1"));
    }

    @Test
    public void test7() {
        Matcher matcher = DependencyUtil.antFilePattern.matcher("com.github.rholder_guava-retrying-1.0.7.jar");
        assertTrue(matcher.matches());
        assertEquals(matcher.group(1), "com.github.rholder");
        assertEquals(matcher.group(2), "guava-retrying");

        matcher = DependencyUtil.antFilePattern.matcher("opensymphony_propertyset-1.3-21Nov03.jar");
        assertTrue(matcher.matches());
        assertEquals(matcher.group(1), "opensymphony");
        assertEquals(matcher.group(2), "propertyset");

        matcher = DependencyUtil.antFilePattern.matcher("xmlrpc_xmlrpc-2.0+xmlrpc61.1+sbfix.jar");
        assertTrue(matcher.matches());
        assertEquals(matcher.group(1), "xmlrpc");
        assertEquals(matcher.group(2), "xmlrpc");

        matcher = DependencyUtil.antFilePattern.matcher("org.eclipse.core_runtime-20070801.jar");
        assertTrue(matcher.matches());
        assertEquals(matcher.group(1), "org.eclipse.core");
        assertEquals(matcher.group(2), "runtime");
    }

    @Test
    public void test8() {
        assertNotNull(ClassNodeUtil.getClassNodeFromCache("org.example.ModuleA"));
        Set<String> results = ClassNodeUtil.loadAllPkgClassNodeFromFile("out/artifacts/example_jar/example.jar", ClassNodeUtil.getPkgName("org.example.util.FileUtil")).stream().map(c -> c.name).collect(Collectors.toSet());
        assertTrue(results.contains("org/example/util/FileUtil"));
        assertTrue(results.contains("org/example/util/StringUtil"));
        assertFalse(results.contains("org/example/Common"));
    }
    /*
        java.io.OutputStream !>> org.apache.catalina.connector.CoyoteOutputStream
     */
}
