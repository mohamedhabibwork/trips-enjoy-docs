"""FastAPI entrypoint for fraud-risk-service.

Wires the routers, the request-id middleware, the lifespan-managed
session factory, the outbox publisher, and the Kafka consumer background
task.
"""
from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .api import build_router
from .config import build_session_factory, get_settings
from .conductor_workers import FraudConductorWorkers
from .kafka_consumer import FakeKafkaConsumer, KafkaConsumerRunner
from .observability import RequestIdMiddleware, configure_logging
from .services import (
    BlocklistService,
    DeviceFingerprintService,
    EvaluationService,
    IdempotencyService,
    ModelService,
    OutboxPublisher,
    RiskScoringService,
)

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Start the DB session factory + OutboxPublisher + Kafka consumer."""
    settings = get_settings()
    configure_logging(settings.fraud_risk_log_level)
    session_factory = build_session_factory(settings)

    idempotency = IdempotencyService(session_factory)
    scoring = RiskScoringService(session_factory)
    fingerprints = DeviceFingerprintService(session_factory)
    blocklist_svc = BlocklistService(session_factory)
    model_svc = ModelService(session_factory)
    evaluation_svc = EvaluationService(session_factory)

    # No-op Kafka producer for the OutboxPublisher in this scope
    # (production wires aiokafka / confluent-kafka). The OutboxPublisher
    # will gracefully fail publishes and re-try with exponential backoff.
    class NoopKafkaProducer:
        async def send(self, *, topic, key, value):
            return None

    publisher = OutboxPublisher(session_factory, NoopKafkaProducer())
    await publisher.start()
    app.state.publisher = publisher

    # In-memory Kafka consumer for identity.session.created.v1.
    consumer = FakeKafkaConsumer()
    consumer.register_topic("identity.session.created.v1")
    consumer_runner = KafkaConsumerRunner(consumer, fingerprints, session_factory)
    app.state.consumer = consumer
    app.state.consumer_runner = consumer_runner

    # Wire the router
    app.include_router(
        build_router(
            scoring=scoring,
            fingerprints=fingerprints,
            blocklist_svc=blocklist_svc,
            model_svc=model_svc,
            evaluation_svc=evaluation_svc,
            idempotency=idempotency,
        )
    )

    # Conductor workers (registered on app.state for the Conductor SDK
    # to discover when wired)
    app.state.conductor_workers = FraudConductorWorkers(scoring, blocklist_svc)

    logger.info(
        "fraud-risk-service started env=%s db=%s",
        settings.fraud_risk_db_url.split("@")[-1],
        settings.fraud_risk_db_url,
    )

    try:
        yield
    finally:
        await publisher.stop()


def create_app() -> FastAPI:
    settings = get_settings()
    app = FastAPI(
        title="fraud-risk-service",
        description="Risk scoring & fraud evaluation microservice",
        version="1.0.0",
        openapi_url="/openapi.json",
        docs_url="/docs",
    )
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    app.add_middleware(RequestIdMiddleware)

    @app.get("/health")
    async def health():
        return {"status": "UP", "service": "fraud-risk-service"}

    @app.get("/v1/status")
    async def status():
        return {"status": "UP", "service": "fraud-risk-service", "version": "1.0.0"}

    app.router.lifespan_context = lifespan
    return app


app = create_app()


if __name__ == "__main__":
    import os

    settings = get_settings()
    port = int(os.environ.get("PORT", "8095"))
    uvicorn.run("app.main:app", host="0.0.0.0", port=port, reload=settings.fraud_risk_log_level == "DEBUG")