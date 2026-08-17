"""Verify reporting-service imports the shared platform-python library.

This test imports the cross-cutting library at packages/platform-python/
to prove the editable install + Python path resolution are wired
correctly. Fails to collect if the dependency is missing.
"""
from __future__ import annotations

from platform_python.errormodel import ErrorCode, ErrorEnvelope
from platform_python.money import Money
from platform_python.settings import make_settings


def test_link_to_platform_python() -> None:
    e = ErrorEnvelope.build(ErrorCode.NOT_FOUND, detail="missing", instance="/x")
    assert e.status == 404

    m = Money.of_minor(1999, "USD")
    assert m.minor == 1999

    s = make_settings("reporting-service", "REPORTING_SERVICE")
    assert s.service_name == "reporting-service"
