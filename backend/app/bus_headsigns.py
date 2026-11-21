"""Names useful for reading Kerala bus headsigns, not a service timetable."""

from __future__ import annotations

import re

SOURCE_URL = "https://www.keralartc.com/contact/depots-enquiry"

# Depot names are from KSRTC's public directory. Malayalam spellings are search
# aliases for text read from a board; they do not imply any service or stop list.
HEADSIGNS: dict[str, tuple[str, ...]] = {
    "Alappuzha": ("alappuzha", "alleppey", "ആലപ്പുഴ"),
    "Cherthala": ("cherthala", "ചേർത്തല"),
    "Harippad": ("harippad", "haripad", "ഹരിപ്പാട്"),
    "Kayamkulam": ("kayamkulam", "കായംകുളം"),
    "Chengannur": ("chengannur", "chenganoor", "ചെങ്ങന്നൂർ"),
    "Ernakulam": ("ernakulam", "എറണാകുളം"),
    "Kottayam": ("kottayam", "കോട്ടയം"),
    "Kollam": ("kollam", "കൊല്ലം"),
    "Thiruvananthapuram": ("thiruvananthapuram", "trivandrum", "തിരുവനന്തപുരം"),
}

# Small, indicative major-place corridors for an offline hackathon demonstration.
# They are not service records, complete stop lists, or proof that a particular
# vehicle follows this road. A board must show both endpoints before one is used.
CORRIDORS: tuple[tuple[str, ...], ...] = (
    ("Alappuzha", "Kalavoor", "Cherthala"),
    ("Alappuzha", "Ambalappuzha", "Harippad", "Kayamkulam"),
    ("Cherthala", "Aroor", "Ernakulam"),
    ("Alappuzha", "Changanassery", "Kottayam"),
)

# Approximate town centres for orienting a map. These are not bus stops.
PLACE_CENTRES: dict[str, tuple[float, float]] = {
    "Alappuzha": (9.4981, 76.3388),
    "Kalavoor": (9.589, 76.328),
    "Cherthala": (9.684, 76.335),
    "Ambalappuzha": (9.3836, 76.3695),
    "Harippad": (9.280, 76.455),
    "Kayamkulam": (9.180, 76.500),
    "Aroor": (9.882, 76.318),
    "Ernakulam": (9.9816, 76.2999),
    "Changanassery": (9.445, 76.540),
    "Kottayam": (9.5916, 76.5222),
    "Kollam": (8.893, 76.614),
    "Thiruvananthapuram": (8.524, 76.936),
}


def approximate_place_pin(destination: str) -> dict | None:
    target = destination.casefold().strip()
    for place, coords in PLACE_CENTRES.items():
        if target == place.casefold() or target in HEADSIGNS.get(place, ()):
            return {"label": place, "latitude": coords[0], "longitude": coords[1],
                    "precision": "approximate town centre; not a bus stop"}
    return None


def place_map_answer(destination: str, request_text: str) -> dict:
    pin = approximate_place_pin(destination)
    if pin is None:
        mentioned: list[tuple[int, str]] = []
        normalized = request_text.casefold()
        for place, aliases in HEADSIGNS.items():
            for alias in aliases:
                match = re.search(r"(?<!\w)" + re.escape(alias) + r"(?!\w)", normalized)
                if match:
                    mentioned.append((match.start(), place))
        if mentioned:
            pin = approximate_place_pin(max(mentioned)[1])
    if pin is None:
        return {"status": "UNKNOWN", "map_pin": None,
                "reason": "I do not have a place pin for that destination. Ask for a nearby known town."}
    places = headsign_places(request_text)
    corridor = _candidate_corridor(places, pin["label"])
    major = corridor["major_places"] if corridor else []
    route_note = (f"A possible major-place corridor is {', '.join(major)}. " if major else "")
    return {"status": "PIN_AVAILABLE", "map_pin": pin, "major_places": major,
            "reason": (f"Here is an approximate map pin for {pin['label']}. {route_note}"
                       "This is not a confirmed bus service or walking route; ask the conductor.")}


def _candidate_corridor(places: list[str], destination: str) -> dict | None:
    shown = set(places)
    if len(shown) != 2:
        return None
    for corridor in CORRIDORS:
        if shown != {corridor[0], corridor[-1]}:
            continue
        target = destination.casefold().strip()
        if not target:
            return None
        target_places = {part.casefold() for part in corridor}
        for place in corridor:
            target_places.update(HEADSIGNS.get(place, ()))
        if target not in target_places:
            return None
        return {
            "status": "POSSIBLE_CORRIDOR",
            "board_places": places,
            "major_places": list(corridor),
            "source_id": "indicative-major-place-corridor",
            "reason": (f"The board appears to show {corridor[0]} and {corridor[-1]}. "
                       f"A possible road corridor includes {', '.join(corridor)}. "
                       f"Ask the conductor whether this exact bus goes to {destination} and in which direction."),
        }
    return None


def headsign_places(board_text: str) -> list[str]:
    normalized = board_text.casefold()
    found: list[str] = []
    for place, aliases in HEADSIGNS.items():
        if any(re.search(r"(?<!\w)" + re.escape(alias) + r"(?!\w)", normalized)
               for alias in aliases):
            found.append(place)
    return found


def read_board_place(board_text: str, destination: str) -> dict | None:
    places = headsign_places(board_text)
    if not places:
        return None
    if len(places) > 1:
        corridor = _candidate_corridor(places, destination)
        if corridor is not None:
            return corridor
        return {"status": "MULTIPLE_BOARD_PLACES", "board_places": places,
                "reason": "Several place names appear on the board. Ask the conductor which direction this bus is travelling.",
                "source_id": SOURCE_URL}
    place = places[0]
    target = destination.casefold().strip()
    aliases = HEADSIGNS[place]
    matching = target in aliases
    return {"status": "BOARD_NAME_MATCHES_DESTINATION" if matching else "BOARD_PLACE_READ",
            "board_places": [place], "source_id": SOURCE_URL,
            "reason": (f"The board appears to say {place}. Confirm this bus's direction and stop with the conductor."
                       if matching else
                       f"The board appears to say {place}. It may still pass your destination; ask the conductor.")}
