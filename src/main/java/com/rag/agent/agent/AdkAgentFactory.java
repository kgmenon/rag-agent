package com.rag.agent.agent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class AdkAgentFactory {
    private static final String AGENT_INSTRUCTION = 
        "Answer using only the provided excerpts. If the answer cannot be found in the provided excerpts, say you do not know.";
    
    public static LlmAgent createAgent() {
        CustomLiteLlmModel llmModel = new CustomLiteLlmModel();
        
        return LlmAgent.builder()
                .model(llmModel)
                .instruction(AGENT_INSTRUCTION)
                .build();
    }
    
    public static String generateAnswer(List<String> passages, String query) {
        // Note: We're using the model directly instead of the agent wrapper for simplicity
        
        StringBuilder context = new StringBuilder();
        context.append("Context excerpts:\n\n");
        
        for (int i = 0; i < passages.size(); i++) {
            context.append("Excerpt ").append(i + 1).append(":\n");
            context.append(passages.get(i)).append("\n\n");
        }
        
        context.append("Question: ").append(query);
        
        try {
            // Create the user input as Content with text Part
            Part textPart = Part.builder().text(context.toString()).build();
            Content userContent = Content.builder()
                    .parts(List.of(textPart))
                    .build();
            
            // Create LlmRequest with the user content
            LlmRequest request = LlmRequest.builder()
                    .contents(List.of(userContent))
                    .build();
            
            // Use the model directly since agent.generate() may not be available
            CustomLiteLlmModel model = new CustomLiteLlmModel();
            Flowable<LlmResponse> responseFlow = model.generateContent(request, false);
            
            // Block and get the first response (timeout after 30 seconds)
            LlmResponse response = responseFlow
                    .timeout(30, TimeUnit.SECONDS)
                    .blockingFirst();
            
            // Extract text from the response  
            if (response != null && response.content() != null) {
                // response.content() returns Optional<Content>, so we need to handle it properly
                if (response.content().isPresent()) {
                    Content responseContent = response.content().get();
                    
                    // Check if parts are present and extract text
                    if (responseContent.parts().isPresent()) {
                        StringBuilder answer = new StringBuilder();
                        for (Part part : responseContent.parts().get()) {
                            if (part.text() != null && !part.text().isEmpty()) {
                                answer.append(part.text());
                            }
                        }
                        return answer.toString();
                    }
                }
            }
            
            return "No response generated from agent";
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate answer using ADK agent", e);
        }
    }
}