"""Verify fraud-risk-service imports the shared platform-python library.

This test imports the cross-cutting library at packages/platform-python/
to prove the editable install + Python path resolution are wired
correctly. Fails to collect if the dependency is missing.
"""
from __future__ import annotations

from platform_python.errormodel import ErrorCode, ErrorEnvelope
from platform_python.money import Money


def test_link_to_platform_python() -> None:
    # errormodel
    e = ErrorEnvelope.build(ErrorCode.NOT_FOUND, detail="missing", instance="/x")
    assert e.status == 404
    assert e.code is ErrorCode.NOT_FOUND

    # money
    m = Money.of_minor(1999, "USD")
    assert m.minor == 1999
    assert m.major == Money.of("19.99", "USD").major
