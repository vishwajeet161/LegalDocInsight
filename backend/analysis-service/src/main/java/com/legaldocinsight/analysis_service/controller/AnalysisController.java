package com.legaldocinsight.analysis_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal/analyses")
public class AnalysisController {
    private static final int MAX_TEXT_BYTES = 1_000_000;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final byte[] credential;
    private final int capacity;

    public AnalysisController(JdbcTemplate db, ObjectMapper json, @Value("${analysis.internal-token:}") String credential,
                              @Value("${analysis.queue-capacity:50}") int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Invalid analysis queue capacity");
        this.db = db;
        this.json = json;
        this.credential = credential.getBytes(StandardCharsets.UTF_8);
        this.capacity = capacity;
    }
    public record Request(String documentId, String contentHash, String analysisVersion, String text) { }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> invalidBody() { return ResponseEntity.badRequest().build(); }
    private boolean authorized(String supplied) {
        return credential.length >= 32 && supplied != null && MessageDigest.isEqual(credential, supplied.getBytes(StandardCharsets.UTF_8));
    }
    @PostMapping
    public synchronized ResponseEntity<?> submit(@RequestHeader(value="X-Internal-Token", required=false) String token, @RequestBody Request request) throws Exception {
        if (!authorized(token)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (request.documentId() == null || request.contentHash() == null || request.analysisVersion() == null || request.text() == null)
            return ResponseEntity.badRequest().build();
        try {
            if (!UUID.fromString(request.documentId()).toString().equals(request.documentId())) return ResponseEntity.badRequest().build();
        } catch (IllegalArgumentException ex) { return ResponseEntity.badRequest().build(); }
        byte[] bytes = request.text().getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_TEXT_BYTES || request.text().isBlank()
                || !request.analysisVersion().matches("[A-Za-z0-9._-]{1,64}")
                || !request.contentHash().matches("[0-9a-f]{64}")
                || !request.contentHash().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
            return ResponseEntity.badRequest().build();
        var existing = db.queryForList("SELECT id FROM analysis_jobs WHERE document_id=? AND content_hash=? AND analysis_version=?", String.class,
                request.documentId(), request.contentHash(), request.analysisVersion());
        if (!existing.isEmpty()) return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(Map.of("jobId", existing.get(0)));
        Long queued = db.queryForObject("SELECT count(*) FROM analysis_jobs WHERE status IN ('PENDING','PROCESSING')", Long.class);
        if (queued == null || queued >= capacity)
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5").cacheControl(CacheControl.noStore()).build();
        String id = UUID.randomUUID().toString();
        try {
            db.update("INSERT INTO analysis_jobs (id, document_id, content_hash, analysis_version, extracted_text, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    id, request.documentId(), request.contentHash(), request.analysisVersion(), request.text(), "PENDING", Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException ignored) {
            id = db.queryForObject("SELECT id FROM analysis_jobs WHERE document_id=? AND content_hash=? AND analysis_version=?", String.class,
                    request.documentId(), request.contentHash(), request.analysisVersion());
        }
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(Map.of("jobId", id));
    }
    @GetMapping("/documents/{documentId}/report")
    public ResponseEntity<?> report(@RequestHeader(value="X-Internal-Token", required=false) String token, @PathVariable String documentId) throws Exception {
        if (!authorized(token)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        var rows = db.queryForList("SELECT j.status, r.report_json FROM analysis_jobs j LEFT JOIN analysis_reports r ON r.job_id=j.id WHERE j.document_id=? ORDER BY j.created_at DESC, j.id DESC LIMIT 1", documentId);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        var row = rows.get(0);
        String state = (String) row.get("status");
        if ("PENDING".equals(state) || "PROCESSING".equals(state)) return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
        if (!"COMPLETED".equals(state) || row.get("report_json") == null) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(json.readTree((String) row.get("report_json")));
    }
}
