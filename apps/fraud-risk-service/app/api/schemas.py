"""Pydantic schemas for fraud-risk-service REST API.

Mirrors docs/services/fraud-risk-service/INTEGRATION.md §1:
  - POST /v1/score
  - POST /v1/block
  - POST /v1/allowlist
  - GET  /v1/scores/{id}
  - GET  /v1/admin/scores
  - GET  /v1/admin/blocklists
  - POST /v1/admin/blocklists
  - DELETE /v1/admin/blocklists/{id}
  - POST /v1/admin/models/deploy
  - GET  /v1/admin/models
  - GET  /v1/admin/evaluations
"""
from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class ScoreRequest(BaseModel):
    """POST /v1/score — score a subject's risk."""

    model_config = ConfigDict(extra="forbid")

    subject_id: uuid.UUID
    subject_kind: str = Field(pattern="^(customer|driver|courier|merchant)$")
    features: dict[str, Any]
    model_id: str = "baseline-linear-v1"


class ScoreResponse(BaseModel):
    """Response to POST /v1/score + GET /v1/scores/{id}."""

    score_id: uuid.UUID
    subject_id: uuid.UUID
    subject_kind: str
    score: float
    decision: str
    model_id: str
    computed_at: datetime
    explanations: dict[str, Any] | None = None


class BlockRequest(BaseModel):
    """POST /v1/block — manually block a subject (admin action)."""

    model_config = ConfigDict(extra="forbid")

    subject_id: uuid.UUID
    subject_kind: str = Field(pattern="^(customer|driver|courier|merchant)$")
    reason: str


class AllowlistRequest(BaseModel):
    """POST /v1/allowlist — add a subject to the allowlist."""

    model_config = ConfigDict(extra="forbid")

    subject_id: uuid.UUID
    subject_kind: str = Field(pattern="^(customer|driver|courier|merchant)$")
    reason: str


class BlocklistResponse(BaseModel):
    id: uuid.UUID
    kind: str
    value: str
    reason: str
    added_by: uuid.UUID
    expires_at: datetime | None
    created_at: datetime


class CreateBlocklistRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    kind: str = Field(pattern="^(email|phone|ip|fingerprint|device|card_bin|country)$")
    value: str
    reason: str
    expires_at: datetime | None = None


class ModelResponse(BaseModel):
    id: str
    version: int
    algorithm: str
    trained_at: datetime
    deployed_at: datetime | None
    hyperparameters: dict[str, Any]
    metrics: dict[str, Any] | None = None


class DeployModelRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    model_id: str
    version: int
    algorithm: str
    hyperparameters: dict[str, Any]
    metrics: dict[str, Any] | None = None


class EvaluationResponse(BaseModel):
    id: uuid.UUID
    model_id: str
    evaluated_at: datetime
    dataset: str
    metrics: dict[str, Any]