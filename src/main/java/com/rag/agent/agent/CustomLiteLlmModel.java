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
import okhttp3.*;

import java.io.IOException;
import java.time.Duration;

public class CustomLiteLlmModel extends BaseLlm {
    private final OkHttpClient httpClient;
    private final String litellmEndpoint;
    
    public CustomLiteLlmModel() {
        super("anthropic.claude-3-sonnet-20240229-v1:0");
        this.litellmEndpoint = Env.require("LITELLM_ENDPOINT");
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofMinutes(5))
                .writeTimeout(Duration.ofSeconds(30))
                .build();
    }
    
    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest request, boolean streaming) {
        return Flowable.fromCallable(() -> {
            try {
                ObjectNode requestBody = createOpenAiRequest(request);
                
                RequestBody body = RequestBody.create(
                    JsonUtil.toJson(requestBody),
                    MediaType.get("application/json")
                );
                
                Request httpRequest = new Request.Builder()
                        .url(litellmEndpoint + "/chat/completions")
                        .post(body)
                        .addHeader("Content-Type", "application/json")
                        .build();
                
                try (Response response = httpClient.newCall(httpRequest).execute()) {
                    if (!response.isSuccessful()) {
                        throw new RuntimeException("LiteLLM request failed: " + response.code() + " " + response.message());
                    }
                    
                    String responseBody = response.body().string();
                    JsonNode responseJson = JsonUtil.parseJson(responseBody);
                    
                    return parseOpenAiResponse(responseJson);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to call LiteLLM proxy", e);
            }
        });
    }
    
    @Override
    public BaseLlmConnection connect(LlmRequest request) {
        throw new UnsupportedOperationException("Connection mode not supported");
    }
    
    private ObjectNode createOpenAiRequest(LlmRequest request) {
        ObjectNode requestBody = JsonUtil.createObjectNode();
        requestBody.put("model", "anthropic.claude-3-sonnet-20240229-v1:0");
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
            
            messageObj.put("content", contentText.toString());
            messages.add(messageObj);
        }
        
        return requestBody;
    }
    
    private LlmResponse parseOpenAiResponse(JsonNode responseJson) {
        JsonNode choices = responseJson.get("choices");
        if (choices == null || choices.size() == 0) {
            throw new RuntimeException("No choices in LiteLLM response");
        }
        
        JsonNode firstChoice = choices.get(0);
        JsonNode message = firstChoice.get("message");
        String contentText = message.get("content").asText();
        
        // Create Content with Part containing the text
        Part textPart = Part.builder().text(contentText).build();
        Content content = Content.builder()
                .parts(java.util.List.of(textPart))
                .build();
        
        return LlmResponse.builder()
                .content(content)
                .build();
    }
}