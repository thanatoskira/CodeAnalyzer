package org.observer.utils;

import com.google.gson.Gson;

import java.util.Map;

public class PrettyPrintUtil {
    public static void print(Map map){
        System.out.println("====== Pretty Print ======");
        System.out.println(new Gson().toJson(map));
    }
}
