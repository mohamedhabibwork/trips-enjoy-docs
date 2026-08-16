"""platform-python — cross-cutting library for Python services.

Provides:
- :mod:`errormodel` — RFC 7807 error envelope with the platform's
  ``code`` / ``traceId`` / ``spanId`` / ``downstream`` / ``errors``
  extensions.
- :mod:`money` — Money value type holding minor units + ISO 4217
  currency; arithmetic is integer-only with mixed-currency rejection.
- :mod:`requestid` — Starlette/FastAPI middleware that implements
  ADR-0019 (request-id at the edge).
- :mod:`settings` — Pydantic settings base class with the canonical
  ``<SERVICE>_DB_URL`` / ``<SERVICE>_REDIS_HOST`` / ``<SERVICE>_KAFKA_*``
  env-var schema.
- :mod:`observability` — OpenTelemetry tracer initialisation.
- :mod:`jwtauth` — Keycloak JWT verification with claim projection.
"""
from __future__ import annotations

__version__ = "4.1.0"
