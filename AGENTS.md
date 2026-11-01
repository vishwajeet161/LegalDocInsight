# LegalDocInsight agent guidance

## Project context

LegalDocInsight is an intentional monorepo. `frontend/` is Angular. `backend/api-gateway/`, `backend/document-service/`, and `backend/analysis-service/` are separate Spring Boot applications listed by the aggregator `backend/pom.xml`. Their current separation is not a requirement to keep or merge them permanently.

Read `README.md`, `docs/CURRENT_ARCHITECTURE.md`, and `docs/ARCHITECTURE_GAP_ANALYSIS.md` before work that affects application behavior or deployment. The checked-in implementation is the source of truth; the README and `docs/NEW_LegalDoc_HLD.png` express intended capabilities. The previously referenced `docs/original-hld.png` is not present in this checkout. Treat the gap analysis as a recommendation, not an approved refactoring plan.

## Working rules

- Prefer the smallest change that finishes the requested behavior. Preserve working code and the monorepo layout.
- Trace a feature end to end across Angular, gateway, backend, persistence, and deployment before changing its architecture or API contract.
- Do not infer that separate Spring Boot projects must remain independently deployed, or that they should be merged merely to simplify a diagram.
- Do not introduce Kafka, Redis, S3, vector databases, Kubernetes, new frameworks, or additional services without evidence that the requested feature needs them and user approval for the architectural change.
- The discovery phase is complete. **Wait for the user's explicit approval before architectural refactoring**, including merging or removing services, changing deployment boundaries, or replacing Kafka. A recommendation in a document does not grant that approval.
- Keep secrets, credentials, and real legal documents out of source control, tests, logs, and agent responses. Do not trust a client-supplied user ID as proof of document ownership.
- If implementation differs from documentation, report the difference and update the relevant documentation as part of the approved task.

## Feature workflow

- Use spec-driven development for each release feature. Read `docs/FIRST_RELEASE_SCOPE.md` and the relevant section of `docs/superpowers/plans/2026-11-01-first-release.md`, then write a focused feature specification using `docs/FEATURE_SPEC_TEMPLATE.md` before implementation.
- Base the specification on the current code and the user's decisions. Record observable acceptance criteria, interfaces, security and failure behavior, and meaningful tests. Resolve material ambiguities before dependent work.
- Implement against the specification, run the relevant tests, and record results and outstanding work in the feature specification so a later session can continue. Update the release plan when feature status changes.
- Subagents are optional and should be used only when the user requests or approves their use; spec-driven development does not require them.

## Verification and reporting

- For backend work, use the Maven wrappers in the affected service directories; for frontend work, use scripts from `frontend/package.json`. Commands and their success have not yet been verified for this checkout, so report actual results and any environment blockers.
- Add focused tests for changed behavior, especially document access, upload/extraction state, analysis results, and API routing. Avoid claiming a flow works based only on a successful compile or a component creation test.
- In the final report, state what changed, why, the verification performed and its result, and any remaining limitation. Do not claim an end-to-end workflow is complete unless it was exercised.
