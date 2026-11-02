# LegalDocInsight

LegalDocInsight is a work-in-progress application for analyzing legal documents. The first release targets a simple workflow: upload a text-based Indian commercial contract and receive an LLM-assisted report with references to the document and, where applicable, Indian law.

## Project status

**The first release is in development, not yet implemented end to end.** The repository contains an Angular landing and upload interface, an API gateway, document upload and extraction code, and an analysis-service stub. Feature 1 implemented the initial Java 21 build repairs, anonymous access tokens and gateway upload limits; there is no completed analysis report or chat workflow.

Anonymous access and rate limiting have initial implementations, but the complete first-release protections have not been verified. Malware scanning and isolated parsing remain planned. Do not deploy the current implementation as a public document-upload service.

For the code-level findings, see [Current architecture](docs/CURRENT_ARCHITECTURE.md) and [Architecture gap analysis](docs/ARCHITECTURE_GAP_ANALYSIS.md).

## First-release goal

A visitor can:

1. Upload a text-based PDF or DOCX contract without creating an account.
2. Follow the document's processing status.
3. Read a temporary report containing a summary, key clauses, obligations, potential risks and supporting document passages.
4. See optional Indian-law observations with references to a curated legal source when the source supports the finding.

The planned report should identify uncertainty when evidence is insufficient. LLM output and legal applicability need evaluation; the project does not guarantee identical results or legally correct conclusions.

## Planned first-release architecture

```text
Angular frontend
    |
    v
API gateway -- per-IP and global upload limits
    |
    v
Document service -- validate, scan, store, extract, track status
    |                         |
    |                         +--> PostgreSQL + persistent file storage
    v
Analysis service -- LLM analysis and report persistence
    |                         |
    +--> Open-weight model    +--> PostgreSQL
    |
    +--> Curated Indian-law sources

Frontend polls document-service for status and the report.
```

The gateway, document service and analysis service remain separate Spring Boot applications for the first release. The planned handoff from document service to analysis service uses internal HTTP. One PostgreSQL instance and a persistent local file volume support the initial single-host deployment. A local malware scanner and a model runtime are also planned.

Ollama serving Qwen3:8b is an initial model candidate; its hardware requirements and report quality must be measured before selection. The model endpoint and name are intended to be configurable.

## Planned privacy and upload controls

- Each upload receives a separate, unguessable access token. Status, report and deletion requests require that token; a document ID alone does not grant access.
- Documents, extracted text and reports are temporary. The proposed retention period is configurable, with seven days as the initial default to review.
- The gateway applies per-IP and global upload limits. The services also bound queue size and concurrent processing.
- Uploads are checked for supported type and size, scanned before parsing in the public deployment profile, and parsed with isolation and resource limits.
- Internal services and storage remain private. Secrets, access tokens and document contents must not appear in logs.

The full set of controls remains in progress. The single-instance in-memory rate limiter would need reconsideration if the gateway is scaled to multiple instances.

## Feature roadmap

| Order | Feature | Status |
| --- | --- | --- |
| 1 | Buildable baseline, anonymous upload and access control | Complete (user reports merged) |
| 2 | Document validation, scanning, extraction and status | In progress |
| 3 | Analysis API and report persistence | Planned |
| 4 | LLM analysis and reliable service handoff | Planned |
| 5 | Curated Indian-law sources and report evaluation | Planned |
| 6 | Angular status and report experience | Planned |
| 7 | Repeatable deployment and release checks | Planned |

Each feature has planned tests and acceptance checks in the [first-release implementation plan](docs/superpowers/plans/2026-11-01-first-release.md). The [first-release scope](docs/FIRST_RELEASE_SCOPE.md) records the proposed behavior and decisions still to validate.

## Repository layout

```text
frontend/                    Angular application
backend/pom.xml              Maven aggregator
backend/api-gateway/         Spring Cloud Gateway application
backend/document-service/    Document upload, storage and extraction application
backend/analysis-service/    Analysis application; currently a stub
docs/                        Architecture discovery, HLD and release plan
AGENTS.md                    Repository guidance for coding agents
```

The Maven modules are independent Spring Boot applications. The aggregator lists them; it is not itself a runnable backend.

## Development and tests

The first task is to establish a buildable baseline and document a repeatable local setup. The following are project entry points, not a full-stack setup:

- Frontend scripts are listed in [`frontend/package.json`](frontend/package.json), including `npm start`, `npm test` and `npm run build`.
- Each backend application has its own Maven wrapper. From an individual service directory, `./mvnw test` is the test command to use when the local Java and Maven setup is ready.
- Existing `application-example.properties` files are examples and are not automatically active Spring configuration. Complete runtime configuration and deployment instructions are part of the planned work.

For macOS or Linux development, `compose.dev.yaml` provides PostgreSQL and Java 21/Maven containers. With Docker Desktop running:

```bash
docker compose -f compose.dev.yaml --profile app up -d gateway
docker compose -f compose.dev.yaml run --rm maven -pl api-gateway test
docker compose -f compose.dev.yaml run --rm maven -pl document-service test
```

The first command starts the local gateway, document service and PostgreSQL. The gateway listens on `127.0.0.1:8080`; analysis and the frontend are not started by this configuration. The database also binds to localhost and uses a development-only default password. Set `LEGALDOC_DEV_DB_PASSWORD`, `LEGALDOC_DEV_DB_PORT` or `LEGALDOC_DEV_GATEWAY_PORT` to override the local defaults. Compose creates persistent database, document and Maven-cache volumes. Use `docker compose -f compose.dev.yaml --profile app down` to stop the stack without deleting its volumes. This setup is for local development and is not a public deployment.

## Later possibilities

User accounts and saved history, chat, scanned-document OCR, broader legal sources, Kafka, S3 and vector search can be evaluated after the first workflow is working. Their inclusion will depend on product and deployment needs.

## Documentation

- [First-release scope](docs/FIRST_RELEASE_SCOPE.md)
- [Feature-by-feature implementation and testing plan](docs/superpowers/plans/2026-11-01-first-release.md)
- [Feature specification template](docs/FEATURE_SPEC_TEMPLATE.md)
- [Current architecture](docs/CURRENT_ARCHITECTURE.md)
- [Architecture gap analysis](docs/ARCHITECTURE_GAP_ANALYSIS.md)
- [Existing HLD diagram](docs/NEW_LegalDoc_HLD.png)

## License

See [LICENSE](LICENSE).
