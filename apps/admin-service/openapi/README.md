# OpenAPI Contract

This service publishes its generated OpenAPI 3 contract at `/openapi.json`
and Swagger UI at `/docs`.

Treat the generated contract as an implementation artifact. Keep every
`/v1/...` operation, schema, canonical error, authentication requirement,
idempotency behavior, and correlation header aligned with the service
documentation under `../../../docs/services/` and the platform standards in
`../../../docs/architecture/`.

When changing an externally visible endpoint, update the implementation and
the matching SRS and integration contract together, then verify both
documentation endpoints with the service's focused test suite.
