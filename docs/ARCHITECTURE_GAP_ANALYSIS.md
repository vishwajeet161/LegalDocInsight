# Architecture gap analysis (2026-11-01)

## Source of comparison

`docs/original-hld.png` is not in this checkout. This comparison uses the available `docs/NEW_LegalDoc_HLD.png` and root `README.md` as the intended HLD. See `CURRENT_ARCHITECTURE.md` for checked-in evidence and operational limits. The recommendation is for finishing the product with minimal reasonable change, not an architectural refactor performed in this phase.

## HLD versus code versus minimum-change direction

| Component / capability | HLD intent | Current code | Classification and recommendation |
| --- | --- | --- | --- |
| Angular client | Upload, analysis results, legal chat | Landing/upload only; hard-coded gateway IP; no status/result/chat UI | **MODIFY**: configurable same-origin API URL; status/result and later chat UI. |
| API gateway as separate deployment | Central routing/auth | Separate Spring Cloud Gateway, fixed routes and partial JWT; two dead/mismatched routes | **MERGE or REMOVE deployment** after validating hosting needs: for a simple deployment, use one public backend and normal reverse proxy/static host. Keep existing gateway temporarily only if its routing is needed while services remain separate. No useful business code needs migrating. |
| Document service | Metadata, upload, storage, extraction | Most substantive implementation, though checked-in code does not compile | **KEEP, MODIFY**: make it build, configure storage/DB, align entity and extraction lifecycle, secure access. Natural anchor for the first working backend. |
| Analysis service as separate deployment | Chunk/embed/analyze; persist results | One stub controller, no analysis code or storage | **MERGE** its planned responsibility into the document backend for the first finished workflow; there is almost no implemented analysis code to move. Retain folder temporarily if useful for incremental work, but no reason to deploy its stub. Reassess separation only when real workload or scaling needs emerge. |
| Document PostgreSQL | Document metadata | JPA entity/repository and reference SQL; no active datasource properties | **KEEP, MODIFY**: use one configured relational database for metadata and analysis results initially. |
| Separate Analysis DB | Analysis results | No analysis entity/repository/table | **NOT IMPLEMENTED; ADD LATER only if isolation/scaling requires it**. A separate database now increases provisioning and cross-data coordination. |
| Local file storage | HLD prefers S3 | Local implementation exists, requires config | **KEEP** for simple single-instance deployment with a persistent volume; abstract interface already exists. |
| S3 direct/presigned upload | HLD document/object store | SDK beans and incomplete S3 service | **ADD LATER** if multi-instance/object storage is required; do not make it a first-run dependency. |
| Tika extraction | Parse documents | Implemented extraction service and async orchestration, blocked by model/build issues | **KEEP, MODIFY**: repair persisted fields, failure handling and status reporting. |
| OCR | Scanned contracts | No OCR engine | **ADD LATER** with an explicit scanned-document requirement/quality target. |
| Kafka | Document extracted event to analysis | Producer only, no consumer; event sent even on extraction failure | **REMOVE** from minimum deployment. Replace the unused event hop with an in-process handoff/job after extraction; keep asynchronous processing without a broker. Revisit if independently scaled workers become necessary. |
| Analysis pipeline | Summary, clauses, risk, compliance | Stub returns `Analysis started`; no result | **NOT IMPLEMENTED**: define a narrow, testable first analysis output and persist it before expanding claims. |
| Document vector DB | Retrieval over uploaded text | None | **ADD LATER** if document size/volume and search quality warrant it. Start with persisted text and bounded retrieval/chunking; choose indexing after measuring. |
| Indian-law vector DB | Grounded law retrieval | None; no corpus, provenance or update process | **ADD LATER**. First define authoritative source set, versions and citation format; a dedicated DB is not yet justified. |
| Chat/RAG and LLM | Document + Indian-law context, response/citations | No chat service, retriever, LLM, embedding provider or frontend | **NOT IMPLEMENTED**: build after analysis/document lifecycle, with source-backed citations and safety boundaries. A separate chat deployment is optional, not implied by HLD. |
| Redis/cache | Fast reports | None | **ADD LATER** when measured latency/cost shows need; database caching can suffice first. |
| Auth/user service | User isolation, JWT | No issuer/user service; gateway public routes; spoofable owner header | **MODIFY** as a release blocker: choose an actual identity source, enforce identity at the backend, remove trust in arbitrary headers. Separate auth service is **NOT IMPLEMENTED** and not required solely for deployment. |
| Service discovery, workflow engine, Kubernetes | Scale and orchestration in README | None | **ADD LATER** only for demonstrated operating needs; not necessary to finish initial workflow. |
| Observability | Metrics/tracing | Gateway actuator/logging; document logs/MDC | **KEEP, MODIFY** basic health and correlated logs; advanced stack later. |

