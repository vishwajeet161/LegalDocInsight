package com.legaldocinsight.document_service.service.impl;

import com.legaldocinsight.document_service.service.StorageService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;

@Slf4j
@Service
public class LocalStorageService implements StorageService {

    @Value("${storage.local.upload-dir}")
    private String uploadDir;

    @PostConstruct
    public void init() throws IOException {
        Path uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
            log.info("Created upload directory: {}", uploadPath.toAbsolutePath());
        }
    }

    @Override
    public String store(MultipartFile file, String documentId) throws IOException {
        String extension = getExtension(file.getOriginalFilename());
        String storedFileName = documentId + extension;
        Path destination = Paths.get(uploadDir).resolve(storedFileName);

        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, destination, StandardCopyOption.REPLACE_EXISTING);
        }

        log.debug("Stored file: {} -> {}", file.getOriginalFilename(), destination.toAbsolutePath());
        return destination.toString();
    }

    @Override
    public InputStream retrieve(String filePath) throws IOException {
        return Files.newInputStream(Paths.get(filePath));
    }

    @Override
    public void delete(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        if (Files.exists(path)) {
            Files.delete(path);
            log.debug("Deleted file: {}", filePath);
        }
    }

    private String getExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return ".bin";
        return fileName.substring(fileName.lastIndexOf("."));
    }
}
