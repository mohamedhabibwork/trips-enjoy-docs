"""Pydantic settings + Kafka topic conventions shared by every Python service.

Mirrors the per-service ``<service>_*.env`` block by exposing the
canonical environment-derived settings below. Subclasses set
``service_name`` and ``prefix``.
"""
from __future__ import annotations

from typing import Annotated

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class PlatformSettings(BaseSettings):
    """Base settings for every Python service.

    Subclasses set ``model_config.service_name`` and ``prefix`` so
    environment variables resolve to ``<SERVICE>_DB_URL``,
    ``<SERVICE>_REDIS_HOST``, etc.
    """

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # ----- Service identity -----
    service_name: str = "platform-service"
    platform_env: Annotated[str, Field(default="dev", alias="PLATFORM_ENV")] = "dev"
    platform_region: Annotated[str, Field(default="local", alias="PLATFORM_REGION")] = "local"
    platform_tenant: Annotated[str, Field(default="global", alias="PLATFORM_TENANT")] = "global"

    # ----- Database -----
    db_url: Annotated[str, Field(default="postgresql://postgres:postgres@0.0.0.0:5432/trips_enjoy")] = ""
    db_schema: Annotated[str, Field(default="public")] = "public"
    db_pool_min: Annotated[int, Field(default=5)] = 5
    db_pool_max: Annotated[int, Field(default=20)] = 20

    # ----- Redis -----
    redis_host: Annotated[str, Field(default="0.0.0.0", alias="REDIS_HOST")] = "0.0.0.0"
    redis_port: Annotated[int, Field(default=6379, alias="REDIS_PORT")] = 6379
    redis_password: Annotated[str, Field(default="", alias="REDIS_PASSWORD")] = ""

    # ----- Kafka -----
    kafka_bootstrap: Annotated[str, Field(default="http://localhost:9092", alias="KAFKA_BOOTSTRAP_SERVERS")] = ""
    kafka_consumer_group: Annotated[str, Field(default="platform-python")] = "platform-python"

    # ----- Keycloak / OIDC -----
    keycloak_base_url: Annotated[str, Field(default="http://0.0.0.0:8181", alias="KEYCLOAK_BASE_URL")] = ""
    keycloak_issuer: Annotated[str, Field(default="")] = ""
    keycloak_jwks: Annotated[str, Field(default="")] = ""
    keycloak_admin_realm: Annotated[str, Field(default="master", alias="KEYCLOAK_ADMIN_REALM")] = "master"
    keycloak_admin_client_id: Annotated[str, Field(default="admin-cli", alias="KEYCLOAK_ADMIN_CLIENT_ID")] = "admin-cli"
    keycloak_admin_username: Annotated[str, Field(default="admin", alias="KEYCLOAK_ADMIN_USERNAME")] = "admin"
    keycloak_admin_password: Annotated[str, Field(default="admin", alias="KEYCLOAK_ADMIN_PASSWORD")] = "admin"


def make_settings(
    service_name: str,
    prefix: str,
    **overrides: object,
) -> PlatformSettings:
    """Subclass factory that pins ``service_name`` and ``prefix``.

    Args:
        service_name: Display name for the consuming service.
        prefix: Env-var prefix, e.g. ``"FRAUD_RISK_SERVICE"`` ->
            ``FRAUD_RISK_SERVICE_DB_URL`` etc.
        **overrides: Field overrides for testing.
    """
    cls = type(
        f"{prefix}Settings",
        (PlatformSettings,),
        {
            "model_config": SettingsConfigDict(
                env_prefix=f"{prefix}_",
                env_file=".env",
                case_sensitive=False,
                extra="ignore",
            ),
        },
    )
    settings = cls(service_name=service_name, **overrides)  # type: ignore[call-arg]
    return settings  # type: ignore[return-value]
