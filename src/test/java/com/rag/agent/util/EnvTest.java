package com.rag.agent.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class EnvTest {
    
    @Test
    void testGet() {
        try (MockedStatic<System> systemMock = Mockito.mockStatic(System.class)) {
            systemMock.when(() -> System.getenv("TEST_KEY")).thenReturn("test_value");
            
            String result = Env.get("TEST_KEY");
            
            assertEquals("test_value", result);
        }
    }
    
    @Test
    void testGetOrDefault() {
        try (MockedStatic<System> systemMock = Mockito.mockStatic(System.class)) {
            systemMock.when(() -> System.getenv("TEST_KEY")).thenReturn(null);
            
            String result = Env.getOrDefault("TEST_KEY", "default_value");
            
            assertEquals("default_value", result);
        }
    }
    
    @Test
    void testRequireSuccess() {
        try (MockedStatic<System> systemMock = Mockito.mockStatic(System.class)) {
            systemMock.when(() -> System.getenv("TEST_KEY")).thenReturn("required_value");
            
            String result = Env.require("TEST_KEY");
            
            assertEquals("required_value", result);
        }
    }
    
    @Test
    void testRequireThrowsException() {
        try (MockedStatic<System> systemMock = Mockito.mockStatic(System.class)) {
            systemMock.when(() -> System.getenv("TEST_KEY")).thenReturn(null);
            
            assertThrows(IllegalStateException.class, () -> Env.require("TEST_KEY"));
        }
    }
}