package org.example;

public abstract class ModuleBase implements Common {
    public void test1() {
        System.out.println("ModuleBase#test1()");
    }

    @Override
    public void common() {
        System.out.println("ModuleBase#common()");
    }

    @Override
    public void common1() {
        System.out.println("ModuleBase#common1()");
    }
}
