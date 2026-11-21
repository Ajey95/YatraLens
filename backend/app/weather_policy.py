"""Forecast-to-itinerary relevance; never infer an official closure."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any


def assess_forecast(event_start: datetime, event_end: datetime, hourly: dict[str, Any],
                    rain_probability_trigger: float = 70.0,
                    gust_kmh_trigger: float = 40.0) -> dict[str, Any]:
    if event_start.tzinfo is None or event_end.tzinfo is None or event_end <= event_start:
        raise ValueError("An aware, increasing event time interval is required")
    start = event_start.astimezone(timezone.utc)
    end = event_end.astimezone(timezone.utc)
    times = hourly.get("time") or []
    rain = hourly.get("precipitation_probability") or []
    gust = hourly.get("wind_gusts_10m") or []
    matched: list[int] = []
    for index, raw in enumerate(times):
        try:
            hour = datetime.fromisoformat(raw.replace("Z", "+00:00"))
            if hour.tzinfo is None:
                hour = hour.replace(tzinfo=timezone.utc)
        except ValueError:
            continue
        if hour < end and hour + timedelta(hours=1) > start:
            matched.append(index)
    if not matched:
        return {"decision": "UNKNOWN", "relevant_hours": 0,
                "reason": "No forecast hour covers the trip activity interval"}
    probabilities = [float(rain[i]) for i in matched if i < len(rain) and rain[i] is not None]
    gusts = [float(gust[i]) for i in matched if i < len(gust) and gust[i] is not None]
    max_rain = max(probabilities, default=None)
    max_gust = max(gusts, default=None)
    trigger = (max_rain is not None and max_rain >= rain_probability_trigger) or (
        max_gust is not None and max_gust >= gust_kmh_trigger)
    return {
        "decision": "ACTION_RECOMMENDED" if trigger else "NO_ALERT",
        "relevant_hours": len(matched),
        "max_precipitation_probability_percent": max_rain,
        "max_wind_gust_kmh": max_gust,
        "reason": "Forecast overlaps the planned activity; confirm operating status with the operator" if trigger
                  else "Forecast did not meet configured notification thresholds",
        "official_warning": False,
        "service_cancelled": False,
    }

