package com.legaldocinsight.document_service.service.impl;

@Service
public class S3StorageService implements StorageService {

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
    public String generatePresignedUploadUrl(
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