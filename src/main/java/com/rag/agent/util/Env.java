package com.rag.agent.util;

public class Env {
    public static String get(String key) {
        return System.getenv(key);
    }
    
    public static String get(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null ? value : defaultValue;
    }
    
    public static String require(String key) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            throw new RuntimeException("Required environment variable '" + key + "' is not set");
        }
        return value;
    }
}