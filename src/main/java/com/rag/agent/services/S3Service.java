package com.rag.agent.services;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class S3Service {
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String uploadBucket;
    
    public S3Service() {
        this.s3Client = S3Client.builder()
            .region(Region.AP_SOUTHEAST_2)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
            
        this.s3Presigner = S3Presigner.builder()
            .region(Region.AP_SOUTHEAST_2)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
            
        String configuredBucket = System.getenv("UPLOAD_BUCKET");
        if (configuredBucket == null) {
            // Default to our Sydney region upload bucket for this POC
            this.uploadBucket = "rag-agent-poc-uploads-rad32rxj";
            System.out.println("Using default upload bucket for Sydney region: " + this.uploadBucket);
        } else {
            this.uploadBucket = configuredBucket;
            System.out.println("Using configured upload bucket: " + this.uploadBucket);
        }
        
        System.out.println("S3Service initialized for ap-southeast-2 Sydney region");
    }
    
    public CreateMultipartUploadResponse initiateMultipartUpload(String fileName, String contentType) {
        String key = "uploads/" + System.currentTimeMillis() + "/" + fileName;
        
        System.out.println("S3Service DEBUG: About to initiate multipart upload");
        System.out.println("S3Service DEBUG: Using bucket: " + uploadBucket);
        System.out.println("S3Service DEBUG: Using key: " + key);
        
        CreateMultipartUploadRequest request = CreateMultipartUploadRequest.builder()
            .bucket(uploadBucket)
            .key(key)
            .contentType(contentType)
            .build();
            
        try {
            CreateMultipartUploadResponse response = s3Client.createMultipartUpload(request);
            System.out.println("S3Service DEBUG: Got response from AWS SDK");
            System.out.println("S3Service DEBUG: Response bucket: " + response.bucket());
            System.out.println("S3Service DEBUG: Response key: " + response.key());
            System.out.println("S3Service DEBUG: Response uploadId: " + response.uploadId());
            return response;
        } catch (Exception e) {
            System.err.println("S3Service ERROR: Exception during multipart upload initiate: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
    
    public String generatePresignedUrlForPart(String bucket, String key, String uploadId, int partNumber) {
        UploadPartRequest uploadPartRequest = UploadPartRequest.builder()
            .bucket(bucket)
            .key(key)
            .uploadId(uploadId)
            .partNumber(partNumber)
            .build();
            
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(15))
            .putObjectRequest(PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build())
            .build();
            
        PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(presignRequest);
        return presignedRequest.url().toString() + "&partNumber=" + partNumber + "&uploadId=" + uploadId;
    }
    
    public CompleteMultipartUploadResponse completeMultipartUpload(String bucket, String key, 
            String uploadId, List<CompletedPart> parts) {
        CompletedMultipartUpload completedUpload = CompletedMultipartUpload.builder()
            .parts(parts)
            .build();
            
        CompleteMultipartUploadRequest completeRequest = CompleteMultipartUploadRequest.builder()
            .bucket(bucket)
            .key(key)
            .uploadId(uploadId)
            .multipartUpload(completedUpload)
            .build();
            
        return s3Client.completeMultipartUpload(completeRequest);
    }
    
    public InputStream getObjectContent(String bucket, String key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build();
            
        return s3Client.getObject(getObjectRequest);
    }
    
    public String getUploadBucket() {
        return uploadBucket;
    }
    
    public void close() {
        if (s3Client != null) {
            s3Client.close();
        }
        if (s3Presigner != null) {
            s3Presigner.close();
        }
    }
}