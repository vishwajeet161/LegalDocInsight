# LegalDocInsight First Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a deployed anonymous upload-to-LLM-report workflow for Indian commercial contracts, with private temporary report access and a path to accounts later.

**Architecture:** Keep document-service and analysis-service separate for this release. Gateway routes public APIs and applies per-IP plus global upload budgets; document-service owns upload, storage, extraction, anonymous access tokens, report retrieval and retryable handoff; analysis-service owns the model call and persisted report behind an internal service credential. One PostgreSQL instance and a local persistent file volume support a single-host deployment. No user table or login is created.

**Tech Stack:** Angular 21/Vitest; Java 21/Spring Boot/Maven wrappers; PostgreSQL; Apache Tika; configurable Ollama-compatible HTTP model endpoint with an open-weight model candidate; Docker Compose for a reproducible single-host demo.

**Spec:** `docs/FIRST_RELEASE_SCOPE.md` (review and approve before implementation).

## Global constraints

- Preserve the monorepo and existing service directories; do not merge services in this release.
- No Kafka broker, S3, Redis, vector database, Kubernetes or new application service for the first release.
- Treat uploaded legal text as private. Keep secrets and full document text out of logs, events and source control.
- Require an opaque upload access token for status, report and deletion; store only its hash and expiry, and never put it in a URL.
- Each feature ends with passing focused tests and an updated API/deployment note where relevant. Do not infer runtime success from compilation.
- Do not start application-code or architectural changes until the user approves this plan. The test paths below are planned files, not files already present.

## Review focus

- Empty or scanned PDF: extraction must fail clearly or report unsupported OCR; test in Feature 2.
- A restarted service during analysis: queued work must recover without a duplicate report; test in Feature 4.
- A missing, forged or expired access token (including a forged `X-User-Id`): document/report access must fail; test in Feature 1, Feature 3 and Feature 6.
- Model returns invalid JSON or fake citation IDs: report must not be marked complete; test in Feature 4 and Feature 5.
- Long contract exceeds model context: chunk/selection logic must record coverage and not silently ignore pages; test in Feature 4.
- A mismatched file signature, scanner detection/outage or expanding DOCX archive: reject before/while parsing without exhausting resources; test in Feature 1 and Feature 2.

---

## Feature 1 — Buildable baseline and anonymous private upload

**Files:** Modify document-service application/config, `Document.java`, `DocumentServiceImpl.java`, `StorageService` implementations, `DocumentController.java` and POM. Add a generated access-token hash/expiry to document persistence and a token verifier. In gateway, remove the unused auth proxy route and reject spoofed identity headers; add single-instance in-memory per-IP **and global** upload budgets with trusted-proxy IP resolution. Create `DocumentUploadIntegrationTest.java` and `DocumentAccessIntegrationTest.java` under document-service `src/test/`; create `GatewayRouteIntegrationTest.java` and `UploadRateLimitTest.java` under gateway `src/test/`.

**Contract:** Anonymous upload returns `202`, document ID, a separate high-entropy access token and expiry. Only its hash is persisted; the raw token is never logged. Status, text and delete endpoints require the token in a header. Incoming `X-User-Id` never grants access. Gateway atomically checks per-IP and global upload budgets and returns `429` plus `Retry-After` on either limit; status polling has a separate, less costly budget. Direct document-service access enforces its own size/concurrency limits. Original file and metadata survive restart. Configure one active storage implementation and one PostgreSQL instance; fix entity/compiler mismatches and align upload limits.

- [ ] Write tests for token creation/hash storage, missing/wrong/expired token, forged identity header, upload, extension/type/signature mismatch, empty/oversize upload, per-IP and global `429`/`Retry-After`, atomic budget checks, spoofed forwarded IP, polling isolation and persistence; run them to observe current failure.
- [ ] Make the smallest build/config/security changes that satisfy those tests; remove ambiguous storage wiring from the active profile.
- [ ] Run `./mvnw test` in document-service and gateway and record actual results; manually verify an upload through gateway.

**Exit:** Gateway and document-service build and start from documented config; upload persists and another visitor cannot read/delete it without its token. No user table is created.

