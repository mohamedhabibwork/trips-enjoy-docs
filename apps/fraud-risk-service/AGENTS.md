# Fraud Risk Service Guide

This is a Python 3.12+ FastAPI service. Read the matching documentation in
`../../docs/services/fraud-risk-service/` before changes.

- Keep API routing, schemas, domain logic, integrations, and configuration
  separated under `app/`.
- Treat decisions and features as sensitive: preserve documented explainability,
  audit, access-control, privacy, and model-versioning requirements.
- Validate inputs with Pydantic at the boundary and keep asynchronous I/O
  non-blocking. Do not log credentials or raw sensitive features.
- Run `ruff check .` and `pytest` for relevant changes. Change dependencies in
  `pyproject.toml` deliberately and keep the license inventory in sync.
- Do not edit `.venv/`, caches, or generated artifacts.

## Security and Quality Gate

- Never commit secrets, tokens, private keys, production data, or unredacted
  PII; redact sensitive values from logs, errors, and test fixtures.
- Authenticate callers, authorize every operation, validate inputs, and use
  parameterized persistence APIs. Do not disable security checks to pass tests.
- Run `ruff check .` and `pytest`; run configured formatting or lint tasks when
  present, and report checks that cannot run.
