"""create the `reporting` schema

Revision ID: 0001
Revises:
Create Date: 2026-08-14

Per docs/architecture/DATABASE_ARCHITECTURE.md:102 — every active service owns
exactly one PostgreSQL schema; the name is the snake_case form of the service
name. Domain tables (per docs/services/reporting-service/ERD.md) will land as
0002/0003 in subsequent revisions.
"""
from alembic import op

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("CREATE SCHEMA IF NOT EXISTS reporting")


def downgrade() -> None:
    op.execute("DROP SCHEMA IF EXISTS reporting CASCADE")
