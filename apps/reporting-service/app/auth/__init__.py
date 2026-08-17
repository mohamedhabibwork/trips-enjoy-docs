"""Auth package: Keycloak JWT validation + RBAC.

The runtime uses Keycloak JWKS to validate incoming bearer tokens; the
issuer is configured per env (see `.env.example`). For local development
without a live Keycloak we expose a stub that mints tokens for known
test users — `STUB_MODE=true` disables signature verification.
"""
from __future__ import annotations

from .jwks import JWKSCache
from .tokens import (
    Principal,
    decode_bearer,
    is_stub_mode,
    require_role,
    require_scope,
)

__all__ = [
    "JWKSCache",
    "Principal",
    "decode_bearer",
    "is_stub_mode",
    "require_role",
    "require_scope",
]
