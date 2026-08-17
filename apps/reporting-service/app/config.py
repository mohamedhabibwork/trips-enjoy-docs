"""Typed application configuration.

Reads from env vars (REPORTING_SERVICE_* and a few platform-wide vars).
Defaults match .env.example so the service starts in local-dev without
needing any real credentials.
"""
from __future__ import annotations

import os
from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """reporting-service configuration."""

    model_config = SettingsConfigDict(
        env_prefix="REPORTING_SERVICE_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    # ----- Platform -----
    platform_env: str = Field(default="dev")
    service_name: str = Field(default="reporting-service")
    service_version: str = Field(default="1.0.0")

    # ----- HTTP -----
    http_port: int = Field(default=8103)
    admin_port: int = Field(default=8104)
    admin_enabled: bool = Field(default=True)

    # ----- Database -----
    db_url: str = Field(
        default="postgresql+asyncpg://postgres@0.0.0.0:5432/trips_enjoy?"
        "options=-c%20search_path%3Dreporting,public"
    )
    db_sync_url: str = Field(
        default="postgresql+psycopg2://postgres@0.0.0.0:5432/trips_enjoy?"
        "options=-c%20search_path%3Dreporting,public"
    )
    db_username: str = Field(default="postgres")
    db_password: str = Field(default="")

    # ----- Cache -----
    redis_url: str = Field(default="redis://0.0.0.0:6379")

    # ----- Kafka -----
    kafka_bootstrap_servers: str = Field(default="http://81.208.166.110:9092")
    kafka_consumer_group: str = Field(default="reporting-service")
    kafka_dlq_suffix: str = Field(default=".dlq")

    # ----- Keycloak -----
    keycloak_base_url: str = Field(default="http://0.0.0.0:8181")
    keycloak_issuer_uri: str = Field(
        default="http://0.0.0.0:8181/realms/platform-services"
    )
    keycloak_jwks_uri: str = Field(
        default=(
            "http://0.0.0.0:8181/realms/platform-services/protocol/"
            "openid-connect/certs"
        )
    )
    keycloak_admin_realm: str = Field(default="master")
    keycloak_admin_client_id: str = Field(default="")
    keycloak_admin_client_secret: str = Field(default="")
    keycloak_admin_username: str = Field(default="")
    keycloak_admin_password: str = Field(default="")

    # ----- Object storage -----
    s3_bucket: str = Field(default="trips-enjoy-platform-reporting")
    s3_region: str = Field(default="us-east-1")
    s3_endpoint_url: str = Field(default="")  # for local dev (MinIO)

    # ----- Schedules -----
    reconciliation_cron: str = Field(default="0 4 * * *")

    # ----- Tuning -----
    view_lag_threshold_seconds: int = Field(default=300)
    parquet_row_group_size: int = Field(default=100_000)
    dashboards_refresh_seconds: int = Field(default=30)
    query_cache_ttl_seconds: int = Field(default=60)


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Lazy, cached settings accessor.

    A process-wide singleton keeps the settings object immutable across
    requests; tests call `Settings.model_construct(...)` or `get_settings.cache_clear()`
    to reset.
    """
    # Allow PLATFORM_ENV override that isn't prefix-scoped.
    platform_env = os.getenv("PLATFORM_ENV")
    if platform_env:
        os.environ.setdefault("REPORTING_SERVICE_PLATFORM_ENV", platform_env)
    return Settings()
