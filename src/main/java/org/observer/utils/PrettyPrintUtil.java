package org.observer.utils;

import com.google.gson.Gson;

import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PrettyPrintUtil {
    // 过滤输出结果
    private final static Set<String> filters = new HashSet<>();
    private final static Gson gson = new Gson();

    public static void prettyPrint(Map map) {
        System.out.println("====== Pretty Print ======");
        System.out.println(gson.toJson(filter(map)));
    }

    public static void saveToFile(Map map, String path) {
        try (FileOutputStream outputStream = new FileOutputStream(path, true)) {
            outputStream.write(gson.toJson(filter(map)).getBytes());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void addFilter(String name) {
        filters.add(name);
    }

    // 只进行一层过滤
    private static Map filter(Map map) {
        System.out.println("[+] Filter: " + filters);
        if (filters.size() > 0) {
            Map result = new ConcurrentHashMap();
            map.entrySet().forEach(entry -> {
                String key = (String) ((Map.Entry) entry).getKey();
                List<Map> value = (List<Map>) ((Map.Entry) entry).getValue();
                result.put(key, value.stream().filter(l -> filters.stream().anyMatch(filter -> gson.toJson(l).contains(filter))).toList());
            });
            return result;
        } else {
            return map;
        }
    }
}
