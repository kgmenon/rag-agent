package com.rag.agent;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.rag.agent.services.*;
import com.rag.agent.agent.DocumentChatbotAgent;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;

public class Main {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static S3Service s3Service;
    private static RAGService ragService;
    private static DocumentChatbotAgent chatbotAgent;
    
    public static void main(String[] args) throws IOException {
        // Initialize services with shared VectorService instance
        s3Service = new S3Service();
        
        // Create shared VectorService instance for both RAG processing and chatbot
        VectorService sharedVectorService = new VectorService();
        ragService = new RAGService(sharedVectorService);  // Pass shared instance
        chatbotAgent = new DocumentChatbotAgent(sharedVectorService);
        
        System.out.println("Initialized Google ADK Document Chatbot Agent");
        
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        
        server.createContext("/health", new HealthHandler());
        server.createContext("/upload/initiate", new UploadInitiateHandler());
        server.createContext("/upload/presignPart", new UploadPresignPartHandler());
        server.createContext("/upload/complete", new UploadCompleteHandler());
        server.createContext("/query", new QueryHandler()); // Legacy endpoint
        server.createContext("/chat", new DocumentChatHandler()); // New document chatbot endpoint
        
        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();
        
        System.out.println("Document Chatbot with Google ADK started on port 8080");
        System.out.println("Health check endpoint: http://localhost:8080/health");
        System.out.println("Upload initiate endpoint: http://localhost:8080/upload/initiate");
        System.out.println("Document Chat endpoint: http://localhost:8080/chat");
        System.out.println("Legacy Query endpoint: http://localhost:8080/query");
        
        // Add shutdown hook to clean up resources
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down server...");
            server.stop(5);
            if (ragService != null) {
                ragService.close();
            }
            if (s3Service != null) {
                s3Service.close();
            }
            System.out.println("Server shutdown complete");
        }));
    }
    
    private static void addCORSHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "3600");
    }
    
    private static void handleOptions(HttpExchange exchange) throws IOException {
        addCORSHeaders(exchange);
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().close();
    }
    
    private static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        addCORSHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(responseBytes);
        os.close();
    }
    
    private static String readRequestBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
    
    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            
            // Enhanced health check for Google ADK chatbot
            Map<String, Object> healthStatus = new HashMap<>();
            healthStatus.put("status", "healthy");
            healthStatus.put("service", "document-chatbot-google-adk");
            healthStatus.put("timestamp", java.time.Instant.now().toString());
            healthStatus.put("components", Map.of(
                "s3Service", s3Service != null ? "ready" : "not_initialized",
                "ragService", ragService != null ? "ready" : "not_initialized", 
                "chatbotAgent", chatbotAgent != null ? "ready" : "not_initialized"
            ));
            
            String response = objectMapper.writeValueAsString(healthStatus);
            sendResponse(exchange, 200, response);
        }
    }
    
    static class UploadInitiateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            
            try {
                String requestBody = readRequestBody(exchange);
                JsonNode jsonNode = objectMapper.readTree(requestBody);
                
                String fileName = jsonNode.get("fileName").asText();
                String contentType = jsonNode.has("contentType") ? 
                    jsonNode.get("contentType").asText() : "application/octet-stream";
                
                System.out.println("Initiating upload for file: " + fileName);
                
                CreateMultipartUploadResponse uploadResponse = s3Service.initiateMultipartUpload(fileName, contentType);
                
                Map<String, Object> response = new HashMap<>();
                response.put("uploadId", uploadResponse.uploadId());
                response.put("key", uploadResponse.key());
                response.put("bucket", uploadResponse.bucket());
                
                String jsonResponse = objectMapper.writeValueAsString(response);
                sendResponse(exchange, 200, jsonResponse);
                
            } catch (Exception e) {
                System.err.println("Error in upload initiate: " + e.getMessage());
                sendResponse(exchange, 500, "{\"error\":\"Internal server error: " + e.getMessage() + "\"}");
            }
        }
    }
    
    static class UploadPresignPartHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            
            try {
                String query = exchange.getRequestURI().getQuery();
                Map<String, String> params = parseQueryString(query);
                
                String bucket = params.get("bucket");
                String key = params.get("key");
                String uploadId = params.get("uploadId");
                String partNumber = params.get("partNumber");
                
                if (bucket == null || key == null || uploadId == null || partNumber == null) {
                    sendResponse(exchange, 400, "{\"error\":\"Missing required parameters\"}");
                    return;
                }
                
                String presignedUrl = s3Service.generatePresignedUrlForPart(bucket, key, uploadId, Integer.parseInt(partNumber));
                
                Map<String, Object> response = new HashMap<>();
                response.put("presignedUrl", presignedUrl);
                
                String jsonResponse = objectMapper.writeValueAsString(response);
                sendResponse(exchange, 200, jsonResponse);
                
            } catch (Exception e) {
                System.err.println("Error generating presigned URL: " + e.getMessage());
                sendResponse(exchange, 500, "{\"error\":\"Internal server error: " + e.getMessage() + "\"}");
            }
        }
    }
    
    static class UploadCompleteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            
            try {
                String requestBody = readRequestBody(exchange);
                JsonNode jsonNode = objectMapper.readTree(requestBody);
                
                String uploadId = jsonNode.get("uploadId").asText();
                String key = jsonNode.get("key").asText();
                String bucket = jsonNode.get("bucket").asText();
                
                List<CompletedPart> parts = new ArrayList<>();
                JsonNode partsArray = jsonNode.get("parts");
                if (partsArray != null && partsArray.isArray()) {
                    for (JsonNode partNode : partsArray) {
                        int partNumber = partNode.get("PartNumber").asInt();
                        String etag = partNode.get("ETag").asText();
                        parts.add(CompletedPart.builder()
                            .partNumber(partNumber)
                            .eTag(etag)
                            .build());
                    }
                }
                
                CompleteMultipartUploadResponse completeResponse = s3Service.completeMultipartUpload(bucket, key, uploadId, parts);
                
                // Extract filename from key
                String fileName = key.substring(key.lastIndexOf('/') + 1);
                
                // Start enhanced document processing asynchronously with page awareness
                System.out.println("Starting page-aware document processing with Google ADK for: " + fileName);
                CompletableFuture<Void> processingFuture = ragService.processDocument(bucket, key, fileName);
                processingFuture.whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        System.err.println("Document processing failed for " + fileName + ": " + throwable.getMessage());
                    } else {
                        System.out.println("Page-aware document processing completed for " + fileName);
                        System.out.println("Document is now ready for page-specific queries (e.g., 'What does page 5 describe?')");
                    }
                });
                
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("location", completeResponse.location());
                response.put("bucket", completeResponse.bucket());
                response.put("key", completeResponse.key());
                response.put("etag", completeResponse.eTag());
                
                String jsonResponse = objectMapper.writeValueAsString(response);
                sendResponse(exchange, 200, jsonResponse);
                
            } catch (Exception e) {
                System.err.println("Error completing upload: " + e.getMessage());
                sendResponse(exchange, 500, "{\"error\":\"Internal server error: " + e.getMessage() + "\"}");
            }
        }
    }
    
    static class QueryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            
            try {
                String requestBody = readRequestBody(exchange);
                JsonNode jsonNode = objectMapper.readTree(requestBody);
                
                String query = jsonNode.get("query").asText();
                
                System.out.println("Processing RAG query: " + query);
                
                RAGService.QueryResponse queryResponse = ragService.answerQuery(query);
                
                Map<String, Object> response = new HashMap<>();
                response.put("answer", queryResponse.getAnswer());
                response.put("query", queryResponse.getQuery());
                response.put("timestamp", queryResponse.getTimestamp());
                
                List<Map<String, Object>> citations = new ArrayList<>();
                for (RAGService.Citation citation : queryResponse.getCitations()) {
                    Map<String, Object> citationMap = new HashMap<>();
                    citationMap.put("fileName", citation.getFileName());
                    citationMap.put("score", citation.getScore());
                    citations.add(citationMap);
                }
                response.put("citations", citations);
                
                String jsonResponse = objectMapper.writeValueAsString(response);
                sendResponse(exchange, 200, jsonResponse);
                
            } catch (Exception e) {
                System.err.println("Error in query handler: " + e.getMessage());
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"error\":\"Internal server error: " + e.getMessage() + "\"}");
            }
        }
    }
    
    private static Map<String, String> parseQueryString(String query) {
        Map<String, String> params = new HashMap<>();
        if (query != null) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                String[] keyValue = pair.split("=");
                if (keyValue.length == 2) {
                    try {
                        params.put(keyValue[0], java.net.URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        System.err.println("Error decoding query parameter: " + e.getMessage());
                    }
                }
            }
        }
        return params;
    }
    
    /**
     * Enhanced Document Chat Handler using Google ADK agents
     * Supports page-specific queries like "What does page 23 describe?"
     */
    static class DocumentChatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            
            try {
                String requestBody = readRequestBody(exchange);
                JsonNode jsonNode = objectMapper.readTree(requestBody);
                
                // Support both "query" and "message" field names for flexibility
                String userQuery = null;
                if (jsonNode.has("query")) {
                    userQuery = jsonNode.get("query").asText();
                } else if (jsonNode.has("message")) {
                    userQuery = jsonNode.get("message").asText();
                } else {
                    throw new RuntimeException("Request must contain either 'query' or 'message' field");
                }
                
                System.out.println("Processing document chat query with Google ADK: " + userQuery);
                
                // Use Google ADK Document Chatbot Agent for processing
                DocumentChatbotAgent.ChatbotResponse chatResponse = chatbotAgent.processQuery(userQuery);
                
                Map<String, Object> response = new HashMap<>();
                response.put("answer", chatResponse.getAnswer());
                response.put("success", chatResponse.isSuccess());
                response.put("timestamp", chatResponse.getTimestamp());
                
                // Include source information with page details
                List<Map<String, Object>> sources = new ArrayList<>();
                for (DocumentProcessor.DocumentChunk chunk : chatResponse.getSources()) {
                    Map<String, Object> source = new HashMap<>();
                    source.put("fileName", chunk.getFileName());
                    source.put("pageNumber", chunk.getPageNumber());
                    source.put("chunkType", chunk.getChunkType());
                    source.put("preview", chunk.getContent().length() > 200 ? 
                        chunk.getContent().substring(0, 200) + "..." : chunk.getContent());
                    sources.add(source);
                }
                response.put("sources", sources);
                
                // Include query analysis if available
                if (chatResponse.getAnalysis() != null) {
                    Map<String, Object> analysis = new HashMap<>();
                    analysis.put("queryType", chatResponse.getAnalysis().getQueryType().toString());
                    analysis.put("pageNumbers", chatResponse.getAnalysis().getPageNumbers());
                    response.put("analysis", analysis);
                }
                
                String jsonResponse = objectMapper.writeValueAsString(response);
                sendResponse(exchange, 200, jsonResponse);
                
            } catch (Exception e) {
                System.err.println("Error in document chat handler: " + e.getMessage());
                e.printStackTrace();
                
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", "Failed to process your question about the document");
                errorResponse.put("success", false);
                errorResponse.put("details", e.getMessage());
                
                String jsonResponse = objectMapper.writeValueAsString(errorResponse);
                sendResponse(exchange, 500, jsonResponse);
            }
        }
    }
}