"""Blocklist + admin services (Model, Evaluation, Blocklist)."""
from __future__ import annotations

import uuid
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..db import Action, Blocklist, Evaluation, Model, OutboxEvent


class BlocklistService:
    def __init__(self, session_factory) -> None:
        self._session_factory = session_factory

    async def list_active(self) -> list[Blocklist]:
        now = datetime.now(timezone.utc)
        async with self._session_factory() as session:
            stmt = select(Blocklist).where(
                (Blocklist.expires_at.is_(None)) | (Blocklist.expires_at > now)
            )
            return list((await session.execute(stmt)).scalars().all())

    async def add(
        self,
        kind: str,
        value: str,
        reason: str,
        actor_id: uuid.UUID,
        expires_at: datetime | None = None,
        correlation_id: uuid.UUID | None = None,
    ) -> Blocklist:
        async with self._session_factory() as session:
            # Check for existing (kind, value)
            stmt = select(Blocklist).where(
                Blocklist.kind == kind, Blocklist.value == value
            )
            existing = (await session.execute(stmt)).scalar_one_or_none()
            if existing is not None:
                existing.reason = reason
                existing.expires_at = expires_at
                existing.added_by = actor_id
                existing.updated_at = datetime.now(timezone.utc)
                existing.row_version += 1
                row = existing
                action_kind = "blocklist_updated"
            else:
                row = Blocklist(
                    id=uuid.uuid4(),
                    kind=kind,
                    value=value,
                    reason=reason,
                    added_by=actor_id,
                    expires_at=expires_at,
                )
                session.add(row)
                action_kind = "blocklist_added"
            audit = Action(
                id=uuid.uuid4(),
                subject_id=uuid.UUID(int=0),  # blocklist is not subject-scoped
                subject_kind="device",
                action=action_kind,
                actor_id=actor_id,
                actor_kind="admin",
                reason=reason,
                payload={"kind": kind, "value": value},
                correlation_id=correlation_id or uuid.uuid4(),
            )
            session.add(audit)
            outbox = OutboxEvent(
                id=uuid.uuid4(),
                aggregate_type="Blocklist",
                aggregate_id=row.id,
                event_type="fraud.blocklist.updated.v1",
                topic="fraud.blocklist.updated.v1",
                payload={"kind": kind, "value": value, "action": action_kind},
                correlation_id=correlation_id or uuid.uuid4(),
                created_by=actor_id,
            )
            session.add(outbox)
            await session.commit()
            await session.refresh(row)
        return row

    async def remove(
        self,
        blocklist_id: uuid.UUID,
        actor_id: uuid.UUID,
        correlation_id: uuid.UUID | None = None,
    ) -> None:
        async with self._session_factory() as session:
            row = await session.get(Blocklist, blocklist_id)
            if row is None:
                raise ValueError(f"blocklist {blocklist_id} not found")
            await session.delete(row)
            audit = Action(
                id=uuid.uuid4(),
                subject_id=uuid.UUID(int=0),
                subject_kind="device",
                action="blocklist_remove",
                actor_id=actor_id,
                actor_kind="admin",
                reason=f"removed {row.kind}={row.value}",
                payload={"kind": row.kind, "value": row.value},
                correlation_id=correlation_id or uuid.uuid4(),
            )
            session.add(audit)
            outbox = OutboxEvent(
                id=uuid.uuid4(),
                aggregate_type="Blocklist",
                aggregate_id=blocklist_id,
                event_type="fraud.blocklist.updated.v1",
                topic="fraud.blocklist.updated.v1",
                payload={"kind": row.kind, "value": row.value, "action": "blocklist_remove"},
                correlation_id=correlation_id or uuid.uuid4(),
                created_by=actor_id,
            )
            session.add(outbox)
            await session.commit()


class ModelService:
    def __init__(self, session_factory) -> None:
        self._session_factory = session_factory

    async def list_models(self) -> list[Model]:
        async with self._session_factory() as session:
            stmt = select(Model).order_by(Model.trained_at.desc())
            return list((await session.execute(stmt)).scalars().all())

    async def deploy(
        self,
        model_id: str,
        version: int,
        hyperparameters: dict,
        metrics: dict | None,
        actor_id: uuid.UUID,
        correlation_id: uuid.UUID | None = None,
    ) -> Model:
        now = datetime.now(timezone.utc)
        async with self._session_factory() as session:
            row = Model(
                id=model_id,
                version=version,
                algorithm=hyperparameters.get("algorithm", "unknown"),
                trained_at=now,
                deployed_at=now,
                hyperparameters=hyperparameters,
                metrics=metrics,
                created_by=actor_id,
            )
            session.add(row)
            audit = Action(
                id=uuid.uuid4(),
                subject_id=uuid.UUID(int=0),
                subject_kind="device",
                action="model_deploy",
                actor_id=actor_id,
                actor_kind="admin",
                reason=f"deployed {model_id} v{version}",
                payload={"model_id": model_id, "version": version, "metrics": metrics or {}},
                correlation_id=correlation_id or uuid.uuid4(),
            )
            session.add(audit)
            outbox = OutboxEvent(
                id=uuid.uuid4(),
                aggregate_type="Model",
                aggregate_id=uuid.uuid4(),
                event_type="fraud.model.deployed.v1",
                topic="fraud.model.deployed.v1",
                payload={"model_id": model_id, "version": version},
                correlation_id=correlation_id or uuid.uuid4(),
                created_by=actor_id,
            )
            session.add(outbox)
            await session.commit()
        return row


class EvaluationService:
    def __init__(self, session_factory) -> None:
        self._session_factory = session_factory

    async def list(self) -> list[Evaluation]:
        async with self._session_factory() as session:
            stmt = select(Evaluation).order_by(Evaluation.evaluated_at.desc())
            return list((await session.execute(stmt)).scalars().all())

    async def record(
        self,
        model_id: str,
        dataset: str,
        metrics: dict,
        actor_id: uuid.UUID,
    ) -> Evaluation:
        async with self._session_factory() as session:
            row = Evaluation(
                id=uuid.uuid4(),
                model_id=model_id,
                dataset=dataset,
                metrics=metrics,
                created_by=actor_id,
            )
            session.add(row)
            await session.commit()
        return row