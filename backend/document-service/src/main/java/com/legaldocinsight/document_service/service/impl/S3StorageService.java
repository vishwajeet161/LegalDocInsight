package com.legaldocinsight.document_service.service.impl;

import com.legaldocinsight.document_service.config.S3Properties;
import com.legaldocinsight.document_service.service.StorageService;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

// The upload URL prototype is incomplete; it is not an active storage bean.
public abstract class S3StorageService implements StorageService {

    private final S3Presigner s3Presigner;
    private final S3Properties properties;

    public S3StorageService(
            S3Presigner s3Presigner,
            S3Properties properties
    ) {
        this.s3Presigner = s3Presigner;
        this.properties = properties;
    }

    @Override
    public String generateUploadUrl(
            String objectKey,
            String contentType
    ) {
        PutObjectRequest putObjectRequest =
                PutObjectRequest.builder()
                        .bucket(properties.getBucket())
                        .key(objectKey)
                        .contentType(contentType)
                        .build();

        PutObjectPresignRequest presignRequest =
                PutObjectPresignRequest.builder()
                        .signatureDuration(
                                properties.getPresignedUrlDuration()
                        )
                        .putObjectRequest(putObjectRequest)
                        .build();

        PresignedPutObjectRequest presignedRequest =
                s3Presigner.presignPutObject(presignRequest);

        return presignedRequest.url().toExternalForm();
    }
}
