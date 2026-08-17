from __future__ import annotations

import pytest

from platform_python.settings import make_settings


def test_make_settings_prefix(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("FRAUD_RISK_SERVICE_DB_URL", "postgresql://x/y")
    monkeypatch.setenv("FRAUD_RISK_SERVICE_REDIS_HOST", "redis-host")
    s = make_settings("fraud-risk-service", "FRAUD_RISK_SERVICE")
    assert s.service_name == "fraud-risk-service"
    assert s.db_url == "postgresql://x/y"
    # redis_host alias is REDIS_HOST (platform-wide); FRAUD_RISK_SERVICE_REDIS_HOST
    # would only resolve if the subclass mapped it. Use the platform-level alias.
    monkeypatch.setenv("REDIS_HOST", "redis-host")
    s2 = make_settings("fraud-risk-service", "FRAUD_RISK_SERVICE")
    assert s2.redis_host == "redis-host"


def test_make_settings_defaults() -> None:
    s = make_settings("reporting-service", "REPORTING_SERVICE")
    assert s.platform_env == "dev"
    assert s.platform_region == "local"
    assert s.platform_tenant == "global"
    assert s.kafka_consumer_group == "platform-python"
