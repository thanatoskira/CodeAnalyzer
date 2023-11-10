package org.example;

public class ModuleB extends ModuleBase{
    @Override
    public void test1() {
        test1();
        System.out.println("ModuleB#test1()");
    }
}
