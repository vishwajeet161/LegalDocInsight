# Extraction and Durable Document Status Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan. The user has not requested subagents. Steps use checkbox syntax for tracking.

**Goal:** Make extraction and its token-protected status durable and bounded, including scanner failure and restart recovery.

**Architecture:** Preserve the existing services. PostgreSQL is the durable job source for a single document-service instance; an isolated, resource-limited child parses only after a private scanner reports clean. Persist and expose safe status separately from extracted text.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL, Apache Tika, a local scanner daemon, Angular 21 and Maven wrappers.

**Spec:** `docs/features/02-extraction-durable-status.md`.

## Global constraints

- Honor the approved specification, including single-instance recovery, resource limits, fail-closed public-profile scanning and `EXTRACTING`/`EXTRACTED` statuses.
- Do not expose tokens, extracted text, raw errors, paths or sensitive filenames in logs or events; do not change existing service boundaries or replace Kafka.
- Use synthetic fixtures and verify negative cases, authorization and recovery; do not represent local-only checks as public deployment verification.

## Review focus

- Invalid or expired token on status/text: deny without revealing content (Task 1).
- Worker completes after deletion or is scheduled twice: no resurrection or duplicate claim (Task 2).
- Scanner unavailable, times out or detects malware: no parse; persist safe failure (Task 3).
- Expanding archive, oversized parser output or hung child: terminate within enforced bounds and persist safe failure (Task 4).
- Restart between commit, claim and terminal update: all accepted jobs remain recoverable (Task 2 and Task 5).

## Task 1: Status contract and frontend client

**Files:** `backend/document-service/src/main/java/com/legaldocinsight/document_service/dto/DocumentResponse.java`, `service/impl/DocumentServiceImpl.java`, existing and new document access tests; `frontend/src/app/services/analysis.ts`, `analysis.spec.ts`.

- [x] Add tests that a token holder sees `EXTRACTING` for a stored `PROCESSING` record, `EXTRACTED` after success and no text in a status response; wrong or absent tokens are denied. Run the document-service wrapper and confirm the new assertions fail for the missing mapping.
- [ ] Map stored `PROCESSING` to the public status `EXTRACTING`, preserving other values and the upload response. Add safe error-code support as lifecycle failures are implemented; retain token checks. **Progress:** Status mapping and generic safe error message implemented; stable error codes remain.
- [x] Add an Angular HTTP test that `getDocumentStatus(id: string, token: string)` sends `X-Document-Token` in a header, not a URL, and returns a typed status. Run it and observe the expected missing behavior before implementation.
- [x] Implement the typed client and rerun the relevant document-service and frontend tests.

## Task 2: Durable lifecycle and admission

**Files:** `entity/Document.java`, `repository/DocumentRepository.java`, `service/impl/ExtractionOrchestrator.java`, upload/controller configuration and lifecycle integration tests.

- [ ] Test committed in-progress visibility with a blocked extractor, atomic single claim, bounded admission returning `503` with `Retry-After`, queue rejection without lost work, and guarded completion after delete.
- [ ] Implement database-backed pending work and short claim/result transactions; retain the approved single-instance assumption. Reclaim interrupted jobs on startup only after the previous instance and its child processes have stopped. Keep a bounded executor and atomic capacity admission.
- [ ] Test restart with pending and interrupted jobs, terminal persistence and simultaneous claim attempts against PostgreSQL; rerun document-service tests.

## Task 3: Private scanner

**Files:** document-service scanner interface/adapter and configuration, scanner tests, approved deployment configuration.

- [ ] Test clean, malware-positive, unavailable and timed-out scanner responses and assert no parsing follows a non-clean outcome; error codes and logs must be safe. **Progress:** The standalone ClamAV client has controlled TCP-daemon tests; integration with extraction remains.
- [ ] Implement a bounded local scanner protocol and private daemon configuration with current signatures; fail closed in the public profile. Document explicit development behavior and ensure the daemon is not publicly exposed.
- [ ] Run scanner tests and an integration check with the actual daemon, including outage and timeout.

## Task 4: Isolated, bounded parser

**Files:** document-service parser entry point, process runner, Tika dependency/configuration, extraction tests, synthetic fixtures and deployment configuration.

- [ ] Identify and document a maintained compatible Tika version and a supported OS mechanism enforcing per-child memory, CPU and process limits; the public profile must refuse to parse when enforcement is unavailable.
- [ ] Test text PDF/DOCX, blank/scanned/encrypted PDF, malformed DOCX, archive expansion/entry caps, unknown page count, output limit, crash and timeout; prove timeouts terminate and reap the child.
- [ ] Implement the parser child and bounded output protocol; reject blank text and unsafe input, never return raw exceptions, and persist text/counts only on success.
- [ ] Verify the actual resource boundary and run the document-service tests.

## Task 5: End-to-end and documentation

**Files:** `docs/features/02-extraction-durable-status.md`, release plan, deployment/readme notes and frontend tests.

- [ ] Run `./mvnw test` in document-service and `npm test -- --watch=false` and `npm run build` in frontend; record actual commands, test counts, failures and environmental blockers.
- [ ] Run a synthetic PDF and DOCX through gateway with real scanner and isolated parser; test authorized/unauthorized polling and restart during queued/in-progress work. Record exact commands and outcomes.
- [ ] Update the specification, plan and relevant deployment/API documentation with implemented behavior, checks and any remaining limitations. Mark Feature 2 complete only if all acceptance criteria are observed.
