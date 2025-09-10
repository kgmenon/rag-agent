package com.rag.agent.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonUtilTest {
    
    @Test
    void testToJson() {
        Map<String, String> testMap = Map.of("key", "value");
        
        String json = JsonUtil.toJson(testMap);
        
        assertTrue(json.contains("\"key\":\"value\""));
    }
    
    @Test
    void testFromJson() {
        String json = "{\"key\":\"value\"}";
        
        @SuppressWarnings("unchecked")
        Map<String, String> result = JsonUtil.fromJson(json, Map.class);
        
        assertEquals("value", result.get("key"));
    }
    
    @Test
    void testParseJson() {
        String json = "{\"key\":\"value\"}";
        
        JsonNode node = JsonUtil.parseJson(json);
        
        assertEquals("value", node.get("key").asText());
    }
    
    @Test
    void testCreateObjectNode() {
        ObjectNode node = JsonUtil.createObjectNode();
        node.put("test", "value");
        
        assertEquals("value", node.get("test").asText());
    }
    
    @Test
    void testGetMapper() {
        assertNotNull(JsonUtil.getMapper());
    }
}