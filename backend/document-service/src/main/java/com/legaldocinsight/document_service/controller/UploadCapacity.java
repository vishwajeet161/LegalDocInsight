package com.legaldocinsight.document_service.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.concurrent.Semaphore;

@Component
final class UploadCapacity {
    private final Semaphore permits;

    UploadCapacity(@Value("${document.upload.max-concurrent:4}") int limit) {
        if (limit < 1) throw new IllegalArgumentException("Upload capacity must be positive");
        this.permits = new Semaphore(limit);
    }

    boolean tryAcquire() {
        return permits.tryAcquire();
    }

    void release() {
        permits.release();
    }
}
