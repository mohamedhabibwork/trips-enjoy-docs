"""Tests for the request-id middleware (ADR-0019 propagation)."""
from __future__ import annotations

import uuid

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.observability import RequestIdMiddleware, current_request_id


def _make_app() -> FastAPI:
    app = FastAPI()
    app.add_middleware(RequestIdMiddleware)

    @app.get("/who")
    async def who() -> dict[str, str]:
        return {"request_id": current_request_id() or ""}

    return app


def test_middleware_propagates_header() -> None:
    client = TestClient(_make_app())
    incoming = str(uuid.uuid4())
    response = client.get("/who", headers={"X-Request-Id": incoming})
    assert response.status_code == 200
    assert response.json()["request_id"] == incoming
    assert response.headers["X-Request-Id"] == incoming


def test_middleware_accepts_correlation_id_alias() -> None:
    client = TestClient(_make_app())
    incoming = str(uuid.uuid4())
    response = client.get("/who", headers={"X-Correlation-Id": incoming})
    assert response.status_code == 200
    assert response.json()["request_id"] == incoming


def test_middleware_mints_id_when_missing() -> None:
    client = TestClient(_make_app())
    response = client.get("/who")
    assert response.status_code == 200
    rid = response.json()["request_id"]
    assert rid
    # Must be a valid UUID.
    uuid.UUID(rid)
    assert response.headers["X-Request-Id"] == rid