## Recommended runtime shape

Target one deployable Spring Boot backend, one Angular static deployment, one relational database, and a persistent file volume for the first complete release. Keep the repository as a monorepo and preserve document/analysis as logical modules/packages. Make upload, extraction, analysis, status/result retrieval and chat available behind the same public API. The existing gateway may stay during transition if the team already operates it, but the checked-in code gives no compelling reason for it as a permanent extra process. Keep the option to extract analysis later if workloads or operational boundaries justify it.

This is a recommendation, not a claim that merging currently working services is free: the document app needs build and configuration repairs, and moving gateway security to one backend needs explicit work and tests. The actual *analysis-service* merge cost is low because only one stub endpoint exists. Keeping three independent services would require implementing a consumer or reliable HTTP handoff, separate status/result ownership and config/deployment for each, with little current benefit.

## What works, what is partial, what is absent

- **Present in code:** Angular upload interaction; gateway route/filter/circuit breaker definitions; document CRUD/upload logic, local storage implementation, Tika parser, async executor, metadata repository, Kafka producer.
- **Partial/blocked:** Document build and startup; storage selection; datasource configuration; authenticated ownership; extraction persistence; Kafka delivery; browser-to-gateway configuration. No end-to-end success is verified.
- **Absent:** Analysis algorithms and result persistence/API, document-to-analysis consumer/call, result UI, chat/RAG, Indian-law corpus, embeddings/vector search, OCR, auth issuance, reproducible deployment, meaningful backend tests.

## Priority issues to fix before product work

1. Make document service compile: missing application import, mismatched `Document` fields/accessors, incomplete S3 implementation and duplicate storage beans.
2. Establish checked-in safe configuration examples/profiles for DB, storage, upload limit and any retained broker. Verify startup without secrets in source.
3. Close the identity gap: currently public routes and client-controlled `X-User-Id` permit cross-user access. Define a real trust boundary before hosting legal documents.
4. Align gateway paths, frontend URL/origins and API contracts. The analysis route currently cannot reach the stub endpoint.
5. Ensure extraction failure does not emit a success event and that processing state/results survive restart. Avoid logging or leaking document content.

## Proposed implementation order (requires approval before refactoring)

1. Establish a buildable baseline and a local repeatable run: fix compile/config errors, choose local storage plus one PostgreSQL instance, add meaningful upload/extraction/security tests.
2. Make document lifecycle reliable: persist extracted text and counts, status/error APIs, frontend polling and clear error states. Validate actual PDF/DOCX behavior.
3. Implement a modest analysis pipeline and persistence in the same backend; expose result API and UI. Define the output and acceptance criteria before selecting any LLM provider.
4. Remove unused Kafka handoff and unnecessary deployment units as the single-process path becomes operational; replace gateway security with enforced backend authentication/authorization and a simple public routing setup.
5. Add document-grounded chat with explicit citations and bounded retrieval. Add Indian-law material only after source licensing, provenance, versioning and citation rules are defined. Add embedding/vector infrastructure only if basic retrieval is inadequate.
6. Package and verify deployment (Angular static hosting, backend, DB, persistent storage, secrets, backups and health checks); then measure whether separate workers, object storage or caches are justified.

No architectural refactoring or production code changes were made in this discovery phase.
