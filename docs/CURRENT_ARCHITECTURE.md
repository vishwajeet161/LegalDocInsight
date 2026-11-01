# Current architecture (repository inspection, 2026-11-01)

## Scope and evidence

This describes checked-in code, not a running deployment. The requested `docs/original-hld.png` is absent; the only diagram is `docs/NEW_LegalDoc_HLD.png`. The root `README.md` is another statement of intent, not evidence of implemented features. No local `application.properties` is checked in (it is ignored), so an untracked deployment may differ. Maven is unavailable in this environment; runtime behavior below is inferred from code and configuration, not confirmed by an end-to-end run.

## Repository and processes

```text
frontend/                 Angular 21 landing and upload pages
backend/pom.xml           Maven aggregator only; no common parent configuration
backend/api-gateway/      Spring Boot 3.2.5, reactive gateway, port 8080
backend/document-service/ Spring Boot 3.5.13, MVC/JPA/Tika, example port 8081
backend/analysis-service/ Spring Boot 3.5.13, MVC stub, example port 8082
docs/                     HLD diagram and this inspection
```

The three backend directories each declare their own Spring Boot parent and application entry point. The root POM lists modules but provides neither shared dependency management nor runnable application. Gateway routes in `application.yml` point at fixed URLs for document, analysis and a nonexistent chat service. No service discovery exists. Only the gateway has a Dockerfile; no Compose file or deployment manifest exists.

| Project | Checked-in responsibility and APIs | Calls / owned state | Independent runnable status |
| --- | --- | --- | --- |
| API gateway | Routes `/api/documents/**`, `/api/analysis/**`, `/api/chat/**`, `/api/auth/**`; correlation/logging filters, JWT filter, circuit-breaker fallbacks, CORS. It has no application business endpoint. | HTTP proxy to ports 8081, 8082, 8083; no database. | A standalone application in principle, but auth is incomplete and downstream paths do not all match. |
| Document service | `POST /api/documents/upload`, `GET /api/documents`, `GET /api/documents/{id}`, `GET /api/documents/{id}/text`, `DELETE /api/documents/{id}`. Stores document metadata, files and extracted text, then publishes an event. | JPA `DocumentRepository`; local disk storage implementation; S3 configuration/unfinished implementation; Kafka producer. No active HTTP client to analysis. | **Not buildable as checked in.** Missing import for `S3Properties` in application class; `Document` lacks methods/fields used by service (`get/setExtractedText`, `get/setPageCount`, `get/setCharacterCount`); `S3StorageService` lacks imports and interface methods; local and S3 storage beans are ambiguous. Required property keys also lack checked-in values. |
| Analysis service | `POST /analysis/analyze` reads `documentId` and `text`, logs lengths, returns literal `Analysis started`. | No repository, entity, Kafka consumer, storage client, analysis engine or result persistence. JPA/PostgreSQL/H2 dependencies are unused. | Small standalone app in principle; example H2 config is not active. Gateway routes `/api/analysis/**` without rewriting to `/analysis/**`, so this endpoint is not reached through the checked-in gateway route. |

## Actual document and analysis flow

1. Angular `Upload` accepts PDF/DOCX and `AnalysisService` posts multipart `file` directly to hard-coded `http://98.80.201.80:8080/api/documents/upload`. It does not show a result or poll status; it stores the returned ID in a component field.
2. Gateway matches `/api/documents/**` and proxies to document service without path rewrite. This path matches `DocumentController`. The route is marked `requiresAuth: false`.
3. Controller uses optional `X-User-Id` with default `anonymous`. `DocumentServiceImpl` validates client-reported MIME type and 50 MB size, creates a `PENDING` row, stores the file, and schedules extraction after transaction commit.
4. Intended active storage is `LocalStorageService`, using `storage.local.upload-dir`, but both it and unfinished `S3StorageService` are `@Service` implementations of the same interface. No qualifier selects one. `S3Config` creates SDK clients and presigner, but no working S3 path is connected.
5. `ExtractionOrchestrator` runs in a local thread pool, loads the file and calls Apache Tika. It sets `EXTRACTED` or `FAILED` and attempts to publish `DocumentExtractedEvent` through Kafka. There is no Kafka consumer anywhere in this repository. It attempts to publish even when extraction fails. Publication is asynchronous and failure is only logged.
6. There is no active call from document service to analysis service. A prior HTTP client is only mentioned in commented-out controller code. No analysis record, result endpoint or frontend result display exists. The analysis endpoint does not analyze its input.

