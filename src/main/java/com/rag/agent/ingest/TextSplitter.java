package com.rag.agent.ingest;

import java.util.ArrayList;
import java.util.List;

public class TextSplitter {
    private final int chunkSize;
    private final int overlap;
    
    public TextSplitter() {
        this(512, 50);
    }
    
    public TextSplitter(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }
    
    public List<String> split(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new ArrayList<>();
        }
        
        String[] words = text.split("\\s+");
        List<String> chunks = new ArrayList<>();
        
        int startIndex = 0;
        while (startIndex < words.length) {
            int endIndex = Math.min(startIndex + chunkSize, words.length);
            
            StringBuilder chunk = new StringBuilder();
            for (int i = startIndex; i < endIndex; i++) {
                if (i > startIndex) {
                    chunk.append(" ");
                }
                chunk.append(words[i]);
            }
            
            String chunkText = chunk.toString().trim();
            if (!chunkText.isEmpty()) {
                chunks.add(chunkText);
            }
            
            if (endIndex >= words.length) {
                break;
            }
            
            startIndex = Math.max(startIndex + chunkSize - overlap, startIndex + 1);
        }
        
        return chunks;
    }
}