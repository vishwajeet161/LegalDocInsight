package com.legaldocinsight.document_service.controller;

import com.legaldocinsight.document_service.service.DocumentTextExtractor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.legaldocinsight.document_service.client.AnalysisServiceClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/documents")
public class DocumentUploadController {

    private static final String UPLOAD_DIR = "/tmp/legaldocinsight";

    private final DocumentTextExtractor extractor;

    private final AnalysisServiceClient analysisClient;

    public DocumentUploadController(DocumentTextExtractor extractor, AnalysisServiceClient analysisClient) {
        this.extractor = extractor;
        this.analysisClient = analysisClient;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) throws Exception {

        Files.createDirectories(Path.of(UPLOAD_DIR));

        String docId = UUID.randomUUID().toString();

        Path path = Path.of(UPLOAD_DIR,
                docId + "-" + file.getOriginalFilename());

        file.transferTo(path);

        // 🔥 Extract text
        String extractedText = extractor.extractText(path.toFile());

        System.out.println("===== EXTRACTED TEXT =====");
        System.out.println(extractedText);
        
        analysisClient.sendForAnalysis(docId, extractedText);

        return ResponseEntity.ok(
                Map.of(
                        "documentId", docId,
                        "textLength", extractedText.length()
                )
        );
    }
}