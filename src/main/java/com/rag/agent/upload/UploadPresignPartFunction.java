package com.rag.agent.upload;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.rag.agent.util.Env;
import com.rag.agent.util.JsonUtil;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class UploadPresignPartFunction implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final S3Presigner s3Presigner;
    private final String bucketName;
    
    public UploadPresignPartFunction() {
        Region region = Region.of(Env.getOrDefault("AWS_REGION", "us-east-1"));
        this.s3Presigner = S3PresignerProvider.getPresigner(region);
        this.bucketName = Env.require("UPLOAD_BUCKET");
    }
    
    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            String bucket = input.getQueryStringParameters().get("bucket");
            String key = input.getQueryStringParameters().get("key");
            String uploadId = input.getQueryStringParameters().get("uploadId");
            int partNumber = Integer.parseInt(input.getQueryStringParameters().get("partNumber"));
            
            if (!bucketName.equals(bucket)) {
                throw new IllegalArgumentException("Invalid bucket name");
            }
            
            UploadPartRequest uploadPartRequest = UploadPartRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .partNumber(partNumber)
                    .build();
            
            UploadPartPresignRequest presignRequest = UploadPartPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(15))
                    .uploadPartRequest(uploadPartRequest)
                    .build();
            
            PresignedUploadPartRequest presignedRequest = s3Presigner.presignUploadPart(presignRequest);
            
            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("presignedUrl", presignedRequest.url().toString());
            responseBody.put("partNumber", partNumber);
            
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(getCorsHeaders())
                    .withBody(JsonUtil.toJson(responseBody));
                    
        } catch (Exception e) {
            context.getLogger().log("Error creating presigned URL: " + e.getMessage());
            
            Map<String, String> errorBody = Map.of("error", "Failed to create presigned URL");
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withHeaders(getCorsHeaders())
                    .withBody(JsonUtil.toJson(errorBody));
        }
    }
    
    private Map<String, String> getCorsHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type");
        return headers;
    }
}