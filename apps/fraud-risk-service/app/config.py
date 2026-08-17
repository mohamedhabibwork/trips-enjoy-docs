"""Settings + DB engine factory for fraud-risk-service.

Per docs/shared/PLATFORM_BASELINE.md §2 + per Phase 7 platform-python
lift-forward.
"""
from __future__ import annotations

import logging
import os
from dataclasses import dataclass
from functools import lru_cache

from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from .db import SCHEMA

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class Settings:
    fraud_risk_db_url: str = os.environ.get(
        "FRAUD_RISK_SERVICE_DB_URL",
        "postgresql+asyncpg://postgres:postgres@localhost:5432/trips_enjoy",
    )
    fraud_risk_kafka_bootstrap: str = os.environ.get(
        "FRAUD_RISK_SERVICE_KAFKA_BOOTSTRAP_SERVERS",
        "http://81.208.166.110:9092",
    )
    fraud_risk_outbox_poll_ms: int = int(os.environ.get("FRAUD_RISK_OUTBOX_POLL_INTERVAL_MS", "200"))
    fraud_risk_log_level: str = os.environ.get("FRAUD_RISK_LOG_LEVEL", "INFO")
    fraud_risk_security_enabled: bool = os.environ.get(
        "FRAUD_RISK_SECURITY_ENABLED", "true"
    ).lower() == "true"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()


def build_session_factory(settings: Settings | None = None) -> async_sessionmaker:
    settings = settings or get_settings()
    engine = create_async_engine(
        settings.fraud_risk_db_url,
        pool_pre_ping=True,
        pool_size=20,
        max_overflow=5,
    )
    return async_sessionmaker(engine, expire_on_commit=False)