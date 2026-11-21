from datetime import datetime, timedelta, timezone

from backend.app.geo_policy import assess_saved_destination


NOW = datetime(2026, 11, 21, 16, 0, tzinfo=timezone.utc)


def test_nearby_jetty_mismatch_requires_sourced_confirmation():
    decision = assess_saved_destination(9.50, 76.35, 12, NOW, 9.50, 76.353,
                                        "booking-jetty-1", now=NOW)
    assert decision.status == "POSSIBLE_DESTINATION_MISMATCH"
    assert 300 <= decision.distance_m <= 350
    assert decision.bearing_degrees == 90
    assert decision.source_id == "booking-jetty-1"
    assert "confirm" in decision.reason


def test_accurate_near_fix_and_stale_fix_are_distinguished():
    near = assess_saved_destination(9.50, 76.35, 12, NOW, 9.5002, 76.3502,
                                    "booking-jetty-1", now=NOW)
    stale = assess_saved_destination(9.50, 76.35, 12, NOW - timedelta(minutes=3),
                                     9.50, 76.353, "booking-jetty-1", now=NOW)
    assert near.status == "NEAR_SAVED_DESTINATION"
    assert stale.status == "LOCATION_STALE"
    assert stale.distance_m is None
