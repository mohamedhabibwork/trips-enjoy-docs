"""Risk scoring service — the B1 fraud detection pipeline.

Per docs/services/fraud-risk-service/INTEGRATION.md §1.1 the score
endpoint computes a risk score from a feature vector using the active
ML model. The algorithm here is a deterministic baseline (linear
combination of normalised features) suitable for testing; in production
the model_id points at a scikit-learn estimator in the Model table.
"""
from __future__ import annotations

import hashlib
import math
import uuid
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..db import Action, OutboxEvent, Score


class RiskScoringService:
    """The headline risk scoring service.

    Three decision outcomes: allow / review / block. The score is
    a normalized [0, 1] value where 0 is safe and 1 is high risk.
    Threshold mapping per docs/services/fraud-risk-service/SRS.md §3:
      score < 0.30  -> allow
      0.30 <= score < 0.70  -> review
      score >= 0.70  -> block
    """

    ALLOW_THRESHOLD = 0.30
    BLOCK_THRESHOLD = 0.70

    def __init__(self, session_factory) -> None:
        self._session_factory = session_factory

    @staticmethod
    def compute_decision(score: float) -> str:
        """Map a numeric score to an allow/review/block decision."""
        if score < RiskScoringService.ALLOW_THRESHOLD:
            return "allow"
        if score >= RiskScoringService.BLOCK_THRESHOLD:
            return "block"
        return "review"

    @staticmethod
    def compute_score(features: dict, model_id: str = "baseline-linear-v1") -> tuple[float, dict]:
        """Deterministic baseline scoring.

        The baseline combines a handful of canonical fraud features:
          - account_age_days (younger = higher risk)
          - transactions_24h (more = higher risk)
          - distinct_devices_24h (more = higher risk)
          - failed_payments_24h (more = higher risk)
          - chargebacks_180d (more = higher risk)
          - velocity_avg_minor (higher = higher risk)

        Each feature is normalised to [0, 1] and weighted. The output
        is a single scalar in [0, 1]. Real ML models in production
        replace this with a scikit-learn estimator from the Model table.
        """
        account_age = features.get("account_age_days", 365)
        tx_24h = features.get("transactions_24h", 0)
        devices_24h = features.get("distinct_devices_24h", 1)
        failed_payments = features.get("failed_payments_24h", 0)
        chargebacks = features.get("chargebacks_180d", 0)
        velocity = features.get("velocity_avg_minor", 0)

        # Each component is normalised to [0, 1]
        age_factor = max(0.0, min(1.0, 1.0 - math.log1p(account_age) / math.log1p(365)))
        tx_factor = max(0.0, min(1.0, tx_24h / 20.0))
        dev_factor = max(0.0, min(1.0, (devices_24h - 1) / 4.0))
        failed_factor = max(0.0, min(1.0, failed_payments / 5.0))
        chargeback_factor = max(0.0, min(1.0, chargebacks / 3.0))
        velocity_factor = max(0.0, min(1.0, velocity / 50000.0))

        weights = {
            "age_factor": 0.10,
            "tx_factor": 0.20,
            "dev_factor": 0.25,
            "failed_factor": 0.20,
            "chargeback_factor": 0.15,
            "velocity_factor": 0.10,
        }

        components = {
            "age_factor": age_factor,
            "tx_factor": tx_factor,
            "dev_factor": dev_factor,
            "failed_factor": failed_factor,
            "chargeback_factor": chargeback_factor,
            "velocity_factor": velocity_factor,
        }
        score = sum(components[k] * weights[k] for k in weights)
        score = max(0.0, min(1.0, score))
        explanations = {
            "components": components,
            "weights": weights,
            "model_id": model_id,
        }
        return score, explanations

    async def score(
        self,
        subject_id: uuid.UUID,
        subject_kind: str,
        features: dict,
        actor_id: uuid.UUID,
        correlation_id: uuid.UUID,
        model_id: str = "baseline-linear-v1",
    ) -> Score:
        """Score a subject and persist the result + audit + outbox row."""
        score_value, explanations = self.compute_score(features, model_id=model_id)
        decision = self.compute_decision(score_value)

        async with self._session_factory() as session:
            score_row = Score(
                id=uuid.uuid4(),
                subject_id=subject_id,
                subject_kind=subject_kind,
                score=score_value,
                decision=decision,
                model_id=model_id,
                features=features,
                explanations=explanations,
                created_by=actor_id,
            )
            session.add(score_row)

            audit = Action(
                id=uuid.uuid4(),
                subject_id=subject_id,
                subject_kind=subject_kind,
                action=decision,
                actor_id=actor_id,
                actor_kind="model",
                reason=f"score={score_value:.4f} via {model_id}",
                payload={"score_id": str(score_row.id), "score": score_value},
                correlation_id=correlation_id,
            )
            session.add(audit)

            outbox = OutboxEvent(
                id=uuid.uuid4(),
                aggregate_type="Score",
                aggregate_id=score_row.id,
                event_type="fraud.risk.scored.v1",
                topic="fraud.risk.scored.v1",
                payload={
                    "score_id": str(score_row.id),
                    "subject_id": str(subject_id),
                    "subject_kind": subject_kind,
                    "score": score_value,
                    "decision": decision,
                    "model_id": model_id,
                },
                correlation_id=correlation_id,
                created_by=actor_id,
            )
            session.add(outbox)
            await session.commit()
            await session.refresh(score_row)
        return score_row


def stable_idempotency_hash(*parts: str) -> str:
    """SHA-256 hex of the concatenated parts — used for idempotency."""
    h = hashlib.sha256()
    for p in parts:
        h.update(p.encode("utf-8"))
        h.update(b"|")
    return h.hexdigest()