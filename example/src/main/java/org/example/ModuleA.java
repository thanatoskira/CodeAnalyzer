package org.example;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class ModuleA extends ModuleBase {
    @Override
    public void test1() {
        super.test1();
        new File("/tmp/111.txt").renameTo(new File("/tmp/222.txt"));
    }

    @Override
    public void common() {
        System.out.println("ModuleA#common()");
        try {
            new FileOutputStream("/tmp/111.txt").write("111".getBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void moduleATest1() {
        System.out.println("ModuleA#test1()");
    }
}
