# Reporting Service Guide

This is a Python 3.14 + FastAPI service that materialises domain events
into queryable read models, serves dashboards, runs CSV / Parquet export
jobs, and detects reconciliation drift. Read the matching documentation in
`../../docs/services/reporting-service/` before changes — the per-service
README, SRS, ERD, INTEGRATION, WORKFLOWS, TECH, and PLAN are the source of
truth.

## Layout

```
app/
  main.py                # FastAPI entrypoint + lifespan + middleware
  config.py              # Pydantic Settings (env-var → typed config)
  logging.py             # Structured JSON logger + redactor
  auth/                  # Keycloak JWT validation + RBAC scopes/roles
  api/                   # Public + admin routers (Pydantic schemas)
    dashboards.py
    views.py
    exports.py
    drift.py
    read_models.py
    admin.py             # /admin/v1/* endpoints (TECH.md §10.4)
  domain/                # Read-model entities (Pydantic) + projections
    types.py
    trips.py             # reporting_trips.trips projector
    orders.py            # reporting_orders.orders projector
    payments.py          # reporting_payments.intents projector
    ledger.py            # reporting_ledger.postings projector
    drift.py             # reconciliation drift detection
    exports.py           # S3 export workflow
  events/                # Kafka consumer + projectors + DLQ
    consumer.py
    inbox.py
    outbox.py
    projectors.py        # dispatch event_name → projector
  observability/         # request_id middleware, OTel stub, RED metrics
  audit.py               # audit.admin.reporting.v1 emitter stub
migrations/versions/
  0001_create_reporting_schema.py       # initial schema
  0002_create_reporting_core_tables.py   # drift_findings, export_jobs,
                                          # read_access_log, inbox, outbox
```

## Conventions

- Keep API routing, schemas, domain logic, integrations, and configuration
  separated under `app/`.
- Treat reports and read models as derived data; preserve documented freshness
  (median view lag < 5 min), privacy (PII masking in non-admin reads),
  authorization (per-dashboard / per-export / per-tenant scopes), retention
  (2 years for read models), and source-of-truth requirements
  (recomputable from the event stream).
- Validate inputs with Pydantic at the boundary and keep asynchronous I/O
  non-blocking. Do not log credentials or raw sensitive report data.
- Run `ruff check .` and `pytest` for relevant changes. Change dependencies in
  `pyproject.toml` deliberately and keep the license inventory in sync.
- Do not edit `.venv/`, caches, or generated artifacts.

## Per-endpoint scope/role reference

Public API (`/v1/...`):

| Endpoint | Required scope |
|---|---|
| `GET /v1/dashboards/{name}` | `reporting.dashboard.{name}` (e.g. `operations`) |
| `GET /v1/views/{view_name}` | `reporting.view.{view_name}` |
| `POST /v1/exports/{name}/run` | `reporting.export.{name}` |
| `GET /v1/exports/{name}/status` | `reporting.export.{name}` |
| `GET /v1/reconciliation/drift` | `reporting.admin` |
| `GET /v1/read-models` | `reporting.admin` |

Admin API (`/admin/v1/...`, port 8081 in prod):

| Endpoint | Required role |
|---|---|
| `POST /admin/v1/reports/{id}/materialize` | `reporting.admin` |
| `GET /admin/v1/exports` | `reporting.admin` |

## Security and Quality Gate

- Never commit secrets, tokens, private keys, production data, or unredacted
  PII; redact sensitive values from logs, errors, and test fixtures.
- Authenticate callers, authorize every operation, validate inputs, and use
  parameterized persistence APIs. Do not disable security checks to pass
  tests.
- Run `ruff check .` and `pytest`; run configured formatting or lint tasks
  when present, and report checks that cannot run.
- Every admin mutation emits `audit.admin.reporting.v1` with `actor_id`,
  `actor_username`, `roles`, `endpoint`, `target_resource`, `action`,
  `reason_code`, `request_id`, `trace_id`, `result`, `duration_ms`.