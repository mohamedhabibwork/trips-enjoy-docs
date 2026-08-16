---
name: trips-enjoy-reporting-service
description: Implement and review secure, clean Python/FastAPI changes for the Trips Enjoy reporting service. Use for reporting-service APIs, read models, integrations, tests, or documentation updates.
---

# Reporting Service

Apply the [shared Code Quality Gate](../AGENTS.md) before handoff.

Before implementation, read the service [README](../../docs/services/reporting-service/README.md), [SRS](../../docs/services/reporting-service/SRS.md), [integration contract](../../docs/services/reporting-service/INTEGRATION.md), [workflows](../../docs/services/reporting-service/WORKFLOWS.md), and [technical profile](../../docs/services/reporting-service/TECH.md). Keep API, testing, Kubernetes, and monitoring changes aligned with those documents.

Read `../../docs/services/reporting-service/` and `../../apps/reporting-service/AGENTS.md` before changing code.

1. Preserve read-model source-of-truth, freshness, privacy, authorization,
   retention, event, and resilience contracts.
2. Separate routing, Pydantic schemas, domain logic, integrations, and
   configuration. Keep asynchronous I/O non-blocking.
3. Validate input at the boundary, authorize every operation, parameterize data
   access, and do not log report data, PII, tokens, or secrets.
4. Keep code small, typed, explicit, and covered by focused tests. Do not edit
   `.venv/`, caches, or generated artifacts.
5. Run `ruff check .` and `pytest`; run configured formatting or lint tasks
   when present and report unavailable checks.
