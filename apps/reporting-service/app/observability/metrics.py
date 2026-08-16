"""Minimal RED metrics store.

The production deployment wires `prometheus-client` via
`starlette-prometheus` (TECH.md §7). For tests + local-dev we keep an
in-memory counter + histogram so the lifecycle + dependencies can be
validated without Prometheus.

Metrics names (SRS §22):
  - `http_requests_total{route, method, status}`
  - `http_request_duration_seconds{route, method, status}`
  - `reporting_view_lag{view_name}`
  - `reporting_export_seconds{name, format}`
  - `reporting_drift_findings_total{view_name}`
  - `reporting_projection_seconds{view_name}`
"""
from __future__ import annotations

import threading
import time
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Any


@dataclass
class _Counter:
    value: float = 0.0
    labels: dict[str, str] = field(default_factory=dict)


@dataclass
class _Histogram:
    samples: list[float] = field(default_factory=list)
    labels: dict[str, str] = field(default_factory=dict)


class Metrics:
    """Process-local metrics store.

    Thread-safe via a single lock; reads are O(n) over the registered
    series which is acceptable for the test/dev path. Production code
    replaces this with `prometheus_client`.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._counters: dict[tuple[str, frozenset], float] = defaultdict(float)
        self._histograms: dict[tuple[str, frozenset], list[float]] = defaultdict(list)

    # ----- public API -----------------------------------------------------

    def record_http(
        self, *, route: str, method: str, status: int, duration_seconds: float
    ) -> None:
        labels = frozenset(
            {"route": route, "method": method, "status": str(status)}.items()
        )
        with self._lock:
            self._counters[("http_requests_total", labels)] += 1
            self._histograms[("http_request_duration_seconds", labels)].append(
                duration_seconds
            )

    def record_view_lag(self, view_name: str, lag_seconds: float) -> None:
        labels = frozenset({"view_name": view_name}.items())
        with self._lock:
            self._histograms[("reporting_view_lag", labels)].append(lag_seconds)

    def record_export(self, name: str, format: str, duration_seconds: float) -> None:
        labels = frozenset({"name": name, "format": format}.items())
        with self._lock:
            self._counters[("reporting_export_seconds_count", labels)] += 1
            self._histograms[("reporting_export_seconds", labels)].append(
                duration_seconds
            )

    def record_drift(self, view_name: str) -> None:
        labels = frozenset({"view_name": view_name}.items())
        with self._lock:
            self._counters[("reporting_drift_findings_total", labels)] += 1

    def record_projection(self, view_name: str, duration_seconds: float) -> None:
        labels = frozenset({"view_name": view_name}.items())
        with self._lock:
            self._counters[("reporting_projection_seconds_count", labels)] += 1
            self._histograms[("reporting_projection_seconds", labels)].append(
                duration_seconds
            )

    def snapshot(self) -> dict[str, Any]:
        """Return a serialisable snapshot for `/metrics` and tests."""
        with self._lock:
            return {
                "counters": {
                    f"{name}|{sorted(labels)}": value
                    for (name, labels), value in self._counters.items()
                },
                "histograms": {
                    f"{name}|{sorted(labels)}": list(values)
                    for (name, labels), values in self._histograms.items()
                },
            }


_metrics = Metrics()


def get_metrics() -> Metrics:
    return _metrics


def record_http(*, route: str, method: str, status: int, duration_seconds: float) -> None:
    _metrics.record_http(
        route=route, method=method, status=status, duration_seconds=duration_seconds
    )


class timer:
    """Context manager that records a duration into a named metric."""

    def __init__(self, callback, **labels: Any) -> None:
        self._callback = callback
        self._labels = labels
        self._start = 0.0

    def __enter__(self) -> timer:
        self._start = time.monotonic()
        return self

    def __exit__(self, *exc: Any) -> None:
        duration = time.monotonic() - self._start
        self._callback(duration=duration, **self._labels)
