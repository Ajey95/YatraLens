"""Present modelled current conditions separately from activity alert decisions."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any


def condition_for(code: int | None) -> str:
    if code == 0:
        return "Clear sky"
    if code in (1, 2, 3):
        return "Partly cloudy" if code in (1, 2) else "Overcast"
    if code in (45, 48):
        return "Fog"
    if code is not None and (51 <= code <= 67 or 80 <= code <= 82):
        return "Rain or showers"
    if code is not None and (71 <= code <= 77 or 85 <= code <= 86):
        return "Snow"
    if code is not None and 95 <= code <= 99:
        return "Thunderstorm"
    return "Conditions unavailable"


def summarize_current(current: dict[str, Any], source_url: str,
                      retrieved_at: datetime) -> dict[str, Any]:
    raw_time = current.get("time")
    try:
        observed = datetime.fromisoformat(str(raw_time).replace("Z", "+00:00"))
        if observed.tzinfo is None:
            observed = observed.replace(tzinfo=timezone.utc)
        observed = observed.astimezone(timezone.utc)
    except (TypeError, ValueError):
        return {"status": "UNKNOWN", "reason": "Current observation time unavailable",
                "source_url": source_url, "retrieved_at": retrieved_at.isoformat()}
    now = retrieved_at.astimezone(timezone.utc)
    if observed < now - timedelta(minutes=90) or observed > now + timedelta(minutes=20):
        return {"status": "UNKNOWN", "reason": "Current conditions are stale",
                "observed_at": observed.isoformat(), "source_url": source_url,
                "retrieved_at": retrieved_at.isoformat()}
    def number(key: str) -> float | None:
        value = current.get(key)
        return round(float(value), 1) if isinstance(value, (int, float)) else None

    code = current.get("weather_code")
    code = int(code) if isinstance(code, (int, float)) else None
    return {"status": "CURRENT", "condition": condition_for(code),
            "temperature_c": number("temperature_2m"),
            "humidity_percent": number("relative_humidity_2m"),
            "precipitation_mm": number("precipitation"),
            "wind_kmh": number("wind_speed_10m"),
            "gust_kmh": number("wind_gusts_10m"),
            "observed_at": observed.isoformat(),
            "retrieved_at": retrieved_at.isoformat(),
            "source_url": source_url,
            "provider": "Open-Meteo modelled current conditions"}
