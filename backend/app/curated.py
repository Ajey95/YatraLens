"""Small, source-linked pilot corpus. A name hint is not visual recognition."""

from __future__ import annotations

from dataclasses import dataclass
from math import asin, cos, radians, sin, sqrt


@dataclass(frozen=True)
class CuratedPlace:
    place_id: str
    name: str
    category: str
    area: str
    keywords: tuple[str, ...]
    facts: tuple[str, ...]
    source_url: str
    source_owner: str
    reviewed_on: str
    # This is only a coarse area gate. It is never exposed as a map pin.
    area_lat: float
    area_lon: float
    area_radius_km: float


PLACES = (
    CuratedPlace(
        "ambalappuzha-krishna-temple", "Ambalappuzha Sree Krishna Swamy Temple",
        "culture", "Ambalappuzha, Alappuzha district",
        ("ambalappuzha", "ambalapuzha", "krishna temple", "sree krishna"),
        ("A Kerala style temple dedicated to Lord Krishna in the Parthasarathy form.",
         "Kerala Tourism describes its Chuttambalam and wall paintings.",
         "Palpayasam is a well known offering associated with this temple."),
        "https://www.keralatourism.org/temples/alappuzha/ambalapuzha-sree-krishna",
        "Kerala Tourism", "2026-11-21", 9.3836, 76.3695, 20.0,
    ),
    CuratedPlace(
        "international-coir-museum", "International Coir Museum",
        "coir_experience", "Kalavoor, Alappuzha district",
        ("coir", "coconut fibre", "museum", "kalavoor"),
        ("The Coir Board places the museum at its Kalavoor complex.",
         "It presents coir making and coir products; confirm current access before travel."),
        "https://coirboard.gov.in/?page_id=1731", "Coir Board", "2026-11-21",
        9.49, 76.33, 30.0,
    ),
)


def area_distance_km(lat: float, lon: float, place: CuratedPlace) -> float:
    a, b = radians(lat), radians(place.area_lat)
    d_lat, d_lon = b - a, radians(place.area_lon - lon)
    h = sin(d_lat / 2) ** 2 + cos(a) * cos(b) * sin(d_lon / 2) ** 2
    return 12_742 * asin(min(1.0, sqrt(h)))


def cultural_lookup(lat: float, lon: float, name_hint: str) -> dict:
    hint = name_hint.casefold().strip()
    for place in PLACES:
        if place.category != "culture" or not any(word in hint for word in place.keywords):
            continue
        if area_distance_km(lat, lon, place) > place.area_radius_km:
            continue
        return {
            "status": "CANDIDATE_REQUIRES_CONFIRMATION", "place_id": place.place_id,
            "name": place.name, "area": place.area, "facts": place.facts,
            "source_url": place.source_url, "source_owner": place.source_owner,
            "source_reviewed_on": place.reviewed_on,
            "identity_basis": "user name hint and coarse GPS area",
            "visitor_rules": None, "opening_status": "UNKNOWN",
            "reason": "Confirm the site name on a sign before relying on site-specific facts or rules",
        }
    return {"status": "UNKNOWN", "reason": "No source-linked landmark matched the name and coarse area"}


def discover(lat: float, lon: float, interest: str) -> dict:
    query = interest.casefold().strip()
    matches = []
    for place in PLACES:
        if place.category != "coir_experience" or not any(word in query for word in place.keywords):
            continue
        if area_distance_km(lat, lon, place) > place.area_radius_km:
            continue
        matches.append({
            "place_id": place.place_id, "name": place.name, "area": place.area,
            "category": place.category, "description": place.facts[0],
            "source_url": place.source_url, "source_owner": place.source_owner,
            "source_reviewed_on": place.reviewed_on,
            "opening_status": "UNKNOWN", "map_pin": None,
            "contact": None, "seller_verified": False,
        })
    return {"status": "CANDIDATES" if matches else "UNKNOWN", "results": matches,
            "reason": "Confirm access and location with the source before visiting" if matches else
                      "No sourced local option matches this interest and area"}
