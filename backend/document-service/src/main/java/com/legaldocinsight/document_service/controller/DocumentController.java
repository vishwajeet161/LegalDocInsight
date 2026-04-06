package com.legaldocinsight.document_service.controller;

import com.legaldocinsight.document_service.dto.DocumentResponse;
import com.legaldocinsight.document_service.dto.DocumentUploadResponse;
import com.legaldocinsight.document_service.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentUploadResponse> uploadDocument(
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "anonymous") String userId,
            @RequestHeader(value = "X-User-Role", required = false, defaultValue = "GUEST") String userRole) {
        log.info("Upload request: file={} userId={}", file.getOriginalFilename(), userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(documentService.uploadDocument(file, userId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentResponse> getDocument(
            @PathVariable String id,
            @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(documentService.getDocument(id, userId));
    }

    @GetMapping("/{id}/text")
    public ResponseEntity<DocumentResponse> getDocumentWithText(
            @PathVariable String id,
            @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(documentService.getDocumentWithText(id, userId));
    }

    @GetMapping
    public ResponseEntity<List<DocumentResponse>> getUserDocuments(
            @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(documentService.getUserDocuments(userId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable String id,
            @RequestHeader("X-User-Id") String userId) {
        documentService.deleteDocument(id, userId);
        return ResponseEntity.noContent().build();
    }
}




















// package com.legaldocinsight.document_service.controller;

// import com.legaldocinsight.document_service.service.DocumentTextExtractor;
// import org.springframework.http.ResponseEntity;
// import org.springframework.web.bind.annotation.*;
// import org.springframework.web.multipart.MultipartFile;
// import com.legaldocinsight.document_service.client.AnalysisServiceClient;

// import java.nio.file.Files;
// import java.nio.file.Path;
// import java.util.Map;
// import java.util.UUID;

// @RestController
// @RequestMapping("/api/documents")
// public class DocumentUploadController {

//     private static final String UPLOAD_DIR = "/tmp/legaldocinsight";

//     private final DocumentTextExtractor extractor;

//     private final AnalysisServiceClient analysisClient;

//     public DocumentUploadController(DocumentTextExtractor extractor, AnalysisServiceClient analysisClient) {
//         this.extractor = extractor;
//         this.analysisClient = analysisClient;
//     }

//     @PostMapping("/upload")
//     public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) throws Exception {

//         Files.createDirectories(Path.of(UPLOAD_DIR));

//         String docId = UUID.randomUUID().toString();

//         Path path = Path.of(UPLOAD_DIR,
//                 docId + "-" + file.getOriginalFilename());

//         file.transferTo(path);

//         // 🔥 Extract text
//         String extractedText = extractor.extractText(path.toFile());

//         System.out.println("===== EXTRACTED TEXT =====");
//         System.out.println(extractedText);
        
//         // analysisClient.sendForAnalysis(docId, extractedText);

//         return ResponseEntity.ok(
//                 Map.of(
//                         "documentId", docId,
//                         "textLength", extractedText.length()
//                 )
//         );
//     }
// }