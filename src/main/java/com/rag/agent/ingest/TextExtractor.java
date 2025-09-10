package com.rag.agent.ingest;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;

import java.io.IOException;
import java.io.InputStream;

public class TextExtractor {
    private final Tika tika;
    
    public TextExtractor() {
        this.tika = new Tika();
    }
    
    public String extractText(InputStream inputStream, String contentType) throws IOException {
        try {
            String text = tika.parseToString(inputStream);
            return normalizeText(text);
        } catch (TikaException e) {
            throw new IOException("Failed to extract text from document", e);
        }
    }
    
    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        
        return text
                .replaceAll("\\s+", " ")
                .replaceAll("[\\r\\n]+", " ")
                .trim();
    }
}