"""Tests for the virtual clock test harness."""

from datetime import UTC, datetime, timedelta, timezone

import pytest

from app.testing.clock import FixedClock

KST = timezone(timedelta(hours=9))
FIXED_NOW = datetime(2026, 8, 18, 14, 0, 0, tzinfo=KST)


class TestFixedClock:
    def test_returns_same_time(self):
        clock = FixedClock(FIXED_NOW)
        assert clock.now() == FIXED_NOW
        assert clock.now() == FIXED_NOW  # deterministic

    def test_advance_moves_forward(self):
        clock = FixedClock(FIXED_NOW)
        clock.advance(timedelta(minutes=30))
        assert clock.now() == FIXED_NOW + timedelta(minutes=30)

    def test_advance_moves_backward(self):
        clock = FixedClock(FIXED_NOW)
        clock.advance(timedelta(minutes=-10))
        assert clock.now() == FIXED_NOW - timedelta(minutes=10)

    def test_set_jumps_to_time(self):
        clock = FixedClock(FIXED_NOW)
        new_time = datetime(2026, 12, 25, 9, 0, tzinfo=KST)
        clock.set(new_time)
        assert clock.now() == new_time

    def test_naive_datetime_rejected_in_constructor(self):
        with pytest.raises(ValueError, match="timezone-aware"):
            FixedClock(datetime(2026, 8, 18, 14, 0))

    def test_naive_datetime_rejected_in_set(self):
        clock = FixedClock(FIXED_NOW)
        with pytest.raises(ValueError, match="timezone-aware"):
            clock.set(datetime(2026, 8, 18, 14, 0))

    def test_utc_z_accepted(self):
        clock = FixedClock(datetime(2026, 8, 18, 5, 0, tzinfo=UTC))
        assert clock.now().utcoffset() == timedelta(0)

    def test_independent_of_system_clock(self):
        """Two clocks at different times don't interact."""
        c1 = FixedClock(FIXED_NOW)
        c2 = FixedClock(FIXED_NOW + timedelta(hours=3))
        c1.advance(timedelta(hours=1))
        assert c1.now() != c2.now()
        assert c2.now() == FIXED_NOW + timedelta(hours=3)
