"""create the `fraud_risk` schema

Revision ID: 0001
Revises:
Create Date: 2026-08-14

Per docs/architecture/DATABASE_ARCHITECTURE.md:102 — every active service owns
exactly one PostgreSQL schema; the name is the snake_case form of the service
name. Domain tables (per docs/services/fraud-risk-service/ERD.md) will land as
0002/0003 in subsequent revisions.
"""
from alembic import op

# revision identifiers, used by Alembic.
revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    # CREATE SCHEMA IF NOT EXISTS is idempotent at the SQL level.
    op.execute("CREATE SCHEMA IF NOT EXISTS fraud_risk")


def downgrade() -> None:
    op.execute("DROP SCHEMA IF EXISTS fraud_risk CASCADE")
