package com.rag.agent.query;

import com.rag.agent.ingest.EmbeddingsClient;
import com.rag.agent.ingest.OpenSearchClientProvider;
import com.rag.agent.util.Env;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.query_dsl.KnnQuery;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import software.amazon.awssdk.regions.Region;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RetrievalClient {
    private final OpenSearchClient openSearchClient;
    private final EmbeddingsClient embeddingsClient;
    private final String indexName;
    private final int topK;
    
    public RetrievalClient() {
        Region region = Region.of(Env.getOrDefault("AWS_REGION", "us-east-1"));
        this.openSearchClient = OpenSearchClientProvider.getClient(
            Env.require("OPENSEARCH_ENDPOINT"), region);
        this.embeddingsClient = new EmbeddingsClient(region);
        this.indexName = Env.getOrDefault("OPENSEARCH_INDEX", "documents");
        this.topK = Integer.parseInt(Env.getOrDefault("RETRIEVAL_TOP_K", "5"));
    }
    
    @SuppressWarnings("unchecked")
    public List<RetrievalResult> search(String query) {
        try {
            float[] queryEmbedding = embeddingsClient.getEmbedding(query);
            
            KnnQuery knnQuery = KnnQuery.of(builder -> builder
                    .field("embedding")
                    .vector(queryEmbedding)
                    .k(topK)
            );
            
            SearchRequest searchRequest = SearchRequest.of(builder -> builder
                    .index(indexName)
                    .query(q -> q.knn(knnQuery))
                    .size(topK)
            );
            
            SearchResponse<Map> response = openSearchClient.search(searchRequest, Map.class);
            
            List<RetrievalResult> results = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map<String, Object> source = hit.source();
                RetrievalResult result = new RetrievalResult(
                    (String) source.get("id"),
                    (String) source.get("text"),
                    (String) source.get("sourceKey"),
                    (Integer) source.get("position"),
                    hit.score()
                );
                results.add(result);
            }
            
            return results;
        } catch (Exception e) {
            throw new RuntimeException("Failed to search documents", e);
        }
    }
    
    
    public static class RetrievalResult {
        private final String id;
        private final String text;
        private final String sourceKey;
        private final int position;
        private final double score;
        
        public RetrievalResult(String id, String text, String sourceKey, int position, double score) {
            this.id = id;
            this.text = text;
            this.sourceKey = sourceKey;
            this.position = position;
            this.score = score;
        }
        
        public String getId() { return id; }
        public String getText() { return text; }
        public String getSourceKey() { return sourceKey; }
        public int getPosition() { return position; }
        public double getScore() { return score; }
        
        public Map<String, Object> toCitation() {
            Map<String, Object> citation = new HashMap<>();
            citation.put("id", id);
            citation.put("score", score);
            return citation;
        }
    }
}