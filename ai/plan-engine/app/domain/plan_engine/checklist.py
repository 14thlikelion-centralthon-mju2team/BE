"""Checklist synthesis.

Merging rules (prompt §12):
  user rule > event item > weather suggestion

Two invariants matter for the client:
  1. The sum of ``applied_minutes`` in the checklist equals
     ``breakdown.personal_routine_minutes``.
  2. ``applied_minutes`` is greater than zero only for ``timed_routine``.
"""

import re
from dataclasses import dataclass, replace

from app.domain.plan_engine.enums import PrepActionType, PrepSourceType
from app.domain.plan_engine.models import PlanChecklistItem, PrepItemSnapshot

UMBRELLA_ITEM_NAME = "우산"

_SOURCE_PRIORITY = {
    PrepSourceType.RULE: 0,
    PrepSourceType.EVENT_ITEM: 1,
    PrepSourceType.WEATHER: 2,
}


@dataclass(frozen=True)
class _Group:
    display_name: str
    source_type: PrepSourceType
    preferred_action_type: PrepActionType
    routine_minutes: int
    is_sensitive: bool
    reason: str | None


def normalize_item_name(value: str) -> str:
    """Conservative normalization: trim, collapse whitespace, casefold.

    No semantic or similarity inference is performed, so "우 산" and "우산"
    stay separate items.
    """
    return re.sub(r"\s+", " ", value.strip()).casefold()


def routine_minutes_of(item: PrepItemSnapshot) -> int:
    if item.action_type is PrepActionType.TIMED_ROUTINE:
        return item.applied_minutes
    return 0


def _merge_item(group: _Group, item: PrepItemSnapshot) -> _Group:
    takes_precedence = _SOURCE_PRIORITY[item.source_type] < _SOURCE_PRIORITY[group.source_type]
    return _Group(
        display_name=item.item_name.strip() if takes_precedence else group.display_name,
        source_type=item.source_type if takes_precedence else group.source_type,
        preferred_action_type=(
            item.action_type if takes_precedence else group.preferred_action_type
        ),
        routine_minutes=group.routine_minutes + routine_minutes_of(item),
        is_sensitive=group.is_sensitive or item.is_sensitive,
        reason=group.reason,
    )


def merge_checklist(
    prep_items: list[PrepItemSnapshot],
    rain_reason: str | None,
) -> list[PlanChecklistItem]:
    groups: dict[str, _Group] = {}
    order: list[str] = []

    for item in prep_items:
        key = normalize_item_name(item.item_name)
        existing = groups.get(key)
        if existing is None:
            groups[key] = _Group(
                display_name=item.item_name.strip(),
                source_type=item.source_type,
                preferred_action_type=item.action_type,
                routine_minutes=routine_minutes_of(item),
                is_sensitive=item.is_sensitive,
                reason=None,
            )
            order.append(key)
            continue
        groups[key] = _merge_item(existing, item)

    if rain_reason is not None:
        umbrella_key = normalize_item_name(UMBRELLA_ITEM_NAME)
        existing = groups.get(umbrella_key)
        if existing is None:
            groups[umbrella_key] = _Group(
                display_name=UMBRELLA_ITEM_NAME,
                source_type=PrepSourceType.WEATHER,
                preferred_action_type=PrepActionType.CARRY,
                routine_minutes=0,
                is_sensitive=False,
                reason=rain_reason,
            )
            order.append(umbrella_key)
        else:
            # The user-registered item wins; the weather condition only
            # contributes the reason (prompt §12).
            groups[umbrella_key] = replace(existing, reason=rain_reason)

    checklist: list[PlanChecklistItem] = []
    for key in order:
        group = groups[key]
        consumes_time = group.routine_minutes > 0
        checklist.append(
            PlanChecklistItem(
                item_name=group.display_name,
                action_type=(
                    PrepActionType.TIMED_ROUTINE
                    if consumes_time
                    else group.preferred_action_type
                ),
                source_type=group.source_type,
                applied_minutes=group.routine_minutes,
                is_sensitive=group.is_sensitive,
                reason=group.reason,
            )
        )
    return checklist
