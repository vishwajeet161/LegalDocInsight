package com.legaldocinsight.document_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "aws.s3")
public class S3Properties {

    private String bucket;
    private String region;
    private Duration presignedUrlDuration = Duration.ofMinutes(10);

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Duration getPresignedUrlDuration() {
        return presignedUrlDuration;
    }

    public void setPresignedUrlDuration(Duration presignedUrlDuration) {
        this.presignedUrlDuration = presignedUrlDuration;
    }
}