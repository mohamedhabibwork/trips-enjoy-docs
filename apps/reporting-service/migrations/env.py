"""Alembic environment for reporting-service.

Reads the sync DB URL from `REPORTING_SERVICE_DB_SYNC_URL`. Run via:

    cd apps/reporting-service && alembic upgrade head
"""
import os
from logging.config import fileConfig

from alembic import context

config = context.config

if config.config_file_name is not None:
    fileConfig(config.config_file_name)

url = os.environ.get(
    "REPORTING_SERVICE_DB_SYNC_URL",
    "postgresql+psycopg2://postgres@0.0.0.0:5432/trips_enjoy?options=-c%20search_path%3Dreporting,public",
)
config.set_main_option("sqlalchemy.url", url)

target_metadata = None


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
        context.configure(connection=connection, target_metadata=target_metadata)
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
