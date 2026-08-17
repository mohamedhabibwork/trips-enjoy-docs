"""Domain package: per-aggregate read models + projectors.

The modules here implement the read-model UPSERTs that `events/projectors.py`
dispatches. They are intentionally tiny: each handler reads the envelope,
extracts the relevant fields, and runs one parameterized SQL statement
(SRS §14 — "A read model is updated in a single SQL statement per event").
"""
from __future__ import annotations

from . import drift, exports, ledger, orders, payments, promotions, trips

__all__ = ["drift", "exports", "ledger", "orders", "payments", "promotions", "trips"]
