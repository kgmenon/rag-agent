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
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UploadCompleteFunction implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final S3Client s3Client;
    private final String bucketName;
    
    public UploadCompleteFunction() {
        Region region = Region.of(Env.getOrDefault("AWS_REGION", "us-east-1"));
        this.s3Client = S3Client.builder().region(region).build();
        this.bucketName = Env.require("UPLOAD_BUCKET");
    }
    
    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            JsonNode requestBody = JsonUtil.parseJson(input.getBody());
            String bucket = requestBody.get("bucket").asText();
            String key = requestBody.get("key").asText();
            String uploadId = requestBody.get("uploadId").asText();
            JsonNode partsNode = requestBody.get("parts");
            
            if (!bucketName.equals(bucket)) {
                throw new IllegalArgumentException("Invalid bucket name");
            }
            
            List<CompletedPart> completedParts = new ArrayList<>();
            for (JsonNode partNode : partsNode) {
                CompletedPart part = CompletedPart.builder()
                        .partNumber(partNode.get("PartNumber").asInt())
                        .eTag(partNode.get("ETag").asText())
                        .build();
                completedParts.add(part);
            }
            
            CompletedMultipartUpload completedUpload = CompletedMultipartUpload.builder()
                    .parts(completedParts)
                    .build();
            
            CompleteMultipartUploadRequest completeRequest = CompleteMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(completedUpload)
                    .build();
            
            CompleteMultipartUploadResponse response = s3Client.completeMultipartUpload(completeRequest);
            
            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("location", response.location());
            responseBody.put("bucket", response.bucket());
            responseBody.put("key", response.key());
            responseBody.put("etag", response.eTag());
            
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(getCorsHeaders())
                    .withBody(JsonUtil.toJson(responseBody));
                    
        } catch (Exception e) {
            context.getLogger().log("Error completing upload: " + e.getMessage());
            
            Map<String, String> errorBody = Map.of("error", "Failed to complete upload");
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