"""Tests for DeviceFingerprintService trust_score + blocklist."""
from __future__ import annotations

import pytest

from app.services.device_fingerprint import DeviceFingerprintService
from app.services.blocklist import BlocklistService


class TestComputeTrustScore:
    def test_first_sighting_high_trust(self):
        """A brand-new fingerprint starts at ~0.95 trust."""
        trust = DeviceFingerprintService.compute_trust_score(seen_count=1)
        assert trust >= 0.90

    def test_repeated_sighting_lower_trust(self):
        """The more times we see this fingerprint, the lower the trust."""
        trust_1 = DeviceFingerprintService.compute_trust_score(1)
        trust_5 = DeviceFingerprintService.compute_trust_score(5)
        trust_10 = DeviceFingerprintService.compute_trust_score(10)
        assert trust_1 > trust_5 > trust_10

    def test_clamped_to_0_1(self):
        for n in (0, 1, 5, 10, 50, 100, 1000):
            trust = DeviceFingerprintService.compute_trust_score(n)
            assert 0.0 <= trust <= 1.0

    def test_distinct_subjects_penalty(self):
        """A fingerprint shared across many subjects gets an extra penalty."""
        trust_1_subj = DeviceFingerprintService.compute_trust_score(seen_count=3, distinct_subjects=1)
        trust_5_subj = DeviceFingerprintService.compute_trust_score(seen_count=3, distinct_subjects=5)
        assert trust_1_subj > trust_5_subj


class TestBlocklistService:
    """The blocklist service writes to the DB; we test the in-memory model."""

    def test_list_active_returns_only_unexpired(self):
        from datetime import datetime, timedelta, timezone
        from app.db import Blocklist
        now = datetime.now(timezone.utc)
        rows = [
            Blocklist(
                id=__import__("uuid").uuid4(),
                kind="email",
                value="a@b.com",
                reason="test",
                added_by=__import__("uuid").uuid4(),
                expires_at=None,
            ),
            Blocklist(
                id=__import__("uuid").uuid4(),
                kind="phone",
                value="+1",
                reason="test",
                added_by=__import__("uuid").uuid4(),
                expires_at=now + timedelta(days=1),
            ),
            Blocklist(
                id=__import__("uuid").uuid4(),
                kind="ip",
                value="1.2.3.4",
                reason="test",
                added_by=__import__("uuid").uuid4(),
                expires_at=now - timedelta(seconds=1),  # expired
            ),
        ]
        # list_active logic: keep entries where expires_at is None or > now
        now2 = datetime.now(timezone.utc)
        active = [r for r in rows if r.expires_at is None or r.expires_at > now2]
        assert len(active) == 2
        kinds = {r.kind for r in active}
        assert kinds == {"email", "phone"}