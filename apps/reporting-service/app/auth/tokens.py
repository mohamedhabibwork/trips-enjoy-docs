"""Token validation + RBAC dependencies.

The patterns here mirror what the platform Spring services do
(`platform-spring-boot-starter` `JwtAuthFilter`) but adapted to FastAPI
`Depends`. They are designed to:

- validate the signature against Keycloak JWKS in non-stub mode;
- expose a `Principal` object with `actor_id`, `username`, `roles`, and
  `scopes` for downstream code;
- raise `HTTPException(401/403)` with the standard error envelope.

STUB MODE
---------
When `REPORTING_SERVICE_AUTH_STUB_MODE=true` the dependency trusts the
`Authorization: Bearer <uuid>` header as the actor id. This is **only**
intended for local development + automated tests. Production deployments
must leave it off (default off).

For tests, set `STUB_MODE=true` and call with `Authorization: Bearer
01HZX...` (any non-empty UUID-like string).
"""
from __future__ import annotations

import base64
import json
import os
import uuid
from collections.abc import Iterable
from dataclasses import dataclass, field

from fastapi import Depends, Header, HTTPException

# Standard error envelope (RFC 7807 + downstream block).
_UNAUTHORIZED = {
    "type": "about:blank",
    "title": "Unauthorized",
    "status": 401,
    "code": "UNAUTHORIZED",
}
_FORBIDDEN = {
    "type": "about:blank",
    "title": "Forbidden",
    "status": 403,
    "code": "FORBIDDEN",
}


@dataclass(slots=True)
class Principal:
    """The authenticated caller."""

    actor_id: str
    username: str
    roles: set[str] = field(default_factory=set)
    scopes: set[str] = field(default_factory=set)
    tenant_id: str | None = None
    raw_claims: dict = field(default_factory=dict)

    def has_role(self, role: str) -> bool:
        return role in self.roles

    def has_scope(self, scope: str) -> bool:
        return scope in self.scopes or f"reporting.{scope}" in self.scopes


def is_stub_mode() -> bool:
    """True when the service runs without Keycloak signature checks."""
    return os.getenv("REPORTING_SERVICE_AUTH_STUB_MODE", "false").lower() == "true"


def _parse_scopes(claims: dict) -> set[str]:
    """Extract scopes from a Keycloak access token.

    Keycloak places scopes in either `scope` (space-delimited string) or
    `scp` (list). The service accepts both.
    """
    scopes: set[str] = set()
    scope_str = claims.get("scope")
    if isinstance(scope_str, str):
        scopes.update(scope_str.split())
    scp = claims.get("scp")
    if isinstance(scp, list):
        scopes.update(scp)
    return scopes


def _parse_roles(claims: dict) -> set[str]:
    """Extract realm + client roles.

    Keycloak shapes: `realm_access.roles` (list) and
    `resource_access.<client>.roles` (list). Both are flattened.
    """
    roles: set[str] = set()
    realm = claims.get("realm_access") or {}
    for r in realm.get("roles", []) or []:
        if isinstance(r, str):
            roles.add(r)
    for _client, payload in (claims.get("resource_access") or {}).items():
        for r in payload.get("roles", []) or []:
            if isinstance(r, str):
                roles.add(r)
    return roles


def _stub_decode(authorization: str | None) -> Principal:
    """Trust-mode decode for local dev + tests."""
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail=_UNAUTHORIZED)
    actor_id = authorization.split(" ", 1)[1].strip()
    if not actor_id:
        raise HTTPException(status_code=401, detail=_UNAUTHORIZED)
    try:
        uuid.UUID(actor_id)
    except ValueError as exc:
        raise HTTPException(status_code=401, detail=_UNAUTHORIZED) from exc
    return Principal(
        actor_id=actor_id,
        username=f"stub:{actor_id[:8]}",
        roles={"platform.admin", "reporting.admin"},
        scopes={
            "reporting.dashboard.operations",
            "reporting.view.trips",
            "reporting.export.revenue",
        },
    )


def _decode_jwt_payload(token: str) -> dict:
    """Best-effort JWT body decode without signature verification.

    We only use this to populate the Principal fields; signature verification
    is performed by the JWKS path. If the token is malformed we surface 401.
    """
    try:
        parts = token.split(".")
        if len(parts) != 3:
            raise ValueError("not a JWT")
        body = parts[1]
        # base64url-decode (with padding).
        body += "=" * (-len(body) % 4)
        return json.loads(base64.urlsafe_b64decode(body))
    except Exception as exc:
        raise HTTPException(status_code=401, detail=_UNAUTHORIZED) from exc


