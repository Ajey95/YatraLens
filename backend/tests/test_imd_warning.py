from datetime import datetime, timezone

from backend.app.imd_warning import parse_kerala_warning, relevant_warning, combine_with_forecast


PAGE = """
<table><tr><th>Warnings for Kerala</th></tr>
<tr><th>Date of Issue: November 21, 2026</th></tr>
<tr><td>Day 1:November 21, 2026</td><td>No warning</td></tr>
<tr><td>Day 2: November 22, 2026</td><td>Heavy Rain<br>Thunderstorm &amp; Lightning</td></tr>
</table>
"""
NOW = datetime(2026, 11, 21, 12, tzinfo=timezone.utc)


def test_dated_warning_matches_only_overlapping_kerala_calendar_day():
    parsed = parse_kerala_warning(PAGE, NOW)
    assert parsed["status"] == "available"
    hit = relevant_warning(parsed, datetime(2026, 11, 22, 4, tzinfo=timezone.utc),
                           datetime(2026, 11, 22, 6, tzinfo=timezone.utc))
    assert hit["date"] == "2026-11-22"
    assert "Heavy Rain" in hit["description"]
    assert relevant_warning(parsed, datetime(2026, 11, 21, 4, tzinfo=timezone.utc),
                            datetime(2026, 11, 21, 5, tzinfo=timezone.utc)) is None


def test_stale_or_unreadable_page_is_unavailable():
    later = datetime(2026, 11, 24, tzinfo=timezone.utc)
    assert parse_kerala_warning(PAGE, later)["status"] == "unavailable"
    assert parse_kerala_warning("<html>maintenance</html>", NOW)["status"] == "unavailable"
    assert parse_kerala_warning(PAGE.replace("Day 2: November 22", "Day 2: November 29"), NOW)["entries"] == [
        {"date": "2026-11-21", "description": "No warning", "warning": False}]


def test_official_warning_recommends_check_without_inventing_cancellation():
    parsed = parse_kerala_warning(PAGE, NOW)
    start = datetime(2026, 11, 22, 4, tzinfo=timezone.utc)
    end = datetime(2026, 11, 22, 6, tzinfo=timezone.utc)
    forecast = {"decision": "NO_ALERT", "reason": "Low forecast rain", "service_cancelled": False}
    result = combine_with_forecast(forecast, parsed, 9.5, 76.35, start, end)
    assert result["decision"] == "ACTION_RECOMMENDED"
    assert result["official_warning"]["date"] == "2026-11-22"
    assert result["service_cancelled"] is False
    assert result["alert_source_url"].startswith("https://mausam.imd.gov.in/")
    assert combine_with_forecast(forecast, parsed, 12.0, 76.35, start, end)["decision"] == "NO_ALERT"
    assert combine_with_forecast(forecast, {"status": "unavailable"}, 9.5, 76.35, start, end)["decision"] == "NO_ALERT"
