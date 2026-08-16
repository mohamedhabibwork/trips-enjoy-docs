---
name: trips-enjoy-fraud-risk-service
description: Implement and review secure, clean Python/FastAPI changes for the Trips Enjoy fraud-risk service. Use for fraud-risk-service APIs, models, integrations, tests, or documentation updates.
---

# Fraud Risk Service

Apply the [shared Code Quality Gate](../AGENTS.md) before handoff.

Before implementation, read the service [README](../../docs/services/fraud-risk-service/README.md), [SRS](../../docs/services/fraud-risk-service/SRS.md), [integration contract](../../docs/services/fraud-risk-service/INTEGRATION.md), [workflows](../../docs/services/fraud-risk-service/WORKFLOWS.md), and [technical profile](../../docs/services/fraud-risk-service/TECH.md). Keep API, testing, Kubernetes, and monitoring changes aligned with those documents.

Read `../../docs/services/fraud-risk-service/` and `../../apps/fraud-risk-service/AGENTS.md` before changing code.

1. Preserve decision explainability, audit, access-control, privacy,
   model-versioning, event, and resilience contracts.
2. Separate routing, Pydantic schemas, domain logic, integrations, and
   configuration. Keep asynchronous I/O non-blocking.
3. Validate input at the boundary, authorize every operation, parameterize data
   access, and do not log raw features, PII, tokens, or secrets.
4. Keep code small, typed, explicit, and covered by focused tests. Do not edit
   `.venv/`, caches, or generated artifacts.
5. Run `ruff check .` and `pytest`; run configured formatting or lint tasks
   when present and report unavailable checks.
