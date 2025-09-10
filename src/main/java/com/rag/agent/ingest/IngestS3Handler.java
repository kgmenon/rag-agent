package com.rag.agent.ingest;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import com.rag.agent.util.Env;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class IngestS3Handler implements RequestHandler<S3Event, String> {
    private final S3Client s3Client;
    private final OpenSearchClient openSearchClient;
    private final TextExtractor textExtractor;
    private final TextSplitter textSplitter;
    private final EmbeddingsClient embeddingsClient;
    private final String indexName;
    private final Region region;
    
    public IngestS3Handler() {
        this.region = Region.of(Env.getOrDefault("AWS_REGION", "us-east-1"));
        this.s3Client = S3Client.builder().region(region).build();
        this.openSearchClient = OpenSearchClientProvider.getClient(
            Env.require("OPENSEARCH_ENDPOINT"), region);
        this.textExtractor = new TextExtractor();
        this.textSplitter = new TextSplitter();
        this.embeddingsClient = new EmbeddingsClient(region);
        this.indexName = Env.getOrDefault("OPENSEARCH_INDEX", "documents");
    }
    
    @Override
    public String handleRequest(S3Event event, Context context) {
        try {
            ensureIndexExists();
            
            for (S3EventNotification.S3EventNotificationRecord record : event.getRecords()) {
                String bucketName = record.getS3().getBucket().getName();
                String objectKey = record.getS3().getObject().getKey();
                
                context.getLogger().log("Processing document: " + bucketName + "/" + objectKey);
                
                processDocument(bucketName, objectKey, context);
            }
            
            return "Successfully processed " + event.getRecords().size() + " documents";
        } catch (Exception e) {
            context.getLogger().log("Error processing documents: " + e.getMessage());
            throw new RuntimeException("Failed to process documents", e);
        }
    }
    
    private void processDocument(String bucketName, String objectKey, Context context) throws Exception {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .build();
        
        try (InputStream inputStream = s3Client.getObject(getObjectRequest)) {
            String contentType = s3Client.headObject(builder -> builder.bucket(bucketName).key(objectKey))
                    .contentType();
            
            String text = textExtractor.extractText(inputStream, contentType);
            context.getLogger().log("Extracted text length: " + text.length());
            
            List<String> chunks = textSplitter.split(text);
            context.getLogger().log("Created " + chunks.size() + " chunks");
            
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                float[] embedding = embeddingsClient.getEmbedding(chunk);
                
                Map<String, Object> document = new HashMap<>();
                document.put("id", UUID.randomUUID().toString());
                document.put("text", chunk);
                document.put("sourceKey", objectKey);
                document.put("position", i);
                document.put("embedding", embedding);
                
                IndexRequest<Map<String, Object>> indexRequest = IndexRequest.of(builder -> builder
                        .index(indexName)
                        .id(document.get("id").toString())
                        .document(document)
                );
                
                openSearchClient.index(indexRequest);
                
                if ((i + 1) % 10 == 0) {
                    context.getLogger().log("Indexed " + (i + 1) + "/" + chunks.size() + " chunks");
                }
            }
        }
    }
    
    private void ensureIndexExists() throws Exception {
        ExistsRequest existsRequest = ExistsRequest.of(builder -> builder.index(indexName));
        
        if (!openSearchClient.indices().exists(existsRequest).value()) {
            CreateIndexRequest createIndexRequest = CreateIndexRequest.of(builder -> builder
                    .index(indexName)
                    .mappings(mappingBuilder -> mappingBuilder
                        .properties("id", propertyBuilder -> propertyBuilder
                            .keyword(keywordBuilder -> keywordBuilder))
                        .properties("text", propertyBuilder -> propertyBuilder
                            .text(textBuilder -> textBuilder))
                        .properties("sourceKey", propertyBuilder -> propertyBuilder
                            .keyword(keywordBuilder -> keywordBuilder))
                        .properties("position", propertyBuilder -> propertyBuilder
                            .integer(integerBuilder -> integerBuilder))
                        .properties("embedding", propertyBuilder -> propertyBuilder
                            .knnVector(knnBuilder -> knnBuilder
                                .dimension(1536)
                                .method(methodBuilder -> methodBuilder
                                    .name("hnsw")
                                    .spaceType("cosinesimil")
                                    .engine("nmslib"))))
                    )
            );
            
            openSearchClient.indices().create(createIndexRequest);
        }
    }
}