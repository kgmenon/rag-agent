package com.rag.agent.services;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.rag.agent.agent.CustomLiteLlmModel;
import com.rag.agent.services.DocumentProcessor.DocumentChunk;
import io.reactivex.rxjava3.core.Flowable;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class VectorService {
    private final Map<String, DocumentChunk> documentStore;
    private final Map<Integer, List<DocumentChunk>> pageIndex; // NEW: Page-based index
    private final Map<String, List<DocumentChunk>> documentIndex; // NEW: Document-based index
    private final LlmAgent searchAgent;
    private final CustomLiteLlmModel llmModel;
    
    public VectorService() {
        this.documentStore = new ConcurrentHashMap<>();
        this.pageIndex = new ConcurrentHashMap<>();
        this.documentIndex = new ConcurrentHashMap<>();
        this.llmModel = new CustomLiteLlmModel();
        
        // Create Google ADK agent for document search and retrieval
        this.searchAgent = LlmAgent.builder()
            .name("anthropic.claude-3-sonnet-20240229-v1:0")
            .model(llmModel)
            .instruction("You are a document search assistant. Given a query and a list of document chunks, " +
                        "identify and return the most relevant chunks that can help answer the query. " +
                        "Rank them by relevance and provide clear reasoning for your selections.")
            .build();
        
        System.out.println("Initializing VectorService with Google ADK agents");
        System.out.println("Document storage: In-memory with Google ADK intelligent search");
        System.out.println("VectorService successfully initialized with Google ADK solution");
    }
    
    
    public void indexDocumentChunks(List<DocumentProcessor.DocumentChunk> chunks) throws IOException {
        if (chunks.isEmpty()) {
            return;
        }
        
        System.out.println("Storing " + chunks.size() + " document chunks with Google ADK agents");
        
        for (DocumentProcessor.DocumentChunk chunk : chunks) {
            // Store chunks in memory for Google ADK agent processing
            documentStore.put(chunk.getChunkId(), chunk);
            
            // Build page index for fast page-specific lookups
            pageIndex.computeIfAbsent(chunk.getPageNumber(), k -> new ArrayList<>()).add(chunk);
            
            // Build document index for document-specific operations
            documentIndex.computeIfAbsent(chunk.getDocumentId(), k -> new ArrayList<>()).add(chunk);
        }
        
        System.out.println("Successfully indexed " + chunks.size() + " document chunks with Google ADK");
        System.out.println("Total chunks available to ADK agents: " + documentStore.size());
        System.out.println("Pages indexed: " + pageIndex.size());
        System.out.println("Documents indexed: " + documentIndex.size());
    }
    
    /**
     * Get chunks by specific page numbers - NEW method for page-aware queries
     */
    public List<DocumentChunk> getChunksByPages(List<Integer> pageNumbers) {
        List<DocumentChunk> pageChunks = new ArrayList<>();
        
        for (Integer pageNum : pageNumbers) {
            List<DocumentChunk> chunks = pageIndex.get(pageNum);
            if (chunks != null) {
                pageChunks.addAll(chunks);
                System.out.println("Found " + chunks.size() + " chunks on page " + pageNum);
            } else {
                System.out.println("No content found for page " + pageNum);
            }
        }
        
        return pageChunks.stream()
            .sorted(Comparator.comparingInt(DocumentChunk::getPageNumber))
            .collect(Collectors.toList());
    }
    
    
    public List<DocumentChunk> searchWithAdkAgent(String query, int topK) {
        if (documentStore.isEmpty()) {
            System.out.println("No documents available for search");
            return new ArrayList<>();
        }
        
        System.out.println("Using Google ADK agent to search for: " + query);
        
        try {
            // Prepare document chunks for the agent
            StringBuilder documentsContext = new StringBuilder();
            documentsContext.append("Available document chunks:\n\n");
            
            int chunkCounter = 1;
            Map<Integer, DocumentChunk> chunkIndex = new HashMap<>();
            
            for (DocumentChunk chunk : documentStore.values()) {
                documentsContext.append("Chunk ").append(chunkCounter).append(":\n");
                documentsContext.append("File: ").append(chunk.getFileName()).append("\n");
                documentsContext.append("Content: ").append(chunk.getContent()).append("\n\n");
                chunkIndex.put(chunkCounter, chunk);
                chunkCounter++;
            }
            
            // Create search prompt for the agent
            String searchPrompt = documentsContext.toString() + 
                "Query: \"" + query + "\"\n\n" +
                "Please identify the " + topK + " most relevant chunks for this query. " +
                "Return only the chunk numbers (e.g., 1, 3, 7) separated by commas, " +
                "ordered by relevance (most relevant first).";
            
            // Create the user input as Content with text Part
            Part textPart = Part.builder().text(searchPrompt).build();
            Content userContent = Content.builder()
                .parts(List.of(textPart))
                .build();
            
            // Create LlmRequest with the user content
            LlmRequest request = LlmRequest.builder()
                .contents(List.of(userContent))
                .build();
            
            // Use the model to get search results
            Flowable<LlmResponse> responseFlow = llmModel.generateContent(request, false);
            LlmResponse response = responseFlow
                .timeout(30, TimeUnit.SECONDS)
                .blockingFirst();
            
            String agentResponse = "";
            if (response != null && response.content() != null && response.content().isPresent()) {
                Content responseContent = response.content().get();
                if (responseContent.parts().isPresent()) {
                    StringBuilder answer = new StringBuilder();
                    for (Part part : responseContent.parts().get()) {
                        if (part.text() != null && !part.text().isEmpty()) {
                            answer.append(part.text());
                        }
                    }
                    agentResponse = answer.toString();
                }
            }
            
            // Parse the agent's response to get chunk numbers
            List<DocumentChunk> results = parseAgentSearchResponse(agentResponse, chunkIndex, query);
            
            System.out.println("Google ADK agent found " + results.size() + " relevant chunks");
            return results;
            
        } catch (Exception e) {
            System.err.println("Error using Google ADK agent for search: " + e.getMessage());
            e.printStackTrace();
            
            // Fallback to simple keyword matching
            return fallbackKeywordSearch(query, topK);
        }
    }
    
    private List<DocumentChunk> parseAgentSearchResponse(String agentResponse, Map<Integer, DocumentChunk> chunkIndex, String query) {
        List<DocumentChunk> results = new ArrayList<>();
        
        try {
            // Extract numbers from the agent's response
            String[] parts = agentResponse.replaceAll("[^0-9,]", "").split(",");
            double score = 1.0;
            
            for (String part : parts) {
                try {
                    int chunkNum = Integer.parseInt(part.trim());
                    DocumentChunk chunk = chunkIndex.get(chunkNum);
                    if (chunk != null) {
                        results.add(chunk);
                    }
                } catch (NumberFormatException e) {
                    // Skip invalid numbers
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing agent response, using fallback: " + e.getMessage());
        }
        
        // If parsing failed, use fallback
        if (results.isEmpty()) {
            return fallbackKeywordSearch(query, Math.min(5, chunkIndex.size()));
        }
        
        return results;
    }
    
    private List<DocumentChunk> fallbackKeywordSearch(String query, int topK) {
        System.out.println("Using fallback keyword search for: " + query);
        
        List<DocumentChunk> results = new ArrayList<>();
        String[] queryWords = query.toLowerCase().split("\\s+");
        
        for (DocumentChunk chunk : documentStore.values()) {
            String content = chunk.getContent().toLowerCase();
            
            double score = 0.0;
            for (String word : queryWords) {
                if (content.contains(word)) {
                    score += 1.0;
                }
            }
            
            if (score > 0) {
                results.add(chunk);
            }
        }
        
        // Sort by page number for consistent results
        results.sort(Comparator.comparingInt(DocumentChunk::getPageNumber));
        return results.subList(0, Math.min(topK, results.size()));
    }
    
    public void close() throws IOException {
        // No resources to close with Google ADK solution
        System.out.println("VectorService with Google ADK agents closed successfully");
    }
}