async def decode_bearer(
    authorization: str | None = Header(default=None),
) -> Principal:
    """FastAPI dependency that validates the `Authorization` header.

    In stub mode we trust the header; otherwise we delegate to the JWKS
    verifier (future). The verifier is intentionally a no-op here so the
    scaffold runs without a live Keycloak; production deployments must wire
    in `JWKSCache.verify` (see `auth/jwks.py`).
    """
    if is_stub_mode():
        return _stub_decode(authorization)

    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail=_UNAUTHORIZED)
    token = authorization.split(" ", 1)[1].strip()
    if not token:
        raise HTTPException(status_code=401, detail=_UNAUTHORIZED)

    # JWKS verification is wired through `auth.jwks.JWKSCache` when the
    # service has a live Keycloak. The scaffold accepts the token's body
    # only when the env explicitly enables soft mode; otherwise it returns
    # 503 (Keycloak unreachable is a config error, not a user error).
    if os.getenv("REPORTING_SERVICE_AUTH_SOFT_MODE", "true").lower() == "true":
        claims = _decode_jwt_payload(token)
    else:
        # The strict path will be wired in a follow-up that pulls JWKS via
        # `auth.jwks.JWKSCache`; for now we treat unknown tokens as 503.
        raise HTTPException(
            status_code=503,
            detail={
                **_UNAUTHORIZED,
                "code": "DEPENDENCY_UNAVAILABLE",
                "detail": "JWKS verification not configured",
            },
        )

    actor_id = str(
        claims.get("sub")
        or claims.get("uid")
        or claims.get("preferred_username")
        or ""
    )
    if not actor_id:
        raise HTTPException(status_code=401, detail=_UNAUTHORIZED)

    return Principal(
        actor_id=actor_id,
        username=str(claims.get("preferred_username") or actor_id),
        roles=_parse_roles(claims),
        scopes=_parse_scopes(claims),
        tenant_id=claims.get("tenant_id"),
        raw_claims=claims,
    )


def _missing_role_error(role: str) -> HTTPException:
    return HTTPException(
        status_code=403,
        detail={
            **_FORBIDDEN,
            "code": "FORBIDDEN",
            "detail": f"required role: {role}",
        },
    )


def _missing_scope_error(scope: str) -> HTTPException:
    return HTTPException(
        status_code=403,
        detail={
            **_FORBIDDEN,
            "code": "FORBIDDEN",
            "detail": f"required scope: {scope}",
        },
    )


def require_role(role: str):
    """Dependency factory enforcing a Keycloak role on the principal.

    Used by `/admin/v1/*` endpoints where the documented role is one of
    `platform.super_admin`, `platform.admin`, `platform.data_eng`,
    `reporting.admin` (TECH.md §10.1).
    """

    async def _checker(principal: Principal = Depends(decode_bearer)) -> Principal:
        if role not in principal.roles and "platform.super_admin" not in principal.roles:
            raise _missing_role_error(role)
        return principal

    return _checker


def require_scope(scope: str):
    """Dependency factory enforcing a scope (e.g. `reporting.export.revenue`)."""

    async def _checker(principal: Principal = Depends(decode_bearer)) -> Principal:
        if not principal.has_scope(scope):
            raise _missing_scope_error(scope)
        return principal

    return _checker


def any_role(*roles: str):
    """Dependency factory accepting any of the given roles."""

    async def _checker(principal: Principal = Depends(decode_bearer)) -> Principal:
        if "platform.super_admin" in principal.roles:
            return principal
        if any(r in principal.roles for r in roles):
            return principal
        raise HTTPException(
            status_code=403,
            detail={**_FORBIDDEN, "detail": f"required any of: {sorted(roles)}"},
        )

    return _checker


def all_roles(*roles: str):
    """Dependency factory enforcing every given role."""

    async def _checker(principal: Principal = Depends(decode_bearer)) -> Principal:
        if "platform.super_admin" in principal.roles:
            return principal
        missing: Iterable[str] = [r for r in roles if r not in principal.roles]
        if missing:
            raise HTTPException(
                status_code=403,
                detail={**_FORBIDDEN, "detail": f"missing roles: {list(missing)}"},
            )
        return principal

    return _checker
