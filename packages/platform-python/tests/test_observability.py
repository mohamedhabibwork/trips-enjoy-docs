from __future__ import annotations

from platform_python.observability import get_tracer, init_tracer


def test_init_tracer_idempotent() -> None:
    t1 = init_tracer("test-service", env="dev", region="local")
    t2 = init_tracer("test-service", env="dev", region="local")
    assert t1 is t2


def test_get_tracer_after_init() -> None:
    t = get_tracer("test-service")
    assert t is not None
