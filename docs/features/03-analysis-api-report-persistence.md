# Feature specification: Analysis API and report persistence

## Goal and status

- **Release feature:** 3 — Analysis API and report persistence
- **Status:** In progress
- **Goal:** Persist private analysis jobs and reports and allow a document token holder to retrieve a report through document-service.
- **Context:** [First release scope](../FIRST_RELEASE_SCOPE.md), [release plan](../superpowers/plans/2026-11-01-first-release.md), [Feature 2](02-extraction-durable-status.md).

## Scope

- **Included:** Authenticated direct HTTP analysis API, idempotent job creation, analysis-owned PostgreSQL tables, a test-only fake analyzer, and token-protected report retrieval via document-service.
- **Excluded:** Real model integration, automatic handoff and retries (Feature 4); report quality and citation validation (Features 4–5); UI, retention and deletion of analysis data (Features 6–7). No Kafka or new service.
- **Dependencies:** Existing anonymous document tokens and shared PostgreSQL instance. Feature 2 remains in progress, its release blockers remain open, and the public profile remains disabled.

## Current behavior

Analysis-service has an unauthenticated `/analysis/analyze` stub that logs a document identifier and text length, returns a string and persists nothing. Its example configuration uses in-memory H2. Document-service stores extracted text and verifies a hashed, expiring `X-Document-Token` before returning document data. It has no analysis client or report endpoint. The gateway has a public `/api/analysis/**` route. Extraction has no reliable direct HTTP handoff.

## Proposed behavior and interfaces

- `POST /internal/analyses` requires `X-Internal-Token` and JSON fields `documentId`, `contentHash`, `analysisVersion` and `text`. The ID must be a canonical UUID, the version uses 1–64 ASCII letters, digits, dots, underscores or hyphens, and the lowercase SHA-256 hash must match the UTF-8 text. The text limit is 1,000,000 UTF-8 bytes and the entire JSON request limit is 1,100,000 bytes. Malformed requests return `400`; oversized bodies return `413`. The database enforces uniqueness of `(document_id, content_hash, analysis_version)` even for concurrent requests; duplicate submissions return the existing `jobId` without replacing data or creating another report.
- A job has an identifier and durable state (`PENDING`, `PROCESSING`, `COMPLETED`, or `FAILED`). A report belongs to one job and contains a schema version, summary, clauses, obligations, risks, evidence references, law observations, model ID and prompt version. Completed job and report writes are atomic. The fake analyzer is enabled only in tests; production has no real model and does not fabricate completed reports. Processing and recovery for real inference are Feature 4.
- `GET /internal/analyses/documents/{documentId}/report` looks up the latest job by creation timestamp and UUID tie-breaker and returns `404` if none exists, `202` for pending/processing, a report for completed work, and `503` for failed or inconsistent work. Internal endpoints reject missing or incorrect credentials and expose no document text in errors or logs.
- `GET /api/documents/{id}/report` checks the existing token and expiry before contacting analysis-service. It propagates the internal report's `404`, `202` or successful result with `Cache-Control: no-store`; an unavailable or invalid upstream response produces a safe `503`. Missing, wrong or expired tokens receive the existing denial behavior, and `X-User-Id` grants no access. The gateway routes the document endpoint and removes its public analysis route.
- Configure `ANALYSIS_DB_URL`, `ANALYSIS_DB_USER`, `ANALYSIS_DB_PASSWORD` and `ANALYSIS_INTERNAL_TOKEN` for analysis-service; use the existing PostgreSQL instance. Set `ANALYSIS_INTERNAL_TOKEN` to the same independently generated secret in document-service and set `ANALYSIS_SERVICE_URL` to the private analysis URL. Credentials under 32 UTF-8 bytes fail closed. The client uses a three-second connection timeout and a five-second request timeout, rejects redirects, and limits response bodies to 1 MB. A private network is still required; a shared credential alone does not encrypt HTTP.

## Security and privacy

Fail closed for missing service credentials; compare credentials safely and never place them or document tokens in URLs, logs or error bodies. Bound request text and HTTP timeouts. Keep document text and synthetic test fixtures out of logs; store no client-supplied identity as authorization. Restrict access to analysis-service and PostgreSQL to the private deployment network. The existing Feature 2 scanner, parser, privacy and host acceptance blockers remain release blockers.

## Acceptance criteria

- [ ] Authenticated submission creates one persisted job; duplicate and concurrent requests for the same key return that job, including after restart.
- [ ] Missing or invalid internal credentials are rejected for submission and retrieval; no public gateway analysis endpoint exposes reports.
- [ ] A test fake can persist a report containing all specified fields, and a restart preserves job and report.
- [ ] The document report endpoint authorizes before upstream access; missing, wrong, expired and forged-identity requests cannot retrieve a report.
- [ ] Authorized retrieval returns `404` before creation, `202` while processing, a report when complete, and a safe error when the upstream fails.
- [ ] Bounded input, invalid hash, duplicate races and service failure are tested without logging sensitive data.
- [ ] Feature 2 remains open and the public profile remains disabled; no automatic extraction handoff or real model call is introduced.

## Test plan

| Test file or check | Scenario and expected result | Command |
| --- | --- | --- |
| Analysis controller and repository integration tests | Authentication, input validation, idempotency including concurrency, report persistence and fake analyzer | `cd backend/analysis-service && ./mvnw test` |
| Document report access integration tests | Token authorization before upstream call; report states and upstream failure | `cd backend/document-service && ./mvnw test` |
| Gateway integration tests | Document report routing and no public analysis route | `cd backend/api-gateway && ./mvnw test` |
| PostgreSQL integration check | Durable jobs and reports across service restart against the existing PostgreSQL instance | Record exact command and result during implementation |

All fixtures are synthetic. PostgreSQL and process-restart checks require a suitable local environment; H2 alone is not evidence of PostgreSQL behavior.

## Implementation notes

- **Likely files:** analysis-service controller, DTOs, persistence, configuration and tests; document-service controller, token authorization and HTTP client; gateway routing and tests; deployment/API documentation.
- **Order:** Specify and test internal contract and persistence; implement fake-backed tests; implement token-protected proxy; remove gateway route; verify and document results.
- **Open decisions:** Report and job selection details are implementation choices to be recorded with the API; no automatic processing is promised in this feature.

## Verification and handoff

- **Completed:** Internal authenticated job creation and report lookup, SQL tables and atomic report store, document-token-gated report proxy, removal of public gateway analysis route. Fake report generation is confined to tests. No production worker or automatic handoff has been added.
- **Checks run:** On 2026-11-21, Java 21 Maven containers ran analysis-service tests (5 passed, H2 and PostgreSQL 16 separately), gateway tests (17 passed), and document-service tests (107 tests, 25 skipped). A PostgreSQL test container retained six synthetic jobs and two reports after restart. The host Java 27 could not compile the existing Lombok-based document-service; Java 21 containers were used instead. Commands were `./mvnw -o clean test` in analysis-service and `./mvnw -o test` in document-service and gateway, run in `maven:3.9-eclipse-temurin-21` containers; the PostgreSQL analysis run set `TEST_ANALYSIS_DB_URL`, `TEST_ANALYSIS_DB_USER` and `TEST_ANALYSIS_DB_PASSWORD` to the isolated test instance.
- **Not verified / blockers:** Complete cross-service HTTP flow, service-process restart retrieval, some negative upstream responses and full response-time behavior remain to be verified. The database restart check used an isolated test container, not the eventual deployment. Feature 2's existing release blockers remain open and the public profile remains disabled.
- **Next step:** Exercise the cross-service flow and outstanding negative/recovery cases before considering Feature 3 complete.
