"""Deterministic, source-aware decisions shared by the cloud API contracts.

This module deliberately makes no claims about whether an external source is true.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from collections import Counter
import re
from typing import Iterable
import unicodedata


@dataclass(frozen=True)
class Claim:
    claim_id: str
    predicate: str
    value: str
    source_id: str
    observed_at: datetime
    valid_from: datetime | None = None
    valid_until: datetime | None = None
    entity_id: str | None = None
    status: str = "PROPOSED"


@dataclass(frozen=True)
class Decision:
    kind: str
    may_assert: bool
    evidence_ids: tuple[str, ...]
    reason: str


MATERIAL_PREDICATES = frozenset(
    {"boarding_time", "departure_time", "arrival_time", "price", "boarding_place", "destination"}
)


def translation_review_flags(original: str, translated: str) -> list[str]:
    """Flag changed numeric tokens; this cannot establish semantic equivalence."""
    def tokens(value: str) -> Counter[str]:
        matches = re.findall(r"\d+(?::\d{2})?", value)
        return Counter("".join(str(unicodedata.decimal(char)) if char.isdecimal() else char
                               for char in match) for match in matches)

    return (["NUMBERS_OR_TIME_CHANGED_REVIEW_BEFORE_SPEAKING"]
            if tokens(original) != tokens(translated) else [])


def evaluate_claims(claims: Iterable[Claim], now: datetime | None = None) -> Decision:
    """Block firm advice when supported material claims disagree.

    Ingestion order and a model's confidence cannot silently resolve a conflict.
    """
    now = now or datetime.now(timezone.utc)
    grouped: dict[tuple[str | None, str], list[Claim]] = {}
    for claim in claims:
        if not claim.source_id:
            continue
        if claim.valid_from and now < claim.valid_from:
            continue
        if claim.valid_until and now >= claim.valid_until:
            continue
        grouped.setdefault((claim.entity_id, claim.predicate), []).append(claim)

    for (_, predicate), group in grouped.items():
        if predicate not in MATERIAL_PREDICATES:
            continue
        distinct_values = {claim.value.strip().casefold() for claim in group}
        if len(distinct_values) > 1:
            return Decision(
                kind="ASK_CLARIFICATION",
                may_assert=False,
                evidence_ids=tuple(claim.source_id for claim in group),
                reason=f"Conflicting {predicate} claims require traveller review.",
            )

    sources = tuple(dict.fromkeys(c.source_id for group in grouped.values() for c in group))
    if not sources:
        return Decision("UNKNOWN", False, (), "No current source-backed claim is available.")
    unverified = [c for group in grouped.values() for c in group if c.status != "VERIFIED"]
    if unverified:
        return Decision("INFORM_TENTATIVELY", False, sources,
                        "Source-backed claims are present but need verification before firm advice.")
    return Decision("INFORM", True, sources, "Current verified source-backed claims do not conflict.")


def route_decision(board_text: str, destination: str, verified_stops: list[str] | None) -> str:
    """A board word alone never proves a bus reaches a destination."""
    if not board_text.strip() or not destination.strip() or verified_stops is None:
        return "UNKNOWN"
    normalized = {stop.strip().casefold() for stop in verified_stops}
    return "MATCH" if destination.strip().casefold() in normalized else "NO_MATCH"

