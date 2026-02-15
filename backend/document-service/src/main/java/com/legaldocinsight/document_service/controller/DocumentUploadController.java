package com.legaldocinsight.document_service.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@RestController
@RequestMapping("/documents")
public class DocumentUploadController {

    private static final String UPLOAD_DIR =
        System.getProperty("user.home") +
        "/Desktop/LegalDocInsight/storage/documents";


    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) throws Exception {

        Files.createDirectories(Path.of(UPLOAD_DIR));

        String docId = UUID.randomUUID().toString();
        Path path = Path.of(UPLOAD_DIR, docId + "-" + file.getOriginalFilename());

        file.transferTo(path);

        return ResponseEntity.ok(
        java.util.Map.of("documentId", docId));
    }
}
