package com.rag.agent.services;

import com.rag.agent.services.DocumentProcessor.DocumentChunk;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class RAGService {
    private final S3Service s3Service;
    private final DocumentProcessor documentProcessor;
    private final EmbeddingService embeddingService;
    private final VectorService vectorService;
    
    public RAGService() {
        this.s3Service = new S3Service();
        this.documentProcessor = new DocumentProcessor();
        this.embeddingService = new EmbeddingService();
        this.vectorService = new VectorService();
    }
    
    public RAGService(VectorService sharedVectorService) {
        this.s3Service = new S3Service();
        this.documentProcessor = new DocumentProcessor();
        this.embeddingService = new EmbeddingService();
        this.vectorService = sharedVectorService;  // Use shared instance
    }
    
    /**
     * Process an uploaded document: extract text, chunk it, generate embeddings, and index in vector store
     */
    public CompletableFuture<Void> processDocument(String bucket, String key, String fileName) {
        return CompletableFuture.runAsync(() -> {
            try {
                System.out.println("Processing document: " + fileName + " from " + bucket + "/" + key);
                
                // Step 1: Download and extract text with page information from S3
                InputStream documentStream = s3Service.getObjectContent(bucket, key);
                DocumentProcessor.PageAwareDocument pageAwareDoc = documentProcessor.extractTextWithPageInfo(documentStream, fileName);
                
                if (pageAwareDoc.getPages().isEmpty()) {
                    System.err.println("No text extracted from document: " + fileName);
                    return;
                }
                
                System.out.println("Extracted content from " + pageAwareDoc.getPageCount() + " pages in " + fileName);
                
                // Step 2: Create page-aware chunks
                String documentId = key.replace("/", "_");
                List<DocumentChunk> chunks = documentProcessor.createPageAwareChunks(pageAwareDoc, documentId);
                
                if (chunks.isEmpty()) {
                    System.err.println("No chunks created for document: " + fileName);
                    return;
                }
                
                System.out.println("Created " + chunks.size() + " page-aware chunks for " + fileName);
                
                // Step 3: Index chunks with Google ADK agents (no embeddings needed)
                vectorService.indexDocumentChunks(chunks);
                
                System.out.println("Successfully processed document with page awareness: " + fileName);
                System.out.println("Document is ready for page-specific queries!");
                
            } catch (Exception e) {
                System.err.println("Error processing document " + fileName + ": " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Document processing failed", e);
            }
        });
    }
    
    /**
     * Answer a user query using RAG
     */
    public QueryResponse answerQuery(String query) {
        try {
            System.out.println("Processing query with Google ADK agents: " + query);
            
            // Step 1: Use Google ADK agent to search for relevant chunks (no embeddings needed)
            List<DocumentChunk> searchResults = vectorService.searchWithAdkAgent(query, 5);
            
            if (searchResults.isEmpty()) {
                return new QueryResponse(
                    "I don't have any relevant documents to answer your question. Please upload some documents first.",
                    List.of(),
                    query
                );
            }
            
            // Step 2: Build context from search results
            String context = buildContextFromResults(searchResults);
            
            // Step 3: Generate answer using Google ADK (via EmbeddingService)
            String answer = embeddingService.generateAnswer(query, context);
            
            // Step 4: Prepare citations
            List<Citation> citations = searchResults.stream()
                .map(result -> new Citation(result.getFileName(), 1.0))
                .collect(Collectors.toList());
            
            System.out.println("Successfully answered query with Google ADK using " + citations.size() + " citations");
            
            return new QueryResponse(answer, citations, query);
            
        } catch (Exception e) {
            System.err.println("Error answering query with Google ADK: " + e.getMessage());
            e.printStackTrace();
            return new QueryResponse(
                "I apologize, but I encountered an error while processing your question. Please try again.",
                List.of(),
                query
            );
        }
    }
    
    private String buildContextFromResults(List<DocumentChunk> results) {
        StringBuilder context = new StringBuilder();
        
        for (int i = 0; i < results.size(); i++) {
            DocumentChunk result = results.get(i);
            context.append("Document: ").append(result.getFileName()).append("\n");
            context.append("Page: ").append(result.getPageNumber()).append("\n");
            context.append("Content: ").append(result.getContent()).append("\n");
            if (i < results.size() - 1) {
                context.append("\n---\n\n");
            }
        }
        
        return context.toString();
    }
    
    public void close() {
        try {
            if (s3Service != null) s3Service.close();
            if (embeddingService != null) embeddingService.close();
            if (vectorService != null) vectorService.close();
        } catch (Exception e) {
            System.err.println("Error closing RAG service: " + e.getMessage());
        }
    }
    
    public static class QueryResponse {
        private final String answer;
        private final List<Citation> citations;
        private final String query;
        private final long timestamp;
        
        public QueryResponse(String answer, List<Citation> citations, String query) {
            this.answer = answer;
            this.citations = citations;
            this.query = query;
            this.timestamp = System.currentTimeMillis();
        }
        
        public String getAnswer() { return answer; }
        public List<Citation> getCitations() { return citations; }
        public String getQuery() { return query; }
        public long getTimestamp() { return timestamp; }
    }
    
    public static class Citation {
        private final String fileName;
        private final double score;
        
        public Citation(String fileName, double score) {
            this.fileName = fileName;
            this.score = score;
        }
        
        public String getFileName() { return fileName; }
        public double getScore() { return score; }
    }
}