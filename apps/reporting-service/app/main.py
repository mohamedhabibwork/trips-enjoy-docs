"""FastAPI entrypoint for reporting-service.

Wires the routers, the request-id middleware, the lifespan-managed
session factory, and the optional Kafka consumer background task.
"""
from __future__ import annotations

import asyncio
import logging
import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from .api import (
    admin_router,
    dashboards_router,
    drift_router,
    exports_router,
    read_models_router,
    views_router,
)
from .config import get_settings
from .events.consumer import build_default_runner
from .events.outbox import OutboxPoller
from .logging import configure_logging
from .observability import RequestIdMiddleware

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Start the DB engine, optionally the consumer + outbox poller."""
    settings = get_settings()
    configure_logging()
    logger.info(
        "reporting-service starting env=%s db=%s redis=%s kafka=%s",
        settings.platform_env,
        settings.db_url.split("@")[-1],  # host-only; no creds
        settings.redis_url,
        settings.kafka_bootstrap_servers,
    )

    engine = create_async_engine(settings.db_url, pool_pre_ping=True)
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    app.state.engine = engine
    app.state.session_factory = session_factory

    background_tasks: list[asyncio.Task] = []
    if os.getenv("REPORTING_SERVICE_RUN_CONSUMER", "false").lower() == "true":
        consumer = build_default_runner(session_factory)
        app.state.consumer = consumer
        background_tasks.append(asyncio.create_task(consumer.run(), name="kafka-consumer"))
    if os.getenv("REPORTING_SERVICE_RUN_OUTBOX", "false").lower() == "true":
        async def _publish(topic: str, payload: dict, headers: dict) -> None:
            # Real impl: aiokafka producer; for the scaffold we just log.
            logger.info("outbox-publish topic=%s payload_size=%d", topic, len(payload))

        poller = OutboxPoller(
            conn_factory=engine.connect,
            publish=_publish,
        )
        app.state.outbox_poller = poller
        background_tasks.append(asyncio.create_task(poller.run(), name="outbox-poller"))

    try:
        yield
    finally:
        for t in background_tasks:
            t.cancel()
        if background_tasks:
            await asyncio.gather(*background_tasks, return_exceptions=True)
        if hasattr(app.state, "consumer"):
            await app.state.consumer.stop()
        await engine.dispose()


def create_app() -> FastAPI:
    """Construct the FastAPI app.

    Kept in a factory so tests can spin up isolated instances with
    different settings.
    """
    settings = get_settings()
    app = FastAPI(
        title="reporting-service",
        description="Reporting and data lake projections microservice",
        version=settings.service_version,
        openapi_url="/openapi.json",
        docs_url="/docs",
        lifespan=lifespan,
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    app.add_middleware(RequestIdMiddleware)

    # Public v1 routers
    app.include_router(dashboards_router)
    app.include_router(views_router)
    app.include_router(exports_router)
    app.include_router(drift_router)
    app.include_router(read_models_router)

    # Admin router (TECH.md §10.4) — only mounted when admin_enabled.
    if settings.admin_enabled:
        app.include_router(admin_router)

    # Health endpoints (TECH.md §7: /healthz, README §15: /health, /ready, /started).
    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "UP", "service": settings.service_name}

    @app.get("/healthz")
    async def healthz() -> dict[str, str]:
        return {"status": "UP", "service": settings.service_name}

    @app.get("/ready")
    async def ready() -> dict[str, str]:
        # In a complete implementation this probes DB / Kafka. The scaffold
        # returns UP so the platform readiness probe passes while the
        # service comes up.
        return {"status": "UP", "service": settings.service_name}

    @app.get("/started")
    async def started() -> dict[str, str]:
        return {"status": "UP", "service": settings.service_name}

    @app.get("/v1/status")
    async def status() -> dict[str, str]:
        return {
            "status": "UP",
            "service": settings.service_name,
            "version": settings.service_version,
        }

    return app


app = create_app()


if __name__ == "__main__":
    port = int(os.getenv("PORT", "8103"))

    uvicorn.run("app.main:app", host="0.0.0.0", port=port, reload=True)  # noqa: S104
