# First release target (proposed for review)

## Outcome

An anonymous visitor uploads a text-based Indian commercial contract (PDF or DOCX), sees processing status, then reads a temporary LLM-assisted report. The report contains a summary, key clauses, obligations, potential risks, and optional Indian-law observations. Each substantive finding links to a passage in the uploaded document; each law observation also links to an identified law provision. The system can show uncertainty or omit a finding when evidence is insufficient. Possession of a separate, unguessable access token is required to read or delete that upload and report.

## Architecture choices for this release

- Keep Angular, the existing gateway, document-service and analysis-service as separate codebases and deployable applications while proving the flow. Reconsider the gateway only after it works.
- Use a direct internal HTTP handoff from document-service to analysis-service after successful extraction. Make requests idempotent by document ID plus document content hash; store status so interrupted work can be retried. Do not send document text through Kafka for this release. The existing producer stays unused until its removal is separately approved.
- Use one PostgreSQL instance with separate document and analysis tables. Use a persistent local volume for original files on a single host. Pass bounded extracted text to analysis-service over an authenticated internal request; neither service needs a shared filesystem or S3 path.
- Use a configurable local open-weight model endpoint. Initial candidate: Ollama serving Qwen3:8b, subject to hardware and report-quality evaluation. The model identifier, endpoint and prompt version are configuration, not hard-coded product behavior. No claim of deterministic or legally correct model output is made.
- Start law-aware findings with a curated, versioned subset of primary Indian contract-law sources. Store title, section, URL, effective/as-of date and retrieved passage with each citation. A law finding requires an applicable verified source; otherwise report `not assessed` or uncertainty. Do not implement a separate law vector database in this release.
- No registration/login or user table in this release. On upload, document-service generates an opaque random access token independent of the document ID, returns it once, and stores only its hash and expiry. All public status, report and deletion requests go through document-service, which checks this token. Analysis-service exposes internal endpoints only, protected by a service credential. The browser keeps the token in session storage for the active tab, with a strict content security policy and no third-party scripts on report pages; losing it means the anonymous report cannot be recovered.
- Keep a minimal, explicit data model: document/job/report records are needed for asynchronous processing and restart recovery, but no user profile or permanent history is needed. Expire and delete originals, extracted text and reports after a configurable retention period (initial default: seven days). Model future ownership as optional `ownerUserId` or a separate access-grant relationship, without creating user accounts now.
- Because uploads are public and model inference is costly, apply **both per-IP and global upload rate limits** at api-gateway. Set separate global caps on queued analyses, concurrent parsing/model calls, file size, extracted text size and storage. Check the IP and global upload budgets atomically for each request; a request must pass both. Apply these strict budgets to upload/expensive operations, not ordinary status polling. Resolve client IP only from a trusted reverse proxy; never trust arbitrary `X-Forwarded-For`. For the proposed single gateway instance, use an in-memory limiter initially; its counters reset on restart, so revisit it before running multiple gateway replicas. Return `429` with `Retry-After` when a rate budget is reached and `503`/`Retry-After` when work capacity is full.
- Screen files before parsing: allow PDF and DOCX only, compare extension, declared type and detected signature, reject encrypted/unsupported content and suspicious archive expansion, store under generated names outside the web root, and bound parser time, memory and extracted output. Run the maintained parser in an isolated process/container boundary for public uploads. Add malware scanning before parsing for an internet-facing release, using a locally deployed scanner with a bounded timeout and fail-closed behavior when it is unavailable; a clean scan does not prove a file harmless.
- Never expose document-service, analysis-service, PostgreSQL, the model runtime or scanner directly to the internet. Restrict browser origins, apply HTTPS and security headers, keep access tokens out of URLs/logs, and never render extracted text or model output as trusted HTML.

## User-visible contract

`POST /api/documents/upload` returns `202` with a document ID, one-time access token, expiry and initial status. A status endpoint returns `PENDING`, `EXTRACTING`, `ANALYZING`, `COMPLETED`, or `FAILED` with a safe error code. A document-service report endpoint returns a versioned report only when the matching access token is supplied in a request header, never a URL. The frontend polls status, displays report sections and citations, and offers a retry for recoverable failures. There is no account-based document list. The exact JSON schema is contract-tested before UI work.

## Release acceptance

1. A real PDF and DOCX each complete upload, extraction, model call and report display in a deployed test environment.
2. A different visitor cannot read or delete a document/report with only its ID, a forged identity header or an invalid/expired token; direct access to analysis-service does not expose reports.
3. Restarting document-service or analysis-service during processing does not silently lose a job; replay does not create duplicate reports.
4. Every report finding has a valid document passage reference; law findings also have a valid, inspectable primary-source citation. Unsupported assertions are rejected or marked uncertain.
5. Model unavailable, malformed output, unreadable/empty document and oversize upload produce observable failures and user-facing recovery paths.
6. Excessive upload requests from one IP **or across all IPs** receive `429`; spoofed forwarded IP headers do not bypass limits. A full analysis queue receives a bounded failure. Malformed, mismatched, encrypted, scanner-positive and decompression-bomb fixtures are rejected or safely fail without exhausting the service; scanner outage fails closed.
7. A small reviewed evaluation set of representative contracts is used to judge factual grounding, citation correctness, risk usefulness and abstention. The release gate is a reviewed result, not an assumed accuracy percentage.
8. A repeatable single-host deployment starts the Angular frontend, gateway, two services, PostgreSQL and model runtime, with persistent storage, health checks, retention cleanup and public-upload abuse limits.

## Out of scope

User accounts, login, permanent report history, chat/RAG conversations, scanned-document OCR, arbitrary legal document types, case-law search, automatic legal advice, Kafka consumer/broker deployment, S3, Redis, separate analysis database, separate vector databases, Kubernetes and service discovery. These can be reconsidered with evidence from usage and evaluation.

## Decisions to validate before implementation

- Confirm that Indian commercial contracts are the first supported document type. This is inferred from the discussion and remains open to correction.
- Benchmark the proposed open-weight model on available deployment hardware and the evaluation set. If it cannot produce usable, grounded reports, choose another open-weight model or host before the model feature begins.
- Confirm the seven-day retention default, deployment host and initial per-IP/global budget values before Feature 1. Limits should be configurable and tuned against the actual host/model capacity. Later login can claim an anonymous report only through proof of its access token and an authenticated user session.
