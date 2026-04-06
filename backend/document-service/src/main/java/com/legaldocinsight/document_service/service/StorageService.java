package com.legaldocinsight.document_service.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

public interface StorageService {
    String store(MultipartFile file, String documentId) throws IOException;
    InputStream retrieve(String filePath) throws IOException;
    void delete(String filePath) throws IOException;
}
