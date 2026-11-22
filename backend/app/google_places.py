"""Short lived Google Places lookups for user selected, consented queries."""

from __future__ import annotations

import os
import base64
import re
from math import asin, cos, radians, sin, sqrt

import httpx

PLACES_URL = "https://places.googleapis.com/v1/places:searchText"
FIELD_MASK = ("places.id,places.displayName,places.formattedAddress,places.location,"
              "places.googleMapsUri,places.primaryType,places.photos.name,"
              "places.photos.authorAttributions")


async def place_photo(photo_name: str) -> str | None:
    """Return one bounded public Places photo without exposing the Maps key to the phone."""
    key = os.getenv("GOOGLE_MAPS_API_KEY", "").strip()
    if not key or not re.fullmatch(r"places/[^/]+/photos/[^/]+", photo_name):
        return None
    async with httpx.AsyncClient(timeout=15, follow_redirects=True) as client:
        response = await client.get(f"https://places.googleapis.com/v1/{photo_name}/media",
                                    params={"maxWidthPx": 640},
                                    headers={"X-Goog-Api-Key": key})
        response.raise_for_status()
        if response.headers.get("content-type", "").split(";")[0] not in ("image/jpeg", "image/png"):
            return None
        if not response.content or len(response.content) > 1_000_000:
            return None
        return base64.b64encode(response.content).decode("ascii")


def distance_km(lat: float, lon: float, other_lat: float, other_lon: float) -> float:
    a, b = radians(lat), radians(other_lat)
    h = sin((b - a) / 2) ** 2 + cos(a) * cos(b) * sin(radians(other_lon - lon) / 2) ** 2
    return 12_742 * asin(min(1.0, sqrt(h)))


async def search_places(query: str, lat: float | None, lon: float | None, *, count: int = 5,
                        radius_m: int = 15_000) -> list[dict]:
    key = os.getenv("GOOGLE_MAPS_API_KEY", "").strip()
    if not key:
        return []
    body = {"textQuery": query[:160], "pageSize": min(max(count, 1), 10)}
    if lat is not None and lon is not None:
        body["locationBias"] = {"circle": {"center": {"latitude": lat, "longitude": lon},
                                             "radius": float(radius_m)}}
    async with httpx.AsyncClient(timeout=12) as client:
        response = await client.post(PLACES_URL, json=body, headers={
            "X-Goog-Api-Key": key, "X-Goog-FieldMask": FIELD_MASK})
        response.raise_for_status()
        data = response.json()
    result = []
    for item in data.get("places", []):
        location = item.get("location") or {}
        place_lat, place_lon = location.get("latitude"), location.get("longitude")
        if not isinstance(place_lat, (float, int)) or not isinstance(place_lon, (float, int)):
            continue
        distance = distance_km(lat, lon, place_lat, place_lon) if lat is not None and lon is not None else None
        if distance is not None and distance > radius_m / 1000 + 2:
            continue
        name = (item.get("displayName") or {}).get("text", "").strip()
        if not name:
            continue
        result.append({"place_id": item.get("id", ""), "name": name,
                       "area": item.get("formattedAddress", ""),
                       "latitude": place_lat, "longitude": place_lon,
                       "distance_km": round(distance, 1) if distance is not None else None,
                       "primary_type": item.get("primaryType"),
                       "photo_name": ((item.get("photos") or [{}])[0]).get("name", ""),
                       "photo_attributions": ((item.get("photos") or [{}])[0]).get("authorAttributions", []),
                       "source_url": item.get("googleMapsUri", ""),
                       "source_owner": "Google Maps Places"})
    return result
