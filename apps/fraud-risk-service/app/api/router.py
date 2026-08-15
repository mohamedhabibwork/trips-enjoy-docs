"""FastAPI router for fraud-risk-service.

Mirrors docs/services/fraud-risk-service/INTEGRATION.md §1:
  10 endpoints across /v1 and /v1/admin.
"""
from __future__ import annotations

import hashlib
import json
import logging
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request

from ..db import Blocklist, Evaluation, Model, Score
from ..services import (
    BlocklistService,
    EvaluationService,
    IdempotencyService,
    ModelService,
    RiskScoringService,
    DeviceFingerprintService,
)
from .schemas import (
    AllowlistRequest,
    BlocklistResponse,
    BlockRequest,
    CreateBlocklistRequest,
    DeployModelRequest,
    EvaluationResponse,
    ModelResponse,
    ScoreRequest,
    ScoreResponse,
)

logger = logging.getLogger(__name__)


def _sha256(payload: dict) -> str:
    return hashlib.sha256(
        json.dumps(payload, sort_keys=True, default=str).encode("utf-8")
    ).hexdigest()


def _score_to_response(score: Score) -> ScoreResponse:
    return ScoreResponse(
        score_id=score.id,
        subject_id=score.subject_id,
        subject_kind=score.subject_kind,
        score=float(score.score),
        decision=score.decision,
        model_id=score.model_id,
        computed_at=score.computed_at,
        explanations=score.explanations,
    )


