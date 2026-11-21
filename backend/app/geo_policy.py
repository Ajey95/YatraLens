"""Accuracy-aware destination checks; no routing or exact-position claims."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from math import asin, atan2, cos, degrees, radians, sin, sqrt


@dataclass(frozen=True)
class GeoDecision:
    status: str
    distance_m: int | None
    bearing_degrees: int | None
    reason: str
    source_id: str | None


def assess_saved_destination(current_lat: float, current_lon: float, accuracy_m: float,
                             fix_at: datetime, destination_lat: float, destination_lon: float,
                             destination_source_id: str, verified_at: datetime | None = None,
                             now: datetime | None = None) -> GeoDecision:
    now = now or datetime.now(timezone.utc)
    if any(not (-90 <= lat <= 90) for lat in (current_lat, destination_lat)) or \
       any(not (-180 <= lon <= 180) for lon in (current_lon, destination_lon)) or \
       accuracy_m <= 0 or not destination_source_id or fix_at.tzinfo is None:
        raise ValueError("Valid coordinates, accuracy, timezone and destination source are required")
    if fix_at > now + timedelta(seconds=30) or now - fix_at > timedelta(minutes=2):
        return GeoDecision("LOCATION_STALE", None, None, "Obtain a fresh GPS fix", destination_source_id)
    if accuracy_m > 100:
        return GeoDecision("LOCATION_UNCERTAIN", None, None,
                           "GPS accuracy is too low to compare nearby jetties", destination_source_id)
    if verified_at is not None and (verified_at.tzinfo is None or
                                    now - verified_at > timedelta(days=30)):
        return GeoDecision("DESTINATION_SOURCE_STALE", None, None,
                           "Verify the saved destination before acting", destination_source_id)

    lat1, lat2 = radians(current_lat), radians(destination_lat)
    delta_lat = lat2 - lat1
    delta_lon = radians(destination_lon - current_lon)
    h = sin(delta_lat / 2) ** 2 + cos(lat1) * cos(lat2) * sin(delta_lon / 2) ** 2
    distance = 2 * 6_371_000 * asin(min(1.0, sqrt(h)))
    y = sin(delta_lon) * cos(lat2)
    x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(delta_lon)
    bearing = int(round((degrees(atan2(y, x)) + 360) % 360))
    threshold = max(150.0, 3 * accuracy_m)
    if distance > threshold:
        return GeoDecision("POSSIBLE_DESTINATION_MISMATCH", int(round(distance)), bearing,
                           "Current GPS fix is away from the sourced destination; confirm the jetty name",
                           destination_source_id)
    return GeoDecision("NEAR_SAVED_DESTINATION", int(round(distance)), bearing,
                       "Within the distance and GPS-accuracy threshold; confirm the entrance or sign",
                       destination_source_id)
