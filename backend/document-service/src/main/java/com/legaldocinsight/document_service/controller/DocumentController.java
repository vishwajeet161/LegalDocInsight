package com.legaldocinsight.document_service.controller;

import com.legaldocinsight.document_service.dto.DocumentResponse;
import com.legaldocinsight.document_service.dto.DocumentUploadResponse;
import com.legaldocinsight.document_service.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final UploadCapacity uploadCapacity;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentUploadResponse> uploadDocument(
            @RequestPart("file") MultipartFile file) {
        log.info("Upload request received");
        if (!uploadCapacity.tryAcquire()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5").build();
        }
        try {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(documentService.uploadDocument(file, null));
        } finally {
            uploadCapacity.release();
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentResponse> getDocument(
            @PathVariable String id,
            @RequestHeader(value = "X-Document-Token", required = false) String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(documentService.getDocument(id, token));
    }

    @GetMapping("/{id}/text")
    public ResponseEntity<DocumentResponse> getDocumentWithText(
            @PathVariable String id,
            @RequestHeader(value = "X-Document-Token", required = false) String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(documentService.getDocumentWithText(id, token));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable String id,
            @RequestHeader(value = "X-Document-Token", required = false) String token) {
        documentService.deleteDocument(id, token);
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
