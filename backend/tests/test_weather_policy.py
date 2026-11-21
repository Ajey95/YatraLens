from datetime import datetime, timezone

from backend.app.weather_policy import assess_forecast


HOURLY = {
    "time": ["2026-11-21T09:00", "2026-11-21T10:00", "2026-11-21T11:00"],
    "precipitation_probability": [10, 85, 20],
    "wind_gusts_10m": [12, 45, 15],
}


def utc(hour: int, minute: int = 0):
    return datetime(2026, 11, 21, hour, minute, tzinfo=timezone.utc)


def test_overlapping_forecast_recommends_operator_check_without_claiming_cancellation():
    result = assess_forecast(utc(10, 30), utc(11, 30), HOURLY)
    assert result["decision"] == "ACTION_RECOMMENDED"
    assert result["max_precipitation_probability_percent"] == 85
    assert result["official_warning"] is False
    assert result["service_cancelled"] is False


def test_outside_forecast_is_unknown():
    result = assess_forecast(utc(15), utc(16), HOURLY)
    assert result["decision"] == "UNKNOWN"

