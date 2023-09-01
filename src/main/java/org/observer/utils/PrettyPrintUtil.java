package org.observer.utils;

import com.google.gson.Gson;

import java.io.FileOutputStream;
import java.util.*;

public class PrettyPrintUtil {
    // 过滤输出结果
    private final static Set<String> filters = new HashSet<>();
    private final static Gson gson = new Gson();

    public static void prettyPrint(Map map) {
        System.out.println("====== Pretty Print ======");
        Map result = filter(map);
        if (result.size() > 0) {
            System.out.println(gson.toJson(result));
        }
    }

    public static void saveToFile(Map map, String path) {
        try (FileOutputStream outputStream = new FileOutputStream(String.format("%s.filter", path), true)) {
            Map result = filter(map);
            if (result.size() > 0) {
                outputStream.write(gson.toJson(result).getBytes());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        try (FileOutputStream outputStream = new FileOutputStream(path, true)) {
            outputStream.write(gson.toJson(map).getBytes());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void addFilter(String name) {
        filters.add(name);
    }

    // 过滤出包含关键词的部分
    public static Map filter(Map map) {
        if (filters.size() > 0 && map.keySet().size() > 0) {
            Map results = new HashMap();
            map.entrySet().forEach(entry -> {
                String key = (String) ((Map.Entry) entry).getKey();
                List<Map> values = (List<Map>) ((Map.Entry) entry).getValue();
                if (values.size() > 0) {
                    if (filters.stream().anyMatch(key::contains)) {
                        results.put(key, values);
                    } else {
                        List next = values.stream().map(PrettyPrintUtil::filter).filter(m -> m.size() > 0).toList();
                        if (next.size() > 0) {
                            results.put(key, next);
                        }
                    }
                }
            });
            return results;
        } else {
            return map;
        }
    }
}
