package com.rag.agent.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.rag.agent.util.JsonUtil;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.util.List;

public class EmbeddingsClient {
    private final BedrockRuntimeClient bedrockClient;
    private static final String TITAN_EMBEDDING_MODEL = "amazon.titan-embed-text-v2";
    
    public EmbeddingsClient(Region region) {
        this.bedrockClient = BedrockRuntimeClient.builder()
                .region(region)
                .build();
    }
    
    public float[] getEmbedding(String text) {
        try {
            String requestBody = JsonUtil.toJson(new EmbeddingRequest(text));
            
            InvokeModelRequest request = InvokeModelRequest.builder()
                    .modelId(TITAN_EMBEDDING_MODEL)
                    .body(SdkBytes.fromUtf8String(requestBody))
                    .build();
            
            InvokeModelResponse response = bedrockClient.invokeModel(request);
            String responseBody = response.body().asUtf8String();
            
            JsonNode responseJson = JsonUtil.parseJson(responseBody);
            JsonNode embedding = responseJson.get("embedding");
            
            if (embedding == null || !embedding.isArray()) {
                throw new RuntimeException("Invalid embedding response format");
            }
            
            float[] result = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                result[i] = (float) embedding.get(i).asDouble();
            }
            
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get embedding from Bedrock", e);
        }
    }
    
    public static class EmbeddingRequest {
        public final String inputText;
        
        public EmbeddingRequest(String inputText) {
            this.inputText = inputText;
        }
    }
}