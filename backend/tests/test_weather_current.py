from datetime import datetime, timedelta, timezone

from fastapi.testclient import TestClient

from backend.app.main import app
from backend.app.weather_current import summarize_current


def test_current_conditions_are_values_not_a_no_alert_decision():
    now = datetime(2026, 11, 21, 21, 0, tzinfo=timezone.utc)
    current = {"time": "2026-11-21T20:45", "temperature_2m": 26.4,
               "relative_humidity_2m": 88, "precipitation": 0.2,
               "wind_speed_10m": 8.3, "wind_gusts_10m": 15.1,
               "weather_code": 61}
    result = summarize_current(current, "https://api.open-meteo.com/example", now)
    assert result["status"] == "CURRENT"
    assert result["condition"] == "Rain or showers"
    assert result["temperature_c"] == 26.4
    assert "decision" not in result


def test_stale_current_conditions_are_not_reported_as_now():
    now = datetime(2026, 11, 21, 21, 0, tzinfo=timezone.utc)
    result = summarize_current({"time": (now - timedelta(hours=3)).isoformat(),
                                "temperature_2m": 30}, "source", now)
    assert result["status"] == "UNKNOWN"


def test_current_weather_requires_location_sharing_consent(monkeypatch):
    monkeypatch.setenv("YATRALENS_DEV_TOKEN", "test-token")
    client = TestClient(app)
    payload = {"lat": 9.5, "lon": 76.35}
    assert client.post("/v1/weather/current", json=payload).status_code == 401
    assert client.post("/v1/weather/current", json=payload,
                       headers={"Authorization": "Bearer test-token"}).status_code == 403
