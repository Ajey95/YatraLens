"""Read the public Alappuzha private-bus schedule used by the linked web app.

This is a community transcription of RTI material, not a live service feed.
Only candidate routes and listed clock times are returned; the rider confirms
the actual vehicle, direction and operating day with the conductor.
"""

from __future__ import annotations

import json
import re
import threading
from urllib.request import Request, urlopen

from backend.app.bus_headsigns import headsign_places

SOURCE_COMMIT = "22752694da65874a791e5e30adc12a6cf18fa7cd"
SOURCE_URL = ("https://raw.githubusercontent.com/amith-vp/Kerala-Private-Bus-Timing/"
              f"{SOURCE_COMMIT}/alappuzha.json")
WEB_APP_URL = "https://github.com/albinsabu2023/Kerala-Private-Bus-Timing-App"
_lock = threading.Lock()
_rows: list[dict] | None = None


def load_rows() -> list[dict]:
    global _rows
    if _rows is not None:
        return _rows
    with _lock:
        if _rows is not None:
            return _rows
        request = Request(SOURCE_URL, headers={"User-Agent": "YatraLens-hackathon/1.0"})
        with urlopen(request, timeout=8) as response:
            data = response.read(12_000_001)
        if len(data) > 12_000_000:
            raise ValueError("Bus dataset exceeds the expected size")
        parsed = json.loads(data)
        rows = parsed.get("busSchedules", [])
        if not isinstance(rows, list) or len(rows) > 3000:
            raise ValueError("Unexpected bus dataset format")
        _rows = rows
        return rows


def _norm(value: str) -> str:
    return " ".join(re.findall(r"[a-z0-9]+", value.casefold()))


def _place_matches(station: str, place: str) -> bool:
    station_words = _norm(station).split()
    place_words = _norm(place).split()
    if not station_words or not place_words:
        return False
    return any(station_words[i:i + len(place_words)] == place_words
               for i in range(len(station_words) - len(place_words) + 1))


def _origin_hints(text: str, destination: str) -> list[str]:
    places = headsign_places(text)
    target = _norm(destination)
    return [place for place in places if _norm(place) != target]


def _times_for_direction(row: dict, origin: str, destination: str) -> list[dict]:
    times: list[dict] = []
    for trip in row.get("schedule", []):
        stations = trip.get("stations", [])
        for start_i, start in enumerate(stations):
            if not _place_matches(str(start.get("station", "")), origin):
                continue
            for end in stations[start_i + 1:]:
                if _place_matches(str(end.get("station", "")), destination):
                    end_i = stations.index(end, start_i + 1)
                    times.append({
                        "from": start.get("station"), "to": end.get("station"),
                        "ordered_stops": [station.get("station") for station in
                                          stations[start_i:end_i + 1]],
                        "listed_departure": start.get("departureTime"),
                        "listed_arrival": end.get("arrivalTime"),
                    })
                    break
            break
        if len(times) >= 3:
            break
    return times


def lookup_private_bus(board_or_request: str, destination: str,
                       *, map_query: bool = False, rows: list[dict] | None = None) -> dict | None:
    if not destination.strip():
        return None
    destination_places = headsign_places(destination)
    target = destination_places[-1] if destination_places else destination.strip()
    origins = _origin_hints(board_or_request, target)
    if not origins and re.search(r"\bKL\s*\d{1,2}\b", board_or_request, re.I) is None:
        return None
    if rows is None:
        rows = load_rows()
    if not rows:
        return None
    board_vehicle = _norm(board_or_request)
    ranked: list[tuple[int, dict]] = []
    for row in rows:
        vehicle = str(row.get("Vehicle Number", "")).strip()
        route = [str(place).strip() for place in row.get("route", []) if str(place).strip()]
        if not vehicle or not route or not any(_place_matches(place, target) for place in route):
            continue
        vehicle_match = bool(_norm(vehicle)) and _norm(vehicle) in board_vehicle
        origin = next((hint for hint in origins if any(_place_matches(place, hint) for place in route)), None)
        if not vehicle_match and origin is None:
            continue
        if not map_query and not vehicle_match and not origins:
            continue
        listed_times = _times_for_direction(row, origin, target) if origin else []
        candidate = {"vehicle_number": vehicle, "major_places": route,
                     "listed_times": listed_times, "board_vehicle_match": vehicle_match}
        score = (100 if vehicle_match else 0) + (20 if listed_times else 0) - len(route)
        ranked.append((score, candidate))
    if not ranked:
        return None
    ranked.sort(key=lambda item: (-item[0], item[1]["vehicle_number"]))
    candidates = [item[1] for item in ranked[:3]]
    best = candidates[0]
    number_list = ", ".join(candidate["vehicle_number"] for candidate in candidates)
    if best["listed_times"]:
        route_text = " → ".join(best["listed_times"][0]["ordered_stops"][:7])
        route_clause = f"One listed trip goes {route_text}."
    else:
        route_text = ", ".join(best["major_places"][:7])
        route_clause = f"A candidate route lists these major places: {route_text}."
    time_note = ""
    asks_time = any(word in board_or_request.casefold() for word in
                    ("time", "when", "schedule", "departure", "arrival"))
    if asks_time and best["listed_times"]:
        listed = best["listed_times"][0]
        time_note = (f" A listed {listed['from']} → {listed['to']} trip shows "
                     f"{listed['listed_departure']} to {listed['listed_arrival']}; "
                     "this is not a live arrival time.")
    return {
        "status": "PRIVATE_BUS_CANDIDATES",
        "candidates": candidates,
        "source_id": SOURCE_URL,
        "source_url": SOURCE_URL,
        "source_kind": "community RTI schedule transcription; date and current operation unverified",
        "reason": (f"The Alappuzha private-bus dataset lists candidate vehicle(s) {number_list} "
                   f"for {target}. {route_clause}{time_note} "
                   "Confirm the actual bus, direction, stops and today's times with the conductor."),
    }
