"""JWKS cache + JWT signature verification.

The service trusts Keycloak's `/realms/<realm>/protocol/openid-connect/certs`
endpoint for signature verification. The cache is refreshed on demand
(every 10 minutes by default) and `verify()` accepts the same arguments
as the platform's `JwtAuthFilter` for Kotlin / `coreos/go-oidc` for Go so
the RBAC contract is uniform across services.

Production note: the verification implementation in this scaffold uses
the `cryptography` library if available; if `cryptography` is not
installed (it is a transitive of FastAPI in some envs) we raise a clear
configuration error rather than silently skipping verification.
"""
from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass, field
from typing import Any

import httpx

from ..config import get_settings


@dataclass
class JWKSCache:
    """In-memory JWKS cache.

    One cache per process; instances are safe to share across coroutines
    (the lock protects the underlying dict only).
    """

    url: str
    ttl_seconds: int = 600
    _keys: dict[str, dict[str, Any]] = field(default_factory=dict)
    _fetched_at: float = 0.0
    _lock: asyncio.Lock = field(default_factory=asyncio.Lock)

    async def _refresh(self) -> None:
        settings = get_settings()
        async with httpx.AsyncClient(timeout=5.0) as client:
            response = await client.get(self.url)
            response.raise_for_status()
        body = response.json()
        new_keys: dict[str, dict[str, Any]] = {}
        for key in body.get("keys", []):
            kid = key.get("kid")
            if isinstance(kid, str):
                new_keys[kid] = key
        self._keys = new_keys
        self._fetched_at = time.monotonic()
        # settings is referenced to keep the linter happy about usage
        _ = settings

    async def get(self, kid: str) -> dict[str, Any] | None:
        if not self._keys or (time.monotonic() - self._fetched_at) > self.ttl_seconds:
            async with self._lock:
                if not self._keys or (time.monotonic() - self._fetched_at) > self.ttl_seconds:
                    await self._refresh()
        return self._keys.get(kid)

    async def verify(self, token: str) -> dict[str, Any]:
        """Verify the JWT signature and return the decoded claims.

        Raises `JWKSError` on any failure.
        """
        try:
            import jwt  # type: ignore[import-not-found]  # PyJWT
        except ImportError as exc:
            raise JWKSError(
                "PyJWT is required for strict verification; "
                "install `pyjwt[crypto]` or run in stub mode."
            ) from exc

        try:
            unverified_header = jwt.get_unverified_header(token)
            unverified_payload = jwt.decode(token, options={"verify_signature": False})
        except Exception as exc:
            raise JWKSError(f"malformed JWT: {exc}") from exc

        kid = unverified_header.get("kid")
        if not isinstance(kid, str):
            raise JWKSError("JWT header missing kid")

        key = await self.get(kid)
        if key is None:
            raise JWKSError(f"no JWKS entry for kid={kid}")

        try:
            claims = jwt.decode(
                token,
                key,
                algorithms=[key.get("alg", "RS256")],
                issuer=get_settings().keycloak_issuer_uri,
                options={"verify_aud": False},
            )
        except Exception as exc:
            raise JWKSError(f"JWT verification failed: {exc}") from exc
        return {**unverified_payload, **claims}


class JWKSError(Exception):
    """Raised by `JWKSCache.verify` for any verification failure."""


_jwks_cache: JWKSCache | None = None


def get_jwks_cache() -> JWKSCache:
    global _jwks_cache
    if _jwks_cache is None:
        settings = get_settings()
        _jwks_cache = JWKSCache(url=settings.keycloak_jwks_uri)
    return _jwks_cache
