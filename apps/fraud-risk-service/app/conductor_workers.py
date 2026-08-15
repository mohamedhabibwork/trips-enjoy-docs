"""Conductor workflow workers for fraud-risk-service.

Per ADR-0018 + shared/CONDUCTOR_WORKFLOWS.md, fraud-risk-service owns
2 of the 17 workflow IDs:
  - wf.fraud.score.v1          (this file)
  - wf.fraud.block.v1           (this file)
"""
from __future__ import annotations

import logging
import uuid

from .services import BlocklistService, RiskScoringService

logger = logging.getLogger(__name__)


class FraudConductorWorkers:
    """Thin wrappers around the RiskScoringService / BlocklistService."""

    def __init__(
        self,
        scoring: RiskScoringService,
        blocklist_svc: BlocklistService,
    ) -> None:
        self._scoring = scoring
        self._blocklist = blocklist_svc

    async def score(self, input: dict) -> dict:
        """Conductor task: fraud.score — runs the scoring pipeline."""
        score = await self._scoring.score(
            subject_id=uuid.UUID(input["subject_id"]),
            subject_kind=input["subject_kind"],
            features=input["features"],
            actor_id=uuid.UUID(input["acting_user_id"]),
            correlation_id=uuid.UUID(input["correlation_id"]),
            model_id=input.get("model_id", "baseline-linear-v1"),
        )
        return {
            "score_id": str(score.id),
            "subject_id": str(score.subject_id),
            "score": float(score.score),
            "decision": score.decision,
        }

    async def block(self, input: dict) -> dict:
        """Conductor task: fraud.block — adds the subject to the blocklist."""
        await self._blocklist.add(
            kind=input["kind"],
            value=str(input["subject_id"]),
            reason=input["reason"],
            actor_id=uuid.UUID(input["acting_user_id"]),
            correlation_id=uuid.UUID(input["correlation_id"]),
        )
        return {"subject_id": str(input["subject_id"]), "blocked": True}