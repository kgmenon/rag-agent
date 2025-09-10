package com.rag.agent.upload;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.rag.agent.util.Env;
import com.rag.agent.util.JsonUtil;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class UploadInitiateFunction implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final S3Client s3Client;
    private final String bucketName;
    
    public UploadInitiateFunction() {
        Region region = Region.of(Env.getOrDefault("AWS_REGION", "us-east-1"));
        this.s3Client = S3Client.builder().region(region).build();
        this.bucketName = Env.require("UPLOAD_BUCKET");
    }
    
    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            JsonNode requestBody = JsonUtil.parseJson(input.getBody());
            String fileName = requestBody.get("fileName").asText();
            String contentType = requestBody.has("contentType") ? 
                requestBody.get("contentType").asText() : "application/octet-stream";
            
            String key = UUID.randomUUID().toString() + "/" + fileName;
            
            CreateMultipartUploadRequest createRequest = CreateMultipartUploadRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(contentType)
                    .build();
            
            CreateMultipartUploadResponse response = s3Client.createMultipartUpload(createRequest);
            
            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("uploadId", response.uploadId());
            responseBody.put("key", key);
            responseBody.put("bucket", bucketName);
            
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(getCorsHeaders())
                    .withBody(JsonUtil.toJson(responseBody));
                    
        } catch (Exception e) {
            context.getLogger().log("Error initiating upload: " + e.getMessage());
            
            Map<String, String> errorBody = Map.of("error", "Failed to initiate upload");
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withHeaders(getCorsHeaders())
                    .withBody(JsonUtil.toJson(errorBody));
        }
    }
    
    private Map<String, String> getCorsHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "POST, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type");
        return headers;
    }
}