Thus the implemented path ends at extraction/event publication, subject to the build and configuration blockers above. No analysis result is returned to Angular.

## Actual chat/RAG flow

There is no Angular chat page or API client. Gateway has a `/api/chat/**` route to port 8083 and a fallback, but no chat-service directory/application exists. There is no document retriever, Indian-law corpus, vector integration, embedding model/provider, LLM client, prompt builder, citations, or conversation persistence. No chat request can complete as designed.

## Infrastructure inventory

| Dependency | Actual state |
| --- | --- |
| PostgreSQL | JDBC dependency in document and analysis POMs, plus `document-service/src/main/resources/db-setup.sql`. No active checked-in datasource config or confirmed running database. Script schema differs from entity text field and is not an applied migration. |
| H2 | Analysis POM has runtime H2 and example in-memory config. Document example also names H2, but document POM does not include its driver. Example files are not loaded by Spring automatically. |
| Kafka | Document producer and event class only. Requires `spring.kafka.bootstrap-servers` and `kafka.topics.document-extracted`; no checked-in values, topic provisioner, consumer or broker deployment. Not needed for any completed cross-service workflow today. |
| Redis | No code or dependency. |
| S3 | SDK/config and incomplete storage class. No usable S3 upload or retrieval; no bucket configuration checked in. |
| Local disk | `LocalStorageService` stores originals under configured `storage.local.upload-dir`; no checked-in value or volume deployment. |
| Vector database / Indian-law vector database | No code, dependency, schema, corpus or provisioning. |
| LLM / embedding providers | No client, model, keys or invocation. |
| Service discovery | None; fixed gateway URL properties. |
| Authentication | Gateway JWT parsing/filter exists; no token issuer or user service. Document service trusts request headers. Document and analysis routes are public in gateway metadata. |
| External APIs | None called by functioning application code. AWS SDK is configured but storage implementation is incomplete. |

## Verified code-level limitations and security

- Gateway security chain permits all exchanges and relies on a custom global JWT filter. Document and analysis routes explicitly bypass it. `/api/auth/**` routes to document service, which has no auth controller.
- Document ownership depends on client-supplied `X-User-Id`; direct access to port 8081 permits impersonation. The public gateway route also forwards a supplied header. Upload defaults to shared `anonymous`, while list/get/delete require a header.
- Default gateway `jwt.secret` is short for JJWT HMAC key requirements and no issuer/registration path exists. Chat is marked protected, so even if a chat service appeared, token acquisition is absent.
- Gateway YAML and `CorsConfig` both configure CORS for localhost:4200; frontend uses a remote HTTP IP. Browser origin and mixed-content behavior will depend on actual hosting and are not configured here.
- `application-example.properties` gateway paths (`/documents/**`, `/analysis/**`) conflict with active YAML (`/api/...`). The analysis controller itself uses `/analysis`, so the active gateway path mismatches it.
- The document service's Java 50 MB limit conflicts with its example multipart 20 MB limit. Its error handler says 50 MB. The actual limit depends on untracked config.
- `Document` does not model extracted text/page/character data used by the code; the SQL file names `extracted_text_file_path` instead. File write and DB transaction are not atomic, so rollback can leave an orphan file.
- Tika supports parsing text-based files, but no OCR integration is present for scanned images/PDFs. MIME is trusted from upload metadata; there is no content signature validation.
- No backend test sources exist. Frontend tests are component/service creation smoke tests; they do not exercise upload, routing, extraction, security or analysis.

## Deployment complexity today

The repository describes three Java processes, Angular hosting, a relational database, local persistent storage, and Kafka for the proposed event path. S3 is partially wired and would add configuration if retained. Only gateway has a Dockerfile. Because document service does not compile and configuration is incomplete, no reproducible full-stack deployment is present. Running separate analysis and gateway processes currently adds deployment units without delivering a completed analysis or chat workflow.
