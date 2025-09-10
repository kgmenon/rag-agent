package com.rag.agent.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RetrievalClientTest {
    
    @Test
    void testRetrievalResultCreation() {
        RetrievalClient.RetrievalResult result = new RetrievalClient.RetrievalResult(
                "id1", "test text", "sourceKey1", 0, 0.95
        );
        
        assertEquals("id1", result.getId());
        assertEquals("test text", result.getText());
        assertEquals("sourceKey1", result.getSourceKey());
        assertEquals(0, result.getPosition());
        assertEquals(0.95, result.getScore(), 0.001);
    }
    
    @Test
    void testRetrievalResultToCitation() {
        RetrievalClient.RetrievalResult result = new RetrievalClient.RetrievalResult(
                "id1", "test text", "sourceKey1", 0, 0.95
        );
        
        Map<String, Object> citation = result.toCitation();
        
        assertEquals("id1", citation.get("id"));
        assertEquals(0.95, citation.get("score"));
    }
    
    @Test
    void testRetrievalClientConstruction() {
        try (MockedStatic<System> systemMock = Mockito.mockStatic(System.class)) {
            systemMock.when(() -> System.getenv("OPENSEARCH_ENDPOINT"))
                    .thenReturn("https://test-endpoint.us-east-1.aoss.amazonaws.com");
            systemMock.when(() -> System.getenv("AWS_REGION"))
                    .thenReturn("us-east-1");
            
            assertDoesNotThrow(() -> new RetrievalClient());
        }
    }
}