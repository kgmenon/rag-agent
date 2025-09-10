package com.rag.agent.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextSplitterTest {
    
    private final TextSplitter textSplitter = new TextSplitter(5, 2);
    
    @Test
    void testSplitBasicText() {
        String text = "This is a test document with many words for splitting.";
        
        List<String> chunks = textSplitter.split(text);
        
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.get(0).contains("This is a test document"));
    }
    
    @Test
    void testSplitWithOverlap() {
        String text = "word1 word2 word3 word4 word5 word6 word7 word8";
        
        List<String> chunks = textSplitter.split(text);
        
        assertTrue(chunks.size() >= 2);
    }
    
    @Test
    void testSplitEmptyText() {
        String text = "";
        
        List<String> chunks = textSplitter.split(text);
        
        assertTrue(chunks.isEmpty());
    }
    
    @Test
    void testSplitNullText() {
        List<String> chunks = textSplitter.split(null);
        
        assertTrue(chunks.isEmpty());
    }
    
    @Test
    void testSplitShortText() {
        String text = "short text";
        
        List<String> chunks = textSplitter.split(text);
        
        assertEquals(1, chunks.size());
        assertEquals("short text", chunks.get(0));
    }
    
    @Test
    void testDefaultConstructor() {
        TextSplitter defaultSplitter = new TextSplitter();
        String text = "This is a test";
        
        List<String> chunks = defaultSplitter.split(text);
        
        assertFalse(chunks.isEmpty());
    }
}