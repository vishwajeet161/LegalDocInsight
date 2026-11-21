# Feature specification: LLM analysis and reliable handoff

## Goal and status

- **Release feature:** 4 — LLM analysis and reliable handoff
- **Status:** In progress; implementation and model selection are not complete.
- **Goal:** Turn a successfully extracted document into a persisted, evidence-linked report, recovering safely from interrupted delivery or analysis.
- **Context:** [Release scope](../FIRST_RELEASE_SCOPE.md), [release plan](../superpowers/plans/2026-11-01-first-release.md), and [Feature 3](03-analysis-api-report-persistence.md).

## Scope

- **Included:** Authenticated direct HTTP delivery, persisted retry and bounded admission, an analysis worker, configurable Ollama-compatible model access, structured output and evidence validation, coverage accounting, recovery and tests.
- **Excluded:** Curated Indian-law findings and quality evaluation (Feature 5), the Angular experience (Feature 6), and production deployment and retention cleanup (Feature 7). Keep Kafka disabled. Feature 2's public profile remains disabled and its release blockers remain open.
- **Dependencies:** Existing document extraction, Feature 3's private API and PostgreSQL tables, and a model endpoint and representative evaluation fixtures. On 2026-11-21 the user indicated they plan to use an API but have not selected a provider or model. Provider-specific integration and real-model verification remain open; the Ollama-compatible client is provisional.

## Current behavior

Successful extraction persists text, `EXTRACTED` status and pending delivery in one update. An opt-in document-service poller submits it over authenticated HTTP. The opt-in analysis worker claims jobs, calls a provisional Ollama-compatible endpoint, validates evidence and persists a report. Both pollers are disabled by default. Analysis deduplicates submissions by document ID, content hash and analysis version. Feature 4 has been exercised across processes with synthetic input, a controlled scanner and a controlled model endpoint; no real model has been exercised.

## Proposed behavior and interfaces

Use the existing direct HTTP API and one PostgreSQL instance. Record pending delivery durably with extraction completion, then retry transient submission failures with bounded backoff. A lost response can be retried using the existing idempotency key. Permanent input errors must become an observable safe failure rather than an infinite retry. Preserve the access-token boundary and existing report API.

Admission must count queued and in-flight work atomically and reject a new job with a retryable response when full; an existing duplicate remains retrievable even when the queue is full. The worker claims persisted jobs atomically and limits concurrent calls. In the supported single-instance deployment, recover interrupted claims at startup after the previous process has stopped; transactions must prevent duplicate reports. Bound attempts, response sizes and all model request phases. Store safe failure codes, not upstream exception bodies.

Split text into bounded, stable, identifiable passages. Record total and analyzed coverage, and report incomplete coverage explicitly rather than silently omitting text. The configured model endpoint, model ID, prompt version, timeout and capacity limits are deployment configuration. Validate structured JSON, required sections and every cited passage ID against the supplied text before committing a report; reject malformed output, unknown evidence and unsupported law claims. Do not treat generated text as HTML. Exact passage selection, report schema compatibility, safe retry controls, queue accounting and recovery semantics need to be finalized in the implementation design before coding.

## Security and privacy

Retain internal service authentication and token-protected public access. Bound input, output, time, concurrency and storage pressure; never log credentials, extracted text, prompts or model output. Use synthetic fixtures for automated tests. The model service is private and no document data is sent through Kafka.

## Acceptance criteria

- [ ] Extraction automatically delivers bounded text; transient outages and interrupted delivery recover without duplicate jobs or reports.
- [ ] Queue and concurrency caps are enforced, including duplicate submissions and concurrent admission.
- [ ] Restart during model processing recovers work; permanent and retryable failures are observable without sensitive details.
- [ ] Timeout, unavailable model, malformed JSON and invalid or missing evidence cannot create a completed report.
- [ ] Coverage is explicit for inputs larger than the model context; no unsupported law finding is presented as verified.
- [ ] A real model runtime processes at least one permitted representative contract through extraction, handoff and report retrieval; record model, hardware, time, memory and review observations without committing document contents.

## Test plan

| Check | Expected result | Command |
| --- | --- | --- |
| Document service tests | Automatic delivery, retry, capacity, security and restart recovery | `cd backend/document-service && ./mvnw test` |
| Analysis service tests | Concurrent admission, worker recovery, model timeout, malformed output, evidence validation and coverage | `cd backend/analysis-service && ./mvnw test` |
| Process integration | Gateway upload through extraction and both services using a controlled model; outages and process restarts | To be documented with the implementation |
| Real model exercise | Actual model and representative permitted input; record resources and review output | Depends on selected runtime and hardware |

## Implementation notes

The main alternatives are a transactional delivery record coupled to extraction completion, or polling persisted `EXTRACTED` documents directly. Both use the existing database and HTTP endpoint. A delivery record makes retries and failures explicit but adds lifecycle state; polling is smaller but needs an equally durable way to distinguish delivered, failed, deleted and retried documents. Recommend a durable delivery record with atomic creation and bounded polling, subject to review of deletion and recovery behavior. A volatile in-memory handoff cannot meet restart recovery.

Implement the delivery and worker with focused failure/restart tests, then model protocol and validation, then exercise the combined flow. Choose model and deployment settings only after a benchmark. The repository does not identify the target host or provide evidence of an available model runtime.

## Verification and handoff

- **Completed:** Opt-in HTTP handoff and single-instance worker, persisted bounded retry delays, evidence and coverage checks, analysis deletion tombstones and safe public status for exhausted handoff. Document deletion serializes with handoff and fails closed when analysis cleanup is unavailable. The worker recovers `PROCESSING` jobs on startup; this requires the previous analysis process to have stopped. Both polling features default to disabled.
- **Checks run:** On 2026-11-21, `./mvnw -o test` in Java 21 Maven containers ran 22 analysis-service tests (2 skipped) and 116 document-service tests (25 skipped), without failures. An opt-in `TwoServiceRestartIntegrationTest#automaticHandoffAndAnalysisRecoverAfterProcessRestart` used a dedicated PostgreSQL 16 `feature4` database, real gateway and service processes, a synthetic DOCX, a controlled scanner, and a controlled HTTP model. It exercised upload, extraction, automatic delivery, killing the analysis process during a model call, restart, report retrieval, deletion, late-submission rejection and selected log-privacy checks. It did not test a real scanner, real model or production isolation.
- **Not verified / blockers:** Provider/model selection, real-model quality and performance, document-service restart during handoff, concurrent or multi-instance operation, complete queue/retry exhaustion and deletion race cases, migration and deployment checks, and broader privacy review remain open. Existing extracted documents with no delivery marker are not automatically backfilled; Feature 3 jobs submitted manually without a delivery marker are not covered by document-service cleanup. Tombstones currently have no retention cleanup. A storage deletion failure after successful analysis cleanup leaves the document row present but its analysis tombstoned; retrying deletion is needed. Feature 2 public-release blockers remain open.
- **Next step:** Select the model API and implement its adapter. Complete restart, failure/retry, migration and deletion coverage; establish tombstone retention and cleanup before release.
