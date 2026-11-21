# Feature 3 Analysis Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans; execute test-first and record results in the feature specification.

**Goal:** Persist private analysis jobs and reports and expose token-protected retrieval.

**Architecture:** Analysis-service provides credential-protected HTTP and owns PostgreSQL tables; document-service checks its existing token before proxying report retrieval. No automatic submission or real model is included.

**Tech Stack:** Java 21, Spring Boot, JPA, PostgreSQL, Maven.

**Spec:** `docs/features/03-analysis-api-report-persistence.md`

## Global Constraints

Preserve Feature 2 blockers and disabled public profile; no new service or infrastructure; fake analyzer only in tests; never include the staged AGENTS.md in commits.

## Review Focus

- Concurrent duplicate submissions must converge on one persisted job.
- Missing, incorrect and unconfigured service credentials must fail closed.
- Invalid or expired document tokens must never trigger an upstream request.
- Oversized or malformed content and mismatched hashes must fail safely.
- Upstream errors and malformed reports must not disclose content or credentials.

### Task 1: Private analysis persistence and API

**Files:** analysis-service POM, configuration, controller, entities, repositories and `AnalysisControllerIntegrationTest`.
**Interfaces:** POST `/internal/analyses`; GET `/internal/analyses/documents/{id}/report`; `X-Internal-Token` credential.

- [ ] Write tests for authentication, validation, unique submissions and persistent report retrieval using synthetic text and a test-only fake.
- [ ] Run tests to demonstrate missing behavior.
- [ ] Implement bounded input, database uniqueness, job/report storage and credential checks.
- [ ] Run tests and record results.

### Task 2: Authorized document report and gateway

**Files:** document-service controller, HTTP client and `DocumentReportAccessIntegrationTest`; gateway route configuration and tests.
**Interfaces:** GET `/api/documents/{id}/report` with `X-Document-Token`; consume Task 1 internal retrieval.

- [ ] Write tests proving authorization occurs before upstream calls, report statuses are propagated, and public analysis route is absent.
- [ ] Run tests to demonstrate missing behavior.
- [ ] Implement bounded HTTP proxy and remove public analysis route.
- [ ] Run affected service suites, verify PostgreSQL persistence where available, and record exact outcomes.

### Task 3: Documentation and review

- [ ] Document configuration and concrete API, update acceptance evidence and release status without closing Feature 2.
- [ ] Review security, persistence and staged changes; run applicable suites.
- [ ] Commit only Feature 3 files, push the branch and create the requested PR against main.
