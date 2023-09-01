package org.observer.utils;

import com.google.gson.Gson;

import java.io.FileOutputStream;
import java.util.Map;

public class PrettyPrintUtil {
    public static void prettyPrint(Map map) {
        System.out.println("====== Pretty Print ======");
        System.out.println(new Gson().toJson(map));
    }

    public static void saveToFile(Map map, String path) {
        try (FileOutputStream outputStream = new FileOutputStream(path, true)) {
            outputStream.write(new Gson().toJson(map).getBytes());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
