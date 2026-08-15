"""Device fingerprinting service.

Per docs/services/fraud-risk-service/INTEGRATION.md §1 the fingerprint
service records the (subject_id, device fingerprint) tuple, tracks
seen_count + trust_score, and exposes a sync API.
"""
from __future__ import annotations

import uuid
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..db import DeviceFingerprint, OutboxEvent


class DeviceFingerprintService:
    def __init__(self, session_factory) -> None:
        self._session_factory = session_factory

    @staticmethod
    def compute_trust_score(seen_count: int, distinct_subjects: int = 1) -> float:
        """Compute trust score from seen_count.

        Heuristic: trust = 1 - min(seen_count, 10) / 10 * 0.5
          1 sighting    -> trust ~ 0.95
          10 sightings  -> trust ~ 0.50
          >10 sightings -> trust < 0.50 (suspicious repeated use)
        A higher distinct_subjects count lowers trust further.
        """
        base = 1.0 - min(seen_count, 10) / 10.0 * 0.5
        penalty = max(0, (distinct_subjects - 1)) * 0.10
        return max(0.0, min(1.0, base - penalty))

    async def record(
        self,
        fingerprint: str,
        subject_id: uuid.UUID,
        user_agent: str | None,
        ip_address: str | None,
        device_class: str | None,
        actor_id: uuid.UUID,
        correlation_id: uuid.UUID,
    ) -> DeviceFingerprint:
        async with self._session_factory() as session:
            stmt = select(DeviceFingerprint).where(
                DeviceFingerprint.fingerprint == fingerprint
            )
            existing = (await session.execute(stmt)).scalar_one_or_none()
            now = datetime.now(timezone.utc)
            if existing is not None:
                existing.last_seen_at = now
                existing.seen_count += 1
                existing.subject_id = subject_id
                existing.user_agent = user_agent
                existing.ip_address = ip_address
                existing.device_class = device_class
                existing.trust_score = self.compute_trust_score(existing.seen_count)
                existing.row_version += 1
                await session.commit()
                await session.refresh(existing)
                row = existing
            else:
                row = DeviceFingerprint(
                    fingerprint=fingerprint,
                    subject_id=subject_id,
                    user_agent=user_agent,
                    ip_address=ip_address,
                    device_class=device_class,
                    first_seen_at=now,
                    last_seen_at=now,
                    seen_count=1,
                    trust_score=self.compute_trust_score(1),
                    row_version=1,
                    created_by=actor_id,
                )
                session.add(row)
                await session.commit()
                await session.refresh(row)

            outbox = OutboxEvent(
                id=uuid.uuid4(),
                aggregate_type="DeviceFingerprint",
                aggregate_id=uuid.UUID(int=0),  # placeholder; not used by consumers
                event_type="fraud.device.fingerprint.recorded.v1",
                topic="fraud.device.fingerprint.recorded.v1",
                payload={
                    "fingerprint": fingerprint,
                    "subject_id": str(subject_id),
                    "seen_count": row.seen_count,
                    "trust_score": float(row.trust_score),
                },
                correlation_id=correlation_id,
                created_by=actor_id,
            )
            session.add(outbox)
            await session.commit()
        return row