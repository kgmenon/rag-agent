package com.rag.agent.upload;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

public class S3PresignerProvider {
    private static S3Presigner presigner;
    
    public static synchronized S3Presigner getPresigner(Region region) {
        if (presigner == null) {
            presigner = S3Presigner.builder()
                    .region(region)
                    .build();
        }
        return presigner;
    }
}