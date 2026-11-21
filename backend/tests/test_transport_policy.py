from datetime import datetime, timedelta, timezone

from backend.app.transport_policy import assess_route


def test_route_record_positive_negative_unknown_and_stale():
    now = datetime.now(timezone.utc)
    route = {"route_id": "fixture-route", "source_id": "fixture-operator-note",
             "board_label": "ആലപ്പുഴ", "stops": ["Alappuzha beach", "Bus stand"],
             "complete_stops": True, "valid_until": now + timedelta(days=1)}
    assert assess_route("ആലപ്പുഴ", "Alappuzha beach", [route], now)["status"] == "CONSISTENT_WITH_ROUTE_RECORD"
    assert assess_route("ആലപ്പുഴ", "Fort Kochi", [route], now)["status"] == "INCONSISTENT_WITH_ROUTE_RECORD"
    assert assess_route("കോട്ടയം", "Alappuzha beach", [route], now)["status"] == "UNKNOWN"
    route["valid_until"] = now - timedelta(seconds=1)
    assert assess_route("ആലപ്പുഴ", "Alappuzha beach", [route], now)["status"] == "UNKNOWN"
