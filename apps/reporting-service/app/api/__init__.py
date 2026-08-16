"""HTTP routers (FastAPI APIRouter).

The package re-exports the public v1 routers and the admin router.
Wiring into the FastAPI app happens in `app.main`.
"""
from __future__ import annotations

from .admin import router as admin_router
from .dashboards import router as dashboards_router
from .drift import router as drift_router
from .exports import router as exports_router
from .read_models import router as read_models_router
from .views import router as views_router

__all__ = [
    "admin_router",
    "dashboards_router",
    "drift_router",
    "exports_router",
    "read_models_router",
    "views_router",
]
