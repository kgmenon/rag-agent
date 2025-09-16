package com.rag.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.rag.agent.util.Env;
import com.rag.agent.util.JsonUtil;
import io.reactivex.rxjava3.core.Flowable;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

public class CustomLiteLlmModel extends BaseLlm {
    private final BedrockRuntimeClient bedrockClient;
    private final String region;
    private final String modelId;
    
    public CustomLiteLlmModel() {
        super("anthropic.claude-3-sonnet-20240229-v1:0");
        this.region = Env.get("AWS_REGION", "ap-southeast-2");
        this.modelId = "anthropic.claude-3-sonnet-20240229-v1:0";
        this.bedrockClient = BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
    
    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest request, boolean streaming) {
        return Flowable.fromCallable(() -> {
            try {
                // Create Bedrock request body for Claude 3
                ObjectNode requestBody = createBedrockRequest(request);
                String requestBodyJson = JsonUtil.toJson(requestBody);
                
                System.out.println("Direct Bedrock request for model: " + modelId);
                
                // Create Bedrock InvokeModel request
                InvokeModelRequest invokeRequest = InvokeModelRequest.builder()
                        .modelId(modelId)
                        .body(SdkBytes.fromUtf8String(requestBodyJson))
                        .contentType("application/json")
                        .accept("application/json")
                        .build();
                
                // Call Bedrock directly
                InvokeModelResponse response = bedrockClient.invokeModel(invokeRequest);
                
                // Parse Bedrock response
                String responseBodyJson = response.body().asUtf8String();
                System.out.println("Bedrock response body: " + responseBodyJson);
                JsonNode responseJson = JsonUtil.parseJson(responseBodyJson);
                System.out.println("Parsed JSON: " + responseJson.toString());
                
                return parseBedrockResponse(responseJson);
                
            } catch (Exception e) {
                System.err.println("Error calling Bedrock directly: " + e.getMessage());
                throw new RuntimeException("Failed to call Bedrock directly", e);
            }
        });
    }
    
    @Override
    public BaseLlmConnection connect(LlmRequest request) {
        throw new UnsupportedOperationException("Connection mode not supported");
    }
    
    private ObjectNode createBedrockRequest(LlmRequest request) {
        ObjectNode requestBody = JsonUtil.createObjectNode();
        requestBody.put("max_tokens", 4096);
        requestBody.put("temperature", 0.1);
        
        ArrayNode messages = requestBody.putArray("messages");
        
        for (Content content : request.contents()) {
            ObjectNode messageObj = JsonUtil.createObjectNode();
            messageObj.put("role", "user");
            
            StringBuilder contentText = new StringBuilder();
            if (content.parts().isPresent()) {
                for (Part part : content.parts().get()) {
                    if (part.text() != null && !part.text().isEmpty()) {
                        contentText.append(part.text());
                    }
                }
            }
            
            // For Claude 3, content should be an array of content blocks
            ArrayNode contentArray = JsonUtil.createArrayNode();
            ObjectNode textBlock = JsonUtil.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", contentText.toString());
            contentArray.add(textBlock);
            
            messageObj.set("content", contentArray);
            messages.add(messageObj);
        }
        
        // Add anthropic version header for Claude 3
        requestBody.put("anthropic_version", "bedrock-2023-05-31");
        
        return requestBody;
    }
    
    private LlmResponse parseBedrockResponse(JsonNode responseJson) {
        System.out.println("Parsing Bedrock response JSON structure:");
        System.out.println("Available fields: " + responseJson.fieldNames());
        
        String contentText = null;
        
        // Try different possible response formats for Claude 3 on Bedrock
        
        // Format 1: Direct content array (Claude 3 Messages API format)
        JsonNode content = responseJson.get("content");
        if (content != null && content.isArray() && content.size() > 0) {
            JsonNode firstContent = content.get(0);
            if (firstContent.has("text")) {
                contentText = firstContent.get("text").asText();
                System.out.println("Found content in Messages API format");
            }
        }
        
        // Format 2: Completion field (legacy format)
        if (contentText == null) {
            JsonNode completion = responseJson.get("completion");
            if (completion != null) {
                contentText = completion.asText();
                System.out.println("Found content in completion format");
            }
        }
        
        // Format 3: Text field (simple format)
        if (contentText == null) {
            JsonNode text = responseJson.get("text");
            if (text != null) {
                contentText = text.asText();
                System.out.println("Found content in text format");
            }
        }
        
        // Format 4: Output field (some Bedrock models use this)
        if (contentText == null) {
            JsonNode output = responseJson.get("output");
            if (output != null) {
                contentText = output.asText();
                System.out.println("Found content in output format");
            }
        }
        
        if (contentText == null || contentText.trim().isEmpty()) {
            System.err.println("No readable content found in response: " + responseJson.toString());
            throw new RuntimeException("No readable content found in Bedrock response");
        }
        
        System.out.println("Extracted content: " + contentText.substring(0, Math.min(100, contentText.length())) + "...");
        
        // Create Content with Part containing the text
        Part textPart = Part.builder().text(contentText).build();
        Content responseContent = Content.builder()
                .parts(java.util.List.of(textPart))
                .build();
        
        return LlmResponse.builder()
                .content(responseContent)
                .build();
    }
}