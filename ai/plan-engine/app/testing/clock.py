"""Virtual clock for deterministic testing.

The AI server never calls ``datetime.now()`` in domain code.  The current time
is always injected via the request's ``now`` field.  This module provides a
``FixedClock`` for tests that need to reason about time progression without
depending on the system clock.
"""

from datetime import datetime, timedelta
from typing import Protocol


class Clock(Protocol):
    """Minimal clock interface for test harnesses."""

    def now(self) -> datetime: ...


class FixedClock:
    """A clock frozen at a given instant, advanceable by explicit ``advance()`` calls.

    Parameters
    ----------
    fixed_now:
        Must be timezone-aware.  Raises ``ValueError`` otherwise.
    """

    def __init__(self, fixed_now: datetime) -> None:
        if fixed_now.tzinfo is None or fixed_now.utcoffset() is None:
            raise ValueError("timezone-aware datetime is required")
        self._current = fixed_now

    def now(self) -> datetime:
        """Return the current virtual time."""
        return self._current

    def advance(self, delta: timedelta) -> None:
        """Move the virtual clock forward (or backward) by *delta*."""
        self._current += delta

    def set(self, new_now: datetime) -> None:
        """Jump to an arbitrary point in time."""
        if new_now.tzinfo is None or new_now.utcoffset() is None:
            raise ValueError("timezone-aware datetime is required")
        self._current = new_now
