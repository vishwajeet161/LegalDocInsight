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

Document extraction persists successful text and `EXTRACTED` status, but does not submit it to analysis. Extraction recovery handles interrupted extraction on the supported single instance. Analysis accepts authenticated submissions and deduplicates by document ID, content hash and analysis version, storing a `PENDING` job and text. It exposes persisted reports through the document service; there is no analysis worker, model call, or automatic handoff. Feature 3 exercised the gateway and both services with manual submission and a fake report, not extraction-to-model processing.

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

- **Completed:** Initial provisional model HTTP client, passage selection, evidence validation, an unscheduled single-job worker and single-instance queue admission. Successful extraction stores a pending handoff marker in the same database update. A manually invoked handoff attempt sends authenticated, hashed requests and retains pending status on transient errors. The worker and handoff are not connected to automatic processing.
- **Checks run:** On 2026-11-21, `./mvnw -o test` in Java 21 Maven containers passed 18 analysis-service tests (1 skipped) and 112 document-service tests (25 skipped). These checks use H2 and controlled model responses, not PostgreSQL or a real model.
- **Not verified / blockers:** Delivery, worker scheduling and process recovery, model retry policy, deletion races, concurrency limits, PostgreSQL and end-to-end flow are not implemented or verified. The model API provider and quality remain undecided. Feature 2 public-release blockers remain open.
- **Next step:** Finish the delivery and worker lifecycle, test deletion and recovery behavior, and repeat the complete suite and process checks.