## Feature 2 — Extraction and durable document status

**Files:** Modify `ExtractionOrchestrator.java`, `TikaExtractionService.java`, `Document.java`, `DocumentRepository.java`, document DTO/controller, parser configuration/dependency and frontend status client. Add a scanner adapter and a local scanner deployment/configuration for the internet-facing profile. Create `TikaExtractionServiceTest.java`, `UploadScanTest.java`, `ExtractionLifecycleIntegrationTest.java`, and safe PDF/DOCX/adversarial fixtures under document-service `src/test/resources/`.

**Contract:** Persist extracted text, page/character counts and status. Do not emit a success handoff on failure. Expose token-protected `GET /api/documents/{id}` and status data sufficient for frontend polling. Map `PENDING`/`PROCESSING` to the release status vocabulary without losing failure reason. Scan before parsing in the public deployment profile and fail closed on scanner detection, timeout or outage. Use a maintained Tika version and isolate parsing of untrusted files from the application process, with time, memory, archive expansion and output limits.

- [ ] Write tests for text PDF, DOCX, scanner-positive fixture, scanner timeout/outage, empty/scanned/encrypted PDF, malformed DOCX, archive expansion, parser timeout/output limit, failure status, status transitions and restart recovery; confirm they fail on current code.
- [ ] Implement persistence and extraction lifecycle with bounded, safe error messages.
- [ ] Run document-service tests and exercise status polling after a real fixture upload.

**Exit:** A document reaches an observable extracted or failed state; restart does not erase it.

## Feature 3 — Analysis API and report persistence

**Files:** Replace analysis stub controller; create analysis request/result DTOs, `AnalysisJob` and `AnalysisReport` entities/repositories, migrations/config and `AnalysisControllerIntegrationTest.java`, `AnalysisRepositoryTest.java` under analysis-service `src/test/`. Add a token-protected report endpoint and internal analysis client to document-service, with `DocumentReportAccessIntegrationTest.java`.

**Contract:** Internal `POST /internal/analyses` accepts document ID, content hash and bounded extracted text from an authenticated service caller; duplicate `(documentId, contentHash, analysisVersion)` requests return the existing job. Analysis-service exposes no public report endpoint. `GET /api/documents/{id}/report` first verifies the anonymous access token in document-service, then fetches the report internally; it returns `404` before creation and `202` while processing. Report schema includes summary, clauses, obligations, risks, evidence references, law observations, model ID and prompt version.

- [ ] Write failing API/repository tests for creation, duplicate submission, internal caller rejection and token-protected report retrieval.
- [ ] Implement storage/API using one PostgreSQL instance and analysis-owned tables; route public report access through document-service and make analysis endpoints internal only.
- [ ] Run `./mvnw test` in analysis-service and gateway; verify the API contract with a fake analyzer.

**Exit:** Analysis jobs/reports persist; only a holder of the document access token can fetch the report through document-service. No real model call is required yet.

## Feature 4 — LLM analysis and reliable handoff

**Files:** Create an analysis-service model-client interface and Ollama-compatible HTTP implementation, prompt/schema validator, chunk/coverage selector, worker and tests such as `ModelClientTest.java`, `ReportValidationTest.java`, `AnalysisWorkerIntegrationTest.java`. Modify document-service orchestration/client and add `AnalysisHandoffIntegrationTest.java`. Add config values for model URL/name/timeouts and prompt version.

**Contract:** After extraction, document-service submits bounded text to analysis-service over an authenticated internal channel. Record delivery state and retry transient failures; analysis is idempotent. A global queue cap and model-concurrency cap reject excess work with a safe, retryable response rather than exhaust the host. Analysis-service calls the configured model, validates structured output and document evidence IDs, persists report or safe failure, and records source coverage. No full text is put into Kafka. Model unavailability and malformed output are visible, retryable failures.

- [ ] Write failing tests for successful handoff, duplicate delivery, service restart, queue-full rejection, model concurrency cap, model timeout, malformed output, invalid citation and oversized input coverage.
- [ ] Implement the minimal direct handoff and model worker; benchmark the selected open-weight model with representative contracts before locking its deployment settings.
- [ ] Run both service test suites and an end-to-end fixture through a real model runtime; record time, memory and evaluation observations.

