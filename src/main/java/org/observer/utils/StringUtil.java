package org.observer.utils;

public class StringUtil {
    public static String x(String str) {
        return str.replace("/", ".");
    }

    public static String y(String str) {
        return str.replace(".", "/");
    }
}
