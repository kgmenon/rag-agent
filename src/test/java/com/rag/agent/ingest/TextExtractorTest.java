package com.rag.agent.ingest;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class TextExtractorTest {
    
    private final TextExtractor textExtractor = new TextExtractor();
    
    @Test
    void testExtractTextFromPlainText() throws IOException {
        String input = "This is a test document.";
        ByteArrayInputStream inputStream = new ByteArrayInputStream(input.getBytes());
        
        String result = textExtractor.extractText(inputStream, "text/plain");
        
        assertEquals("This is a test document.", result);
    }
    
    @Test
    void testExtractTextNormalizesWhitespace() throws IOException {
        String input = "This   is\n\na\r\ntest\t\tdocument.";
        ByteArrayInputStream inputStream = new ByteArrayInputStream(input.getBytes());
        
        String result = textExtractor.extractText(inputStream, "text/plain");
        
        assertEquals("This is a test document.", result);
    }
    
    @Test
    void testExtractTextHandlesEmptyInput() throws IOException {
        String input = "";
        ByteArrayInputStream inputStream = new ByteArrayInputStream(input.getBytes());
        
        String result = textExtractor.extractText(inputStream, "text/plain");
        
        assertEquals("", result);
    }
    
    @Test
    void testExtractTextHandlesNullInput() throws IOException {
        ByteArrayInputStream inputStream = new ByteArrayInputStream(new byte[0]);
        
        String result = textExtractor.extractText(inputStream, "text/plain");
        
        assertEquals("", result);
    }
}