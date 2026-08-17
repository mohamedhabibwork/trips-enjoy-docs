"""Shared pytest fixtures for reporting-service tests."""
from __future__ import annotations

import os
import uuid

import pytest
from fastapi.testclient import TestClient

# Force stub auth + soft mode before the app reads config.
os.environ.setdefault("REPORTING_SERVICE_AUTH_STUB_MODE", "true")
os.environ.setdefault("REPORTING_SERVICE_AUTH_SOFT_MODE", "true")
os.environ.setdefault("REPORTING_SERVICE_PLATFORM_ENV", "test")


@pytest.fixture
def actor_id() -> str:
    return str(uuid.uuid4())


@pytest.fixture
def auth_header(actor_id: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {actor_id}"}


@pytest.fixture
def client() -> TestClient:
    """Synchronous test client for the FastAPI app.

    Skips the lifespan to avoid pulling in Postgres / Kafka; routers are
    validated with mocked `db_conn` dependencies in the per-test files.
    """
    from app.main import create_app

    app = create_app()
    return TestClient(app, raise_server_exceptions=False)


@pytest.fixture
def settings():
    from app.config import get_settings

    get_settings.cache_clear()  # type: ignore[attr-defined]
    return get_settings()
