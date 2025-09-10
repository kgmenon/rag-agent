package com.rag.agent.query;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.rag.agent.agent.AdkAgentFactory;
import com.rag.agent.util.JsonUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class QueryHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final RetrievalClient retrievalClient;
    
    public QueryHandler() {
        this.retrievalClient = new RetrievalClient();
    }
    
    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            if ("OPTIONS".equals(input.getHttpMethod())) {
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(200)
                        .withHeaders(getCorsHeaders())
                        .withBody("");
            }
            
            JsonNode requestBody = JsonUtil.parseJson(input.getBody());
            String query = requestBody.get("query").asText();
            
            context.getLogger().log("Processing query: " + query);
            
            List<RetrievalClient.RetrievalResult> searchResults = retrievalClient.search(query);
            context.getLogger().log("Retrieved " + searchResults.size() + " passages");
            
            List<String> passages = new ArrayList<>();
            List<Map<String, Object>> citations = new ArrayList<>();
            
            for (RetrievalClient.RetrievalResult result : searchResults) {
                passages.add(result.getText());
                citations.add(result.toCitation());
            }
            
            String answer = AdkAgentFactory.generateAnswer(passages, query);
            context.getLogger().log("Generated answer of length: " + answer.length());
            
            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("answer", answer);
            responseBody.put("citations", citations);
            responseBody.put("usedPassages", passages);
            
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(getCorsHeaders())
                    .withBody(JsonUtil.toJson(responseBody));
                    
        } catch (Exception e) {
            context.getLogger().log("Error processing query: " + e.getMessage());
            e.printStackTrace();
            
            Map<String, String> errorBody = Map.of("error", "Failed to process query: " + e.getMessage());
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withHeaders(getCorsHeaders())
                    .withBody(JsonUtil.toJson(errorBody));
        }
    }
    
    private Map<String, String> getCorsHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "POST, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type");
        return headers;
    }
}