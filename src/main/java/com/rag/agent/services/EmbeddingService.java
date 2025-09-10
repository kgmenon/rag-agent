package com.rag.agent.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class EmbeddingService {
    private final BedrockRuntimeClient bedrockClient;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String liteLLMProxyUrl;
    
    // Using Amazon Titan Embeddings model (latest version available in Sydney)
    private static final String EMBEDDING_MODEL_ID = "amazon.titan-embed-text-v2:0";
    
    public EmbeddingService() {
        System.out.println("Initializing EmbeddingService for ap-southeast-2 Sydney region");
        
        // Initialize AWS Bedrock client for Titan embeddings in Sydney region
        this.bedrockClient = BedrockRuntimeClient.builder()
            .region(Region.AP_SOUTHEAST_2) // Using Sydney region for this POC
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
            
        // Initialize HTTP client for LiteLLM proxy communication
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
            
        // LiteLLM proxy URL - will be configured to proxy to Bedrock Claude
        this.liteLLMProxyUrl = System.getenv("LITELLM_PROXY_URL");
        if (liteLLMProxyUrl == null) {
            System.out.println("Warning: LITELLM_PROXY_URL not set, will use direct Bedrock");
        }
        
        this.objectMapper = new ObjectMapper();
        
        System.out.println("EmbeddingService initialized:");
        System.out.println("- Region: ap-southeast-2 Sydney");
        System.out.println("- Titan Embeddings: " + EMBEDDING_MODEL_ID);
        System.out.println("- Claude via Google ADK → LiteLLM Proxy → Bedrock Claude");
        System.out.println("- LiteLLM Proxy URL: " + (liteLLMProxyUrl != null ? liteLLMProxyUrl : "Not configured"));
    }
    
    public float[] generateEmbedding(String text) {
        try {
            // Prepare the request payload for Titan Embeddings
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("inputText", text);
            
            String jsonPayload = objectMapper.writeValueAsString(requestBody);
            
            InvokeModelRequest request = InvokeModelRequest.builder()
                .modelId(EMBEDDING_MODEL_ID)
                .body(SdkBytes.fromUtf8String(jsonPayload))
                .contentType("application/json")
                .accept("application/json")
                .build();
            
            InvokeModelResponse response = bedrockClient.invokeModel(request);
            String responseBody = response.body().asUtf8String();
            
            // Parse the response
            JsonNode jsonResponse = objectMapper.readTree(responseBody);
            JsonNode embeddingArray = jsonResponse.get("embedding");
            
            if (embeddingArray == null || !embeddingArray.isArray()) {
                throw new RuntimeException("Invalid embedding response format");
            }
            
            float[] embedding = new float[embeddingArray.size()];
            for (int i = 0; i < embeddingArray.size(); i++) {
                embedding[i] = (float) embeddingArray.get(i).asDouble();
            }
            
            return embedding;
            
        } catch (Exception e) {
            System.err.println("Error generating embedding: " + e.getMessage());
            throw new RuntimeException("Failed to generate embedding", e);
        }
    }
    
    public String generateAnswer(String question, String context) {
        // Try Google ADK via LiteLLM proxy first, fallback to direct Bedrock
        if (liteLLMProxyUrl != null) {
            return generateAnswerViaGoogleADK(question, context);
        } else {
            return generateAnswerDirectBedrock(question, context);
        }
    }
    
    private String generateAnswerViaGoogleADK(String question, String context) {
        try {
            System.out.println("Generating answer via Google ADK → LiteLLM Proxy → Bedrock Claude");
            
            String systemPrompt = "You are a helpful AI assistant that answers questions based on provided context. " +
                "If the context doesn't contain enough information to answer the question, please say so clearly.";
            
            String userPrompt = String.format(
                "Context:\n%s\n\nQuestion: %s\n\nPlease provide a comprehensive answer based on the context above.",
                context, question
            );
            
            // Create request body for LiteLLM proxy (OpenAI-compatible API)
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "claude-3.5-sonnet-20241022"); // Model via LiteLLM
            requestBody.put("messages", new Object[]{
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            });
            requestBody.put("max_tokens", 1000);
            requestBody.put("temperature", 0.1);
            requestBody.put("top_p", 0.9);
            
            String jsonPayload = objectMapper.writeValueAsString(requestBody);
            
            RequestBody body = RequestBody.create(
                jsonPayload, 
                MediaType.parse("application/json")
            );
            
            Request request = new Request.Builder()
                .url(liteLLMProxyUrl + "/v1/chat/completions")
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    System.err.println("LiteLLM proxy request failed: " + response.code());
                    return generateAnswerDirectBedrock(question, context);
                }
                
                String responseBody = response.body().string();
                JsonNode jsonResponse = objectMapper.readTree(responseBody);
                String answer = jsonResponse
                    .path("choices")
                    .get(0)
                    .path("message")
                    .path("content")
                    .asText();
                
                System.out.println("Successfully generated answer via Google ADK → LiteLLM → Bedrock");
                return answer != null ? answer.trim() : "I apologize, but I couldn't generate a response.";
            }
            
        } catch (Exception e) {
            System.err.println("Error generating answer via Google ADK/LiteLLM: " + e.getMessage());
            return generateAnswerDirectBedrock(question, context);
        }
    }
    
    private String generateAnswerDirectBedrock(String question, String context) {
        try {
            System.out.println("Generating answer via direct Bedrock Claude (fallback)");
            String prompt = buildRAGPrompt(question, context);
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("prompt", prompt);
            requestBody.put("max_tokens_to_sample", 1000);
            requestBody.put("temperature", 0.1);
            requestBody.put("top_p", 0.9);
            
            String jsonPayload = objectMapper.writeValueAsString(requestBody);
            
            InvokeModelRequest request = InvokeModelRequest.builder()
                .modelId("anthropic.claude-sonnet-4-20250514-v1:0")
                .body(SdkBytes.fromUtf8String(jsonPayload))
                .contentType("application/json")
                .accept("application/json")
                .build();
            
            InvokeModelResponse response = bedrockClient.invokeModel(request);
            String responseBody = response.body().asUtf8String();
            
            JsonNode jsonResponse = objectMapper.readTree(responseBody);
            return jsonResponse.get("completion").asText().trim();
            
        } catch (Exception e) {
            System.err.println("Error in direct Bedrock answer generation: " + e.getMessage());
            return "I apologize, but I encountered an error while processing your question. Please try again.";
        }
    }
    
    private String buildRAGPrompt(String question, String context) {
        return String.format(
            "\\n\\nHuman: Based on the following context, please answer the question. " +
            "If the context doesn't contain enough information to answer the question, " +
            "please say so clearly.\\n\\n" +
            "Context:\\n%s\\n\\n" +
            "Question: %s\\n\\n" +
            "Assistant: Based on the provided context, I can answer your question:\\n\\n",
            context, question
        );
    }
    
    public void close() {
        if (bedrockClient != null) {
            bedrockClient.close();
        }
        // HTTP client resources are automatically cleaned up
    }
}