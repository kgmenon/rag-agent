package com.rag.agent.services;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    
    /**
     * Enhanced extraction with page awareness for PDFs
     */
    public PageAwareDocument extractTextWithPageInfo(InputStream inputStream, String fileName) throws IOException {
        if (fileName.toLowerCase().endsWith(".pdf")) {
            return extractFromPDF(inputStream, fileName);
        } else {
            // For non-PDF files, extract as single page
            String fullText;
            try {
                fullText = tika.parseToString(inputStream);
            } catch (TikaException e) {
                throw new IOException("Failed to extract text from document: " + e.getMessage(), e);
            }
            return new PageAwareDocument(fileName, Map.of(1, fullText));
        }
    }
    
    private PageAwareDocument extractFromPDF(InputStream inputStream, String fileName) throws IOException {
        Map<Integer, String> pageContents = new HashMap<>();
        
        byte[] bytes = inputStream.readAllBytes();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            
            for (int pageNum = 1; pageNum <= document.getNumberOfPages(); pageNum++) {
                stripper.setStartPage(pageNum);
                stripper.setEndPage(pageNum);
                
                String pageText = stripper.getText(document);
                if (!pageText.trim().isEmpty()) {
                    pageContents.put(pageNum, cleanText(pageText.trim()));
                }
            }
        } catch (Exception e) {
            throw new IOException("Failed to extract PDF pages: " + e.getMessage(), e);
        }
        
        return new PageAwareDocument(fileName, pageContents);
    }
    
    /**
     * Create page-aware chunks from a PageAwareDocument
     */
    public List<DocumentChunk> createPageAwareChunks(PageAwareDocument document, String documentId) {
        List<DocumentChunk> chunks = new ArrayList<>();
        
        for (Map.Entry<Integer, String> page : document.getPages().entrySet()) {
            int pageNum = page.getKey();
            String pageContent = page.getValue();
            
            // Create page-level chunks with metadata
            chunks.add(new DocumentChunk(
                documentId + "_page_" + pageNum,
                documentId,
                document.getFileName(),
                pageContent,
                pageNum,
                "page",
                System.currentTimeMillis()
            ));
            
            // Also create section-level chunks within the page if content is long
            if (pageContent.length() > 2000) {
                chunks.addAll(createSectionChunks(pageContent, documentId, document.getFileName(), pageNum));
            }
        }
        
        return chunks;
    }
    
    /**
     * Legacy method for backward compatibility
     */
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
                        1, // Default to page 1 for legacy method
                        "section",
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
                1, // Default to page 1 for legacy method
                "section",
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
    
    private List<DocumentChunk> createSectionChunks(String pageContent, String documentId, String fileName, int pageNum) {
        List<DocumentChunk> sectionChunks = new ArrayList<>();
        String[] paragraphs = pageContent.split("\n\s*\n");
        
        StringBuilder currentSection = new StringBuilder();
        int sectionIndex = 0;
        
        for (String paragraph : paragraphs) {
            if (currentSection.length() + paragraph.length() > 1500) {
                if (currentSection.length() > 0) {
                    sectionChunks.add(new DocumentChunk(
                        documentId + "_page_" + pageNum + "_section_" + sectionIndex,
                        documentId,
                        fileName,
                        currentSection.toString().trim(),
                        pageNum,
                        "section",
                        System.currentTimeMillis()
                    ));
                    sectionIndex++;
                }
                currentSection = new StringBuilder();
            }
            currentSection.append(paragraph).append("\n\n");
        }
        
        // Add final section
        if (currentSection.length() > 0) {
            sectionChunks.add(new DocumentChunk(
                documentId + "_page_" + pageNum + "_section_" + sectionIndex,
                documentId,
                fileName,
                currentSection.toString().trim(),
                pageNum,
                "section",
                System.currentTimeMillis()
            ));
        }
        
        return sectionChunks;
    }
    
    /**
     * Enhanced DocumentChunk with page information
     */
    public static class DocumentChunk {
        private final String chunkId;
        private final String documentId;
        private final String fileName;
        private final String content;
        private final int pageNumber; // Enhanced: Page information
        private final String chunkType; // Enhanced: "page" or "section"
        private final long timestamp;
        private float[] embedding;
        
        public DocumentChunk(String chunkId, String documentId, String fileName, 
                           String content, int pageNumber, String chunkType, long timestamp) {
            this.chunkId = chunkId;
            this.documentId = documentId;
            this.fileName = fileName;
            this.content = content;
            this.pageNumber = pageNumber;
            this.chunkType = chunkType;
            this.timestamp = timestamp;
        }
        
        // Enhanced getters
        public String getChunkId() { return chunkId; }
        public String getDocumentId() { return documentId; }
        public String getFileName() { return fileName; }
        public String getContent() { return content; }
        public int getPageNumber() { return pageNumber; }
        public String getChunkType() { return chunkType; }
        public long getTimestamp() { return timestamp; }
        public float[] getEmbedding() { return embedding; }
        
        public void setEmbedding(float[] embedding) {
            this.embedding = embedding;
        }
    }
    
    /**
     * Container for page-aware document content
     */
    public static class PageAwareDocument {
        private final String fileName;
        private final Map<Integer, String> pages;
        
        public PageAwareDocument(String fileName, Map<Integer, String> pages) {
            this.fileName = fileName;
            this.pages = pages;
        }
        
        public String getFileName() { return fileName; }
        public Map<Integer, String> getPages() { return pages; }
        public int getPageCount() { return pages.size(); }
        
        public String getPageContent(int pageNumber) {
            return pages.get(pageNumber);
        }
        
        public List<Integer> getPageNumbers() {
            return new ArrayList<>(pages.keySet()).stream().sorted().collect(Collectors.toList());
        }
    }
}