"""Idempotency service — newer PK-on-key pattern (like pricing-service).

Mirrors the canonical pattern across all graduates. A key written via
record() is replayed verbatim by find_existing().
"""
from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..db import IdempotencyKey


class IdempotencyService:
    """Async wrapper around the idempotency_keys table.

    The fraud-risk-service has high QPS (every score request is idempotent),
    so we use the newer PK-on-key pattern: `idempotency_key` (UUID) is
    itself the PK, vs the legacy scope+key composite pattern.
    """

    def __init__(self, session_factory) -> None:
        self._session_factory = session_factory

    async def find_existing(
        self, idempotency_key: uuid.UUID
    ) -> IdempotencyKey | None:
        async with self._session_factory() as session:
            return await session.get(IdempotencyKey, idempotency_key)

    async def record(
        self,
        idempotency_key: uuid.UUID,
        request_hash: str,
        response_status: int,
        response_body: dict,
        actor_id: uuid.UUID,
        ttl_seconds: int = 86_400,
    ) -> None:
        assert len(request_hash) == 64, "request_hash must be SHA-256 hex (64 chars)"
        now = datetime.now(timezone.utc)
        async with self._session_factory() as session:
            existing = await session.get(IdempotencyKey, idempotency_key)
            if existing is not None:
                raise ValueError(
                    f"idempotency key {idempotency_key} already recorded"
                )
            row = IdempotencyKey(
                idempotency_key=idempotency_key,
                request_hash=request_hash,
                response_status=response_status,
                response_body=response_body,
                actor_id=actor_id,
                created_at=now,
                expires_at=now + timedelta(seconds=ttl_seconds),
            )
            session.add(row)
            await session.commit()