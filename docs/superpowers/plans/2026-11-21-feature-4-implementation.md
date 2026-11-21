# Feature 4 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans and test each behavior before implementation.

**Goal:** Recoverably deliver extracted documents to a bounded model worker and persist validated, evidence-linked reports.

**Architecture:** Persist handoff state with extraction completion and retry authenticated HTTP delivery. The analysis service admits and claims bounded PostgreSQL jobs, calls a configurable private Ollama-compatible endpoint, validates passage references and persists reports atomically. Support one instance per service initially.

**Tech stack:** Java 21, Spring Boot, PostgreSQL, Maven wrappers, Java HTTP client.

**Spec:** `docs/features/04-llm-analysis-reliable-handoff.md`.

## Constraints and review focus

- Preserve token checks, private endpoints, bounded data and logs free of secrets and document content. Keep Kafka and Feature 2 public profile disabled.
- Retry a lost delivery response without duplicates; do not deliver deleted documents.
- Atomic admission must account for simultaneous submissions and duplicates when full.
- Startup recovery requires the previous single instance and its model calls to have stopped.
- Invalid evidence, malformed output, stalled bodies, large input and incomplete coverage must not produce a misleading completed report.
- Real-model acceptance requires actual runtime, hardware and an approved representative fixture; do not infer it from a fake.

## Tasks

1. **Report validation and coverage:** Add tests for deterministic bounded passages, coverage, valid evidence, unknown/missing evidence, malformed output and law claims. Run failing tests, implement a focused passage selector and validator, then run analysis-service tests.
2. **Model client and worker:** Test timeout including stalled body, malformed and oversized responses, limited concurrency, atomic claim/completion, failures and restart recovery. Implement configured Ollama-compatible client and worker, plus bounded transactional admission; run analysis-service tests against H2 and PostgreSQL where available.
3. **Durable delivery:** Test extraction transaction, authenticated HTTP submission, duplicate delivery, bounded retries, permanent failure, deletion races and process restart. Implement durable delivery state, HTTP client and recovery; run document-service tests and PostgreSQL process tests.
4. **Integration and evidence:** Run the gateway, both services, PostgreSQL and a controlled model endpoint through upload, extraction, handoff, report retrieval and restart scenarios. If a real runtime and permitted representative contract are available, run the real-model acceptance and record model, time, memory and review. Update the feature specification, release plan and README with actual outcomes and limitations.

Each task begins with a failing focused test and ends with a passing relevant suite. Document any dependency or environment blocker rather than claiming a flow was exercised.
