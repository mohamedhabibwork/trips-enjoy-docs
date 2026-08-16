"""Keycloak JWT verification helper shared by Python services.

Mirrors the ``platform-spring-boot-security`` Kotlin module's
``JwtRoleConverter`` and the Go ``platform-go/jwtauth`` package.

The canonical contract:

1. Verify the JWT signature against the Keycloak JWKS endpoint.
2. Map ``realm_access.roles[]`` to ``ROLE_<UPPER>``.
3. Map ``resource_access.<client>.roles[]`` to ``ROLE_<CLIENT>_<UPPER>``.
4. Map ``scope`` (or ``scp``) to ``SCOPE_<lower>``.
5. Check expiration.
"""
from __future__ import annotations

import json
import time
from dataclasses import dataclass, field
from typing import Any

import httpx
import jwt
from jwt import PyJWKClient


@dataclass(frozen=True)
class Claims:
    subject: str
    username: str
    email: str
    roles: tuple[str, ...]
    scopes: tuple[str, ...]
    expires_at: float
    issuer: str
    audience: tuple[str, ...] = field(default_factory=tuple)

    def has_role(self, name: str) -> bool:
        return name in self.roles

    def has_any_role(self, names: list[str]) -> bool:
        return any(n in self.roles for n in names)


class JWTAuth:
    """Token verifier with JWKS caching."""

    def __init__(self, issuer_url: str, client_id: str, jwks_url: str | None = None) -> None:
        self.issuer_url = issuer_url
        self.client_id = client_id
        self.jwks_url = jwks_url or f"{issuer_url.rstrip('/')}/protocol/openid-connect/certs"
        self._jwks_client = PyJWKClient(self.jwks_url)

    def verify(self, raw_token: str) -> Claims:
        signing_key = self._jwks_client.get_signing_key_from_jwt(raw_token).key
        payload = jwt.decode(
            raw_token,
            signing_key,
            algorithms=["RS256"],
            audience=self.client_id,
            issuer=self.issuer_url,
        )
        roles: list[str] = list(payload.get("realm_access", {}).get("roles", []))
        for client, access in payload.get("resource_access", {}).items():
            for role in access.get("roles", []):
                roles.append(f"{client.upper()}_{role.upper()}")
        scopes = (
            payload.get("scope", "").split()
            or payload.get("scp", "").split()
        )
        return Claims(
            subject=payload["sub"],
            username=payload.get("preferred_username", ""),
            email=payload.get("email", ""),
            roles=tuple(roles),
            scopes=tuple(scopes),
            expires_at=float(payload["exp"]),
            issuer=payload["iss"],
            audience=tuple(payload.get("aud", []) if isinstance(payload.get("aud"), list) else [payload["aud"]]),
        )


def fetch_openid_metadata(issuer_url: str) -> dict[str, Any]:
    """Fetch ``.well-known/openid-configuration`` from the Keycloak issuer."""
    url = f"{issuer_url.rstrip('/')}/.well-known/openid-configuration"
    response = httpx.get(url, timeout=5.0)
    response.raise_for_status()
    return json.loads(response.text)
