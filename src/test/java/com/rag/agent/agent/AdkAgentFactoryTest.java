package com.rag.agent.agent;

import com.google.adk.agents.LlmAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class AdkAgentFactoryTest {
    
    @Test
    void testCreateAgent() {
        try (MockedStatic<System> systemMock = Mockito.mockStatic(System.class)) {
            systemMock.when(() -> System.getenv("LITELLM_ENDPOINT"))
                    .thenReturn("http://localhost:8080");
            
            LlmAgent agent = AdkAgentFactory.createAgent();
            
            assertNotNull(agent);
        }
    }
    
    @Test
    void testGenerateAnswer() {
        try (MockedStatic<System> systemMock = Mockito.mockStatic(System.class)) {
            systemMock.when(() -> System.getenv("LITELLM_ENDPOINT"))
                    .thenReturn("http://localhost:8080");
            
            List<String> passages = List.of("Test passage 1", "Test passage 2");
            String query = "Test query";
            
            assertDoesNotThrow(() -> {
                AdkAgentFactory.generateAnswer(passages, query);
            });
        }
    }
}