def build_router(
    scoring: RiskScoringService,
    fingerprints: DeviceFingerprintService,
    blocklist_svc: BlocklistService,
    model_svc: ModelService,
    evaluation_svc: EvaluationService,
    idempotency: IdempotencyService,
) -> APIRouter:
    router = APIRouter()

    # ---------- /v1 (public endpoints used by upstream services) ----------

    @router.post("/v1/score", response_model=ScoreResponse, status_code=201)
    async def post_score(req: ScoreRequest, request: Request) -> ScoreResponse:
        idem_key_raw = request.headers.get("Idempotency-Key")
        x_user_id = request.headers.get("X-User-Id")
        if not idem_key_raw:
            raise HTTPException(status_code=400, detail="Idempotency-Key header required")
        if not x_user_id:
            raise HTTPException(status_code=400, detail="X-User-Id header required")
        idem_key = uuid.UUID(idem_key_raw)
        actor_id = uuid.UUID(x_user_id)
        correlation_id = uuid.UUID(request.headers.get("X-Request-Id", str(uuid.uuid4())))

        existing = await idempotency.find_existing(idem_key)
        if existing is not None:
            if len(existing.request_hash) != 64:
                raise HTTPException(status_code=422, detail="malformed idempotency record")
            cached_hash = _sha256(req.model_dump(mode="json"))
            if existing.request_hash != cached_hash:
                raise HTTPException(
                    status_code=422, detail="idempotency key body mismatch",
                )
            score_id = uuid.UUID(existing.response_body["score_id"])
            async with scoring._session_factory() as session:  # type: ignore[attr-defined]
                score = await session.get(Score, score_id)
            if score is None:
                raise HTTPException(status_code=500, detail="idempotency record refers to missing score")
            return _score_to_response(score)

        score = await scoring.score(
            subject_id=req.subject_id,
            subject_kind=req.subject_kind,
            features=req.features,
            actor_id=actor_id,
            correlation_id=correlation_id,
            model_id=req.model_id,
        )
        await idempotency.record(
            idempotency_key=idem_key,
            request_hash=_sha256(req.model_dump(mode="json")),
            response_status=201,
            response_body={"score_id": str(score.id)},
            actor_id=actor_id,
        )
        return _score_to_response(score)

    @router.post("/v1/block", status_code=204)
    async def post_block(req: BlockRequest, request: Request) -> None:
        """Manually block a subject (admin/system actor)."""
        actor_id = uuid.UUID(request.headers.get("X-User-Id", str(uuid.uuid4())))
        correlation_id = uuid.UUID(request.headers.get("X-Request-Id", str(uuid.uuid4())))
        await blocklist_svc.add(
            kind="customer",
            value=str(req.subject_id),
            reason=req.reason,
            actor_id=actor_id,
            correlation_id=correlation_id,
        )

    @router.post("/v1/allowlist", status_code=204)
    async def post_allowlist(req: AllowlistRequest, request: Request) -> None:
        actor_id = uuid.UUID(request.headers.get("X-User-Id", str(uuid.uuid4())))
        # In production this writes to a separate allowlist table.
        # For this graduation we just record an audit action.
        from ..db import Action  # local import to avoid circular at module load
        async with scoring._session_factory() as session:  # type: ignore[attr-defined]
            session.add(
                Action(
                    id=uuid.uuid4(),
                    subject_id=req.subject_id,
                    subject_kind=req.subject_kind,
                    action="allow",
                    actor_id=actor_id,
                    actor_kind="admin",
                    reason=req.reason,
                    correlation_id=correlation_id,
                ),
            )
            await session.commit()

    @router.get("/v1/scores/{score_id}", response_model=ScoreResponse)
    async def get_score(score_id: uuid.UUID) -> ScoreResponse:
        async with scoring._session_factory() as session:  # type: ignore[attr-defined]
            score = await session.get(Score, score_id)
        if score is None:
            raise HTTPException(status_code=404, detail=f"score {score_id} not found")
        return _score_to_response(score)

    # ---------- /v1/admin (admin-only endpoints) ----------

    @router.get("/v1/admin/scores", response_model=list[ScoreResponse])
    async def admin_list_scores(
        subject_id: uuid.UUID | None = None, limit: int = 100
    ) -> list[ScoreResponse]:
        from sqlalchemy import select, desc
        async with scoring._session_factory() as session:  # type: ignore[attr-defined]
            stmt = select(Score).order_by(desc(Score.computed_at)).limit(limit)
            if subject_id is not None:
                stmt = stmt.where(Score.subject_id == subject_id)
            result = await session.execute(stmt)
            return [_score_to_response(s) for s in result.scalars().all()]

    @router.get("/v1/admin/blocklists", response_model=list[BlocklistResponse])
    async def admin_list_blocklists() -> list[BlocklistResponse]:
        rows = await blocklist_svc.list_active()
        return [
            BlocklistResponse(
                id=r.id, kind=r.kind, value=r.value, reason=r.reason,
                added_by=r.added_by, expires_at=r.expires_at, created_at=r.created_at,
            )
            for r in rows
        ]

    @router.post("/v1/admin/blocklists", response_model=BlocklistResponse, status_code=201)
    async def admin_add_blocklist(
        req: CreateBlocklistRequest, request: Request,
    ) -> BlocklistResponse:
        actor_id = uuid.UUID(request.headers.get("X-User-Id", str(uuid.uuid4())))
        row = await blocklist_svc.add(
            kind=req.kind, value=req.value, reason=req.reason,
            actor_id=actor_id, expires_at=req.expires_at,
        )
        return BlocklistResponse(
            id=row.id, kind=row.kind, value=row.value, reason=row.reason,
            added_by=row.added_by, expires_at=row.expires_at, created_at=row.created_at,
        )

    @router.delete("/v1/admin/blocklists/{blocklist_id}", status_code=204)
    async def admin_delete_blocklist(blocklist_id: uuid.UUID, request: Request) -> None:
        actor_id = uuid.UUID(request.headers.get("X-User-Id", str(uuid.uuid4())))
        await blocklist_svc.remove(blocklist_id, actor_id=actor_id)

    @router.post("/v1/admin/models/deploy", response_model=ModelResponse, status_code=201)
    async def admin_deploy_model(
        req: DeployModelRequest, request: Request,
    ) -> ModelResponse:
        actor_id = uuid.UUID(request.headers.get("X-User-Id", str(uuid.uuid4())))
        row = await model_svc.deploy(
            model_id=req.model_id, version=req.version,
            hyperparameters={**req.hyperparameters, "algorithm": req.algorithm},
            metrics=req.metrics, actor_id=actor_id,
        )
        return ModelResponse(
            id=row.id, version=row.version, algorithm=row.algorithm,
            trained_at=row.trained_at, deployed_at=row.deployed_at,
            hyperparameters=row.hyperparameters, metrics=row.metrics,
        )

    @router.get("/v1/admin/models", response_model=list[ModelResponse])
    async def admin_list_models() -> list[ModelResponse]:
        rows = await model_svc.list_models()
        return [
            ModelResponse(
                id=r.id, version=r.version, algorithm=r.algorithm,
                trained_at=r.trained_at, deployed_at=r.deployed_at,
                hyperparameters=r.hyperparameters, metrics=r.metrics,
            )
            for r in rows
        ]

    @router.get("/v1/admin/evaluations", response_model=list[EvaluationResponse])
    async def admin_list_evaluations() -> list[EvaluationResponse]:
        rows = await evaluation_svc.list()
        return [
            EvaluationResponse(
                id=r.id, model_id=r.model_id, evaluated_at=r.evaluated_at,
                dataset=r.dataset, metrics=r.metrics,
            )
            for r in rows
        ]

    return router