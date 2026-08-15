"""Alembic environment for fraud-risk-service.

Reads the sync DB URL from `FRAUD_RISK_SERVICE_DB_SYNC_URL`. Run via:

    cd apps/fraud-risk-service && alembic upgrade head
"""
import os
from logging.config import fileConfig

from alembic import context

config = context.config

if config.config_file_name is not None:
    fileConfig(config.config_file_name)

# Pull the URL from the environment. Falls back to localhost for local dev.
url = os.environ.get(
    "FRAUD_RISK_SERVICE_DB_SYNC_URL",
    "postgresql+psycopg2://postgres@0.0.0.0:5432/trips_enjoy?options=-c%20search_path%3Dfraud_risk,public",
)
config.set_main_option("sqlalchemy.url", url)

# Per docs/architecture/DATABASE_ARCHITECTURE.md: schema is set via the
# search_path option above; we don't use version_table_schema here.
target_metadata = None  # no SQLAlchemy models yet; raw DDL migrations only.


def run_migrations_offline() -> None:
    context.configure(
        url=url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    from sqlalchemy import engine_from_config, pool

    connectable = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )
    with connectable.connect() as connection:
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
        )
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
