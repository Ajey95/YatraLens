"""Conservative matching against an explicitly supplied, dated route record."""

from __future__ import annotations

import re
from datetime import datetime, timezone


def words(value: str) -> set[str]:
    return set(re.findall(r"[\w]+", value.casefold(), flags=re.UNICODE))


def assess_route(board_text: str, destination: str, routes: list[dict],
                 now: datetime | None = None) -> dict:
    now = now or datetime.now(timezone.utc)
    board = words(board_text)
    target = words(destination)
    if not board or not target:
        return {"status": "UNKNOWN", "reason": "Read the bus board and enter a destination",
                "source_id": None}
    for route in routes:
        valid_until = route.get("valid_until")
        if valid_until and (valid_until.tzinfo is None or valid_until < now):
            continue
        sign = words(route.get("board_label", ""))
        if not sign or not sign.issubset(board):
            continue
        stops = [words(stop) for stop in route.get("stops", [])]
        if any(target.issubset(stop) for stop in stops):
            return {"status": "CONSISTENT_WITH_ROUTE_RECORD", "source_id": route["source_id"],
                    "reason": "The stated destination appears in this supplied route record. Confirm direction and boarding with the conductor",
                    "route_id": route["route_id"], "checked_at": now.isoformat()}
        if route.get("complete_stops"):
            return {"status": "INCONSISTENT_WITH_ROUTE_RECORD", "source_id": route["source_id"],
                    "reason": "The stated destination is absent from this supplied complete stop list. Ask the conductor before boarding",
                    "route_id": route["route_id"], "checked_at": now.isoformat()}
    return {"status": "UNKNOWN", "source_id": None,
            "reason": "No current, matching route record; ask the conductor"}
