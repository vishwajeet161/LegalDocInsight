package com.legaldocinsight.document_service.service;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.web.multipart.MultipartFile;

public interface StorageService {

    String generateUploadUrl(
        String objectKey,
        String contentType
    );

    boolean exists(String objectKey);

    InputStream download(String objectKey);

    void upload(
        String objectKey,
        InputStream content,
        String contentType
    );

    void delete(String objectKey) throws IOException;
    String store(MultipartFile file, String documentId) throws IOException;
    InputStream retrieve(String filePath) throws IOException;
    // void delete(String filePath) throws IOException;
}
