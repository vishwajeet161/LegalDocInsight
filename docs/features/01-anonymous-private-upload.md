# Feature specification: anonymous private upload

## Goal and status

- **Release feature:** 1 — buildable baseline and anonymous private upload.
- **Status:** Complete; local implementation and checks completed. The user reports that Feature 1 was merged to main; local main references inspected on 2026-11-02 had not yet reflected that merge.
- **Goal:** Accept a supported document without registration and give only its token holder access to its status, text and deletion endpoints.
- **Context:** [First-release scope](../FIRST_RELEASE_SCOPE.md), [implementation plan](../superpowers/plans/2026-11-01-first-release.md), and the existing gateway and document-service code.

## Scope

- **Included:** Repair document-service compilation and local configuration; persist the original file and document record; issue and verify a document access token; validate upload size and type; add gateway per-IP and global upload limits; add meaningful tests.
- **Excluded:** LLM analysis, report retrieval, malware scanning, parser isolation and retention cleanup. These belong to later release features. The service must not be exposed publicly before the planned upload protections are complete.
- **Dependencies:** Java and Maven wrapper, PostgreSQL for the deployed configuration, persistent file storage and a reachable gateway. Tests should be isolated from real legal documents and remote systems.

## Baseline before Feature 1

`DocumentController` accepts `X-User-Id` as an owner and defaults upload to `anonymous`; other operations trust that same client-controlled header. The gateway marks document routes public. The document entity omits extraction fields referenced by services, the S3 implementation is incomplete, and storage beans are ambiguous. The gateway has no IP or global upload limiter.

## Proposed behavior and interfaces

- `POST /api/documents/upload` accepts a multipart `file` and returns `202` with `documentId`, `accessToken`, `expiresAt` and status. Generate at least 256 random bits for the token, independently of the ID; persist only its SHA-256 verifier and expiry. Use a configurable seven-day expiry as the starting default.
- Require the token in `X-Document-Token` on document status, text and deletion endpoints. Missing, invalid and expired tokens receive the same safe denial; a forged user header never grants access. Disable anonymous document listing.
- Accept PDF and DOCX only, with matching file extension and detected content; enforce a configurable request-size limit before storage. Generate storage filenames and keep files outside the web root. Maintain storage and database consistency on failures as far as possible.
- Gateway applies separate configurable per-client-IP and global budgets atomically to `POST /api/documents/upload`. Denied requests receive `429` and `Retry-After`; ordinary status polling uses a separate budget. Resolve forwarded client addresses only from configured trusted proxies; otherwise use the direct peer address. Bound the in-memory limiter's state. Limits are per gateway instance and reset on restart.
- Until Feature 2 completes the safety work, keep this as a local development baseline; do not represent its file validation as malware protection.

## Security and privacy

Never log tokens or document contents; compare token verifiers safely. Protect direct service access with document-service token checks and server-side upload constraints. Extraction, external scanner and global worker limits remain required before public deployment. No accounts, user table, Redis, S3 or Kafka deployment are introduced.

## Acceptance criteria

- [x] The gateway and document-service build and start with documented configuration.
- [x] A supported upload returns an ID, access token, expiry and initial status; its record and file survive restart.
- [x] Correct token grants access; absent, incorrect or expired token and forged identity headers do not.
- [x] Invalid, mismatched, empty or oversized uploads are rejected.
- [x] Per-IP and global upload limits reject excess requests without trusting spoofed forwarded headers; status polling does not consume upload budget.

## Test plan

| Test file or check | Scenario and expected result | Command |
| --- | --- | --- |
| `DocumentUploadIntegrationTest` and `DocumentUploadTest` | Valid upload persists; empty, oversize and mismatched inputs fail. | Docker Maven tests in document-service |
| `DocumentAccessTest` | Correct, missing, wrong and expired tokens; failed deletion. | Docker Maven tests in document-service |
| `UploadRateLimiterTest` and `UploadRateLimitFilterTest` | IP/global limits, atomic checks, trusted proxy handling and polling isolation. | Docker Maven tests in api-gateway |
| Local integration check | Upload through gateway and confirm status access after restart. | Synthetic HTTP checks against local Compose services. |
| `frontend/src/app/pages/upload/upload.spec.ts` | A response access token is not logged. | `npm test -- --watch=false` in frontend |

## Implementation notes

- **Likely files:** document controller/service/entity/DTO/storage/configuration and gateway filters/configuration, plus tests and safe local configuration.
- **Order:** Establish test baseline; write tests; repair startup and storage; implement token validation and uploads; implement limits; run focused and service-wide tests; record results.
- **Open decisions:** Initial defaults are 10 MiB per file, four concurrent upload handlers, five uploads per IP per hour, 30 uploads globally per hour, 120 status requests per IP per minute and 3,000 status requests globally per minute. Tune these configurable values for the deployment host. Trusted proxies default to none; configure only proxy networks controlled by the operator.

## Verification and handoff

- **Completed:** Feature branch `feature/anonymous-private-upload`; local Docker Compose toolchain; document-service build repairs, token-protected anonymous upload, local storage and PostgreSQL profile; gateway per-IP/global limits and trusted-proxy resolution; initial service tests. Existing incomplete S3 code is inactive and Kafka is disabled unless explicitly configured.
- **Checks run (2026-11-01):** `docker compose -f compose.dev.yaml --profile app config --quiet` passed; `docker compose -f compose.dev.yaml run --rm maven -version` reported Java 21; PostgreSQL container reported healthy on `127.0.0.1:5432`. Service test suites ran in the Java 21 Maven image using the local Maven cache: gateway 12 tests passed; document-service 14 tests passed. The same suites passed through the documented Compose tool service with `LEGALDOC_MAVEN_CACHE` set to the existing cache. Frontend `npm test -- --watch=false` passed 8 tests; `npm run build` passed outside the sandbox after the sandboxed build exited unexpectedly. A synthetic upload through the gateway returned `202`, a forged identity received `403`, a valid token received `200`, and deletion returned `204`. A second synthetic upload remained accessible after a document-service restart and was deleted afterward. The Compose app profile accepted five synthetic uploads and rejected the sixth with `429` despite changing forwarded IP headers; generated documents were deleted and the gateway budget reset. The user also verified a PDF upload and extraction locally.
- **Not verified / limitations:** No public deployment, real legal document, scanner, isolated parser or retention cleanup was tested. Upload validation and this local profile are not a complete defense for untrusted public files. The in-memory gateway limits are per instance and reset on restart; proxy networks must be configured carefully. File and database deletion cannot be committed atomically, and a database failure after file deletion may leave a record needing repair. PostgreSQL, gateway and document-service containers and their named volumes remain available for local development.
- **Next step:** Continue with [Feature 2's specification](02-extraction-durable-status.md) and resolve its scanner/parser-isolation decisions before implementation.
