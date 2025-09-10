package com.rag.agent.services;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class DocumentProcessor {
    private final Tika tika;
    private static final int CHUNK_SIZE = 1000;
    private static final int CHUNK_OVERLAP = 200;
    
    public DocumentProcessor() {
        this.tika = new Tika();
    }
    
    public String extractText(InputStream inputStream, String fileName) throws IOException, TikaException {
        return tika.parseToString(inputStream);
    }
    
    public List<DocumentChunk> chunkDocument(String text, String documentId, String fileName) {
        List<DocumentChunk> chunks = new ArrayList<>();
        
        if (text == null || text.trim().isEmpty()) {
            return chunks;
        }
        
        // Clean the text
        String cleanedText = cleanText(text);
        
        // Split into sentences for better chunking
        String[] sentences = cleanedText.split("(?<=[.!?])\\s+");
        
        StringBuilder currentChunk = new StringBuilder();
        int chunkIndex = 0;
        
        for (String sentence : sentences) {
            if (currentChunk.length() + sentence.length() > CHUNK_SIZE && currentChunk.length() > 0) {
                // Create chunk
                String chunkText = currentChunk.toString().trim();
                if (!chunkText.isEmpty()) {
                    chunks.add(new DocumentChunk(
                        documentId + "_chunk_" + chunkIndex,
                        documentId,
                        fileName,
                        chunkText,
                        chunkIndex,
                        System.currentTimeMillis()
                    ));
                    chunkIndex++;
                }
                
                // Start new chunk with overlap
                currentChunk = new StringBuilder();
                if (chunkText.length() > CHUNK_OVERLAP) {
                    String overlap = chunkText.substring(chunkText.length() - CHUNK_OVERLAP);
                    currentChunk.append(overlap).append(" ");
                }
            }
            
            currentChunk.append(sentence).append(" ");
        }
        
        // Add final chunk
        String finalChunkText = currentChunk.toString().trim();
        if (!finalChunkText.isEmpty()) {
            chunks.add(new DocumentChunk(
                documentId + "_chunk_" + chunkIndex,
                documentId,
                fileName,
                finalChunkText,
                chunkIndex,
                System.currentTimeMillis()
            ));
        }
        
        return chunks;
    }
    
    private String cleanText(String text) {
        // Remove excessive whitespace
        text = Pattern.compile("\\s+").matcher(text).replaceAll(" ");
        
        // Remove control characters except newlines and tabs
        text = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]").matcher(text).replaceAll("");
        
        // Normalize quotes
        text = text.replace("\u201C", "\"").replace("\u201D", "\"");
        text = text.replace("\u2018", "'").replace("\u2019", "'");
        
        return text.trim();
    }
    
    public static class DocumentChunk {
        private final String chunkId;
        private final String documentId;
        private final String fileName;
        private final String content;
        private final int chunkIndex;
        private final long timestamp;
        private float[] embedding;
        
        public DocumentChunk(String chunkId, String documentId, String fileName, 
                           String content, int chunkIndex, long timestamp) {
            this.chunkId = chunkId;
            this.documentId = documentId;
            this.fileName = fileName;
            this.content = content;
            this.chunkIndex = chunkIndex;
            this.timestamp = timestamp;
        }
        
        // Getters
        public String getChunkId() { return chunkId; }
        public String getDocumentId() { return documentId; }
        public String getFileName() { return fileName; }
        public String getContent() { return content; }
        public int getChunkIndex() { return chunkIndex; }
        public long getTimestamp() { return timestamp; }
        public float[] getEmbedding() { return embedding; }
        
        public void setEmbedding(float[] embedding) {
            this.embedding = embedding;
        }
    }
}