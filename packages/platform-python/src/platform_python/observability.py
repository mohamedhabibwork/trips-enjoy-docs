"""OpenTelemetry tracer + meter provider initialisation shared by every Python service.

Mirrors ``platform-spring-boot-observability`` (Kotlin) and
``platform-go/observability`` (Go).
"""
from __future__ import annotations

import logging
import os
from typing import Any

from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor, ConsoleSpanExporter
from opentelemetry.semconv.resource import ResourceAttributes

log = logging.getLogger(__name__)

_initialized = False


def init_tracer(
    service_name: str,
    *,
    env: str | None = None,
    region: str | None = None,
    tenant: str | None = None,
    otlp_endpoint: str | None = None,
    console_fallback: bool = False,
) -> trace.Tracer:
    """Initialise the global TracerProvider. Idempotent.

    Args:
        service_name: Human-readable service name (e.g. ``fraud-risk-service``).
        env: Environment tag (``dev``, ``stg``, ``prod``). Defaults to ``PLATFORM_ENV``.
        region: Region tag (e.g. ``eu-west-1``). Defaults to ``PLATFORM_REGION``.
        tenant: Tenant slug. Defaults to ``PLATFORM_TENANT``.
        otlp_endpoint: OTLP gRPC endpoint. Defaults to ``OTEL_EXPORTER_OTLP_ENDPOINT``.
        console_fallback: If True and no OTLP endpoint, writes spans to stdout.
    """
    global _initialized
    if _initialized:
        return trace.get_tracer(service_name)

    env = env or os.getenv("PLATFORM_ENV", "dev")
    region = region or os.getenv("PLATFORM_REGION", "local")
    tenant = tenant or os.getenv("PLATFORM_TENANT", "global")
    otlp_endpoint = otlp_endpoint or os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT", "")

    resource = Resource.create(
        {
            ResourceAttributes.SERVICE_NAME: service_name,
            ResourceAttributes.DEPLOYMENT_ENVIRONMENT: env,
            "region": region,
            "tenant": tenant,
        }
    )
    provider = TracerProvider(resource=resource)
    if otlp_endpoint:
        provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=otlp_endpoint, insecure=True)))
    elif console_fallback:
        provider.add_span_processor(BatchSpanProcessor(ConsoleSpanExporter()))
    trace.set_tracer_provider(provider)
    _initialized = True
    log.info("platform-python.tracer.init service=%s env=%s region=%s otlp=%s", service_name, env, region, otlp_endpoint)
    return trace.get_tracer(service_name)


def get_tracer(name: str) -> trace.Tracer:
    """Return the named tracer (initialises the provider with default config if not yet set)."""
    if not _initialized:
        init_tracer(name)
    return trace.get_tracer(name)