**Exit:** At least one real contract produces a saved, source-grounded report, and failures/replays remain recoverable.

## Feature 5 — Curated Indian-law support and evaluation

**Files:** Create a versioned primary-source law corpus under `backend/analysis-service/src/main/resources/law/` with provenance metadata, a small retriever/selector and tests such as `LawSourceRepositoryTest.java`, `LawCitationValidationTest.java`, `ReportEvaluationTest.java`; add anonymized/synthetic evaluation fixtures under `src/test/resources/evaluation/` and an evaluation report in `docs/`.

**Contract:** Law observations are optional. Each includes source title, section, URL, as-of/effective date and supporting passage. Reject unverifiable citations and avoid a law conclusion when source applicability is unclear. Evaluation scores factual grounding, citation accuracy, useful risk identification and abstention against reviewed expected findings; results determine whether the proposed model is adequate.

- [ ] Write failing tests for source lookup, valid/invalid law citations, applicability uncertainty and cited-passage validation.
- [ ] Implement the smallest curated corpus and retrieval path; run the evaluation set with the real model.
- [ ] Review incorrect reports and record quality limits before declaring the feature complete.

**Exit:** Report law claims are traceable to curated sources, and the evaluation result is documented. No separate law vector DB is required.

## Feature 6 — Angular report experience and anonymous access

**Files:** Modify `frontend/src/app/pages/upload/`, `frontend/src/app/services/analysis.ts` and routes; create report/status components and tests such as `document-api.spec.ts`, `analysis.spec.ts`, `upload.spec.ts`, `report.spec.ts`; add backend security integration cases for direct port access.

**Contract:** Anonymous visitors upload, see status and inspect a report with clickable document/law citations in the active browser tab. Keep the access token in session storage and send it in a header; explain that a lost token cannot be recovered. API base URL comes from deployment config/same origin. Do not display an uncited finding as established fact.

- [ ] Write failing client/component tests for upload, session token handling, polling, completed report, failed job, citation navigation and missing/expired-token responses; verify the token never enters URL or logs.
- [ ] Implement the UI/API integration and accessible loading/error states.
- [ ] Run `npm test` and `npm run build` in frontend; manually verify that a second browser session cannot access the report with only its document ID.

**Exit:** A visitor can complete the full workflow in one tab; a second visitor cannot view the report without its token.

## Feature 7 — Repeatable deployment and release checks

**Files:** Create deployment instructions and single-host Compose/config templates; add Dockerfiles for services missing them, health/readiness settings, retention cleanup, a deployment smoke-test script and `docs/RELEASE_1_VERIFICATION.md`. Update README with actual supported features and commands.

**Contract:** One documented command set brings up frontend, gateway, document-service, analysis-service, PostgreSQL, scanner and model runtime with persistent files/DB and environment-provided secrets. Only the public HTTP entry is exposed; backend ports and data services stay private. HTTPS, origin restrictions and security headers protect the browser flow. Cleanup expires documents, text, tokens and reports after the configured retention period. Public upload has per-IP and global rate limits plus bounded concurrency; the parser runs with an isolation/resource boundary. Health checks and logs expose failures without leaking legal text. No real user documents are part of fixtures.

- [ ] Write smoke checks that fail when a service, DB, scanner, model endpoint or API route is unavailable; add tests for expired-data cleanup, IP/global rate limits, concurrent-job caps, direct-port isolation and safe rendering of hostile text; run them against the incomplete stack.
- [ ] Add minimal packaging/config and verify clean-start plus restart recovery on the chosen host.
- [ ] Run backend tests, frontend tests/build and smoke checks; record commands, outputs, known limits and a demo script.

**Exit:** Another engineer can deploy and demonstrate a real PDF/DOCX report from the documented steps.

## Release gate

Review `docs/FIRST_RELEASE_SCOPE.md` and this plan before implementation. Each feature is a separate review point. The release is complete only when the acceptance checks in the scope document are observed in the deployed environment. Chat, OCR, Kafka, S3 and vector search remain later decisions based on evidence.
