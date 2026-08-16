"""Tests for the auth dependency layer."""
from __future__ import annotations

import os
import uuid

from fastapi import Depends, FastAPI, HTTPException
from fastapi.testclient import TestClient

os.environ["REPORTING_SERVICE_AUTH_STUB_MODE"] = "true"

from app.auth import Principal, decode_bearer, require_role, require_scope


def _make_app() -> FastAPI:
    app = FastAPI()

    @app.get("/who")
    async def who(principal: Principal = Depends(decode_bearer)) -> dict[str, str]:
        return {"actor_id": principal.actor_id, "username": principal.username}

    @app.get("/needs-admin")
    async def admin_only(
        principal: Principal = Depends(require_role("reporting.admin")),
    ) -> dict[str, str]:
        return {"actor_id": principal.actor_id}

    @app.get("/needs-scope")
    async def scoped(
        principal: Principal = Depends(require_scope("reporting.export.revenue")),
    ) -> dict[str, str]:
        return {"actor_id": principal.actor_id}

    return app


def test_decode_bearer_succeeds_in_stub_mode() -> None:
    app = _make_app()
    actor = str(uuid.uuid4())
    client = TestClient(app)
    response = client.get("/who", headers={"Authorization": f"Bearer {actor}"})
    assert response.status_code == 200
    body = response.json()
    assert body["actor_id"] == actor
    assert body["username"].startswith("stub:")


def test_decode_bearer_requires_authorization() -> None:
    app = _make_app()
    client = TestClient(app)
    response = client.get("/who")
    assert response.status_code == 401


def test_decode_bearer_rejects_non_uuid_actor_in_stub_mode() -> None:
    app = _make_app()
    client = TestClient(app)
    response = client.get("/who", headers={"Authorization": "Bearer not-a-uuid"})
    assert response.status_code == 401


def test_require_role_in_stub_mode_has_admin() -> None:
    app = _make_app()
    client = TestClient(app)
    actor = str(uuid.uuid4())
    response = client.get(
        "/needs-admin",
        headers={"Authorization": f"Bearer {actor}"},
    )
    assert response.status_code == 200


def test_require_scope_missing_returns_403() -> None:
    """In stub mode the principal lacks arbitrary per-export scopes."""

    # Build a principal with no scopes by overriding the stub default.
    from app.auth import tokens as auth_tokens

    original_stub = auth_tokens._stub_decode

    def _no_scope_stub(authorization):
        if not authorization or not authorization.lower().startswith("bearer "):
            raise HTTPException(status_code=401)
        actor = authorization.split(" ", 1)[1].strip()
        return Principal(actor_id=actor, username="x", roles=set(), scopes=set())

    auth_tokens._stub_decode = _no_scope_stub
    try:
        app = _make_app()
        client = TestClient(app)
        actor = str(uuid.uuid4())
        response = client.get(
            "/needs-scope",
            headers={"Authorization": f"Bearer {actor}"},
        )
        assert response.status_code == 403
    finally:
        auth_tokens._stub_decode = original_stub
