package com.rag.agent.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmbeddingsClientTest {
    
    @Mock
    private BedrockRuntimeClient mockBedrockClient;
    
    @Test
    void testGetEmbedding() {
        String mockResponseBody = "{\"embedding\": [0.1, 0.2, 0.3]}";
        InvokeModelResponse mockResponse = InvokeModelResponse.builder()
                .body(SdkBytes.fromUtf8String(mockResponseBody))
                .build();
        
        when(mockBedrockClient.invokeModel(any(InvokeModelRequest.class)))
                .thenReturn(mockResponse);
        
        try (MockedStatic<BedrockRuntimeClient> clientMock = Mockito.mockStatic(BedrockRuntimeClient.class)) {
            clientMock.when(() -> BedrockRuntimeClient.builder()).thenReturn(
                Mockito.mock(software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClientBuilder.class));
            
            // Mock the builder chain
            var builderMock = Mockito.mock(software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClientBuilder.class);
            when(builderMock.region(any(Region.class))).thenReturn(builderMock);
            when(builderMock.build()).thenReturn(mockBedrockClient);
            clientMock.when(BedrockRuntimeClient::builder).thenReturn(builderMock);
            
            EmbeddingsClient client = new EmbeddingsClient(Region.US_EAST_1);
            
            float[] result = client.getEmbedding("test text");
            
            assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, result, 0.001f);
        }
    }
    
    @Test
    void testEmbeddingRequest() {
        EmbeddingsClient.EmbeddingRequest request = new EmbeddingsClient.EmbeddingRequest("test");
        
        assertEquals("test", request.inputText);
    }
}