from backend.app.private_bus_reference import lookup_private_bus


ROWS = [{
    "Vehicle Number": "KL 32 D 2310",
    "route": ["CHERTHALA BUS STAND", "ALAPPUZHA"],
    "schedule": [
        {"trip": 1, "stations": [
            {"station": "CHERTHALA BUS STAND", "departureTime": "06:40 am", "arrivalTime": "06:40 am"},
            {"station": "ALAPPUZHA", "departureTime": "07:55 am", "arrivalTime": "07:55 am"}]},
        {"trip": 2, "stations": [
            {"station": "ALAPPUZHA", "departureTime": "08:05 am", "arrivalTime": "08:05 am"},
            {"station": "CHERTHALA BUS STAND", "departureTime": "09:20 am", "arrivalTime": "09:20 am"}]},
    ],
}]


def test_private_bus_route_and_directional_listed_time():
    result = lookup_private_bus("When is KL 32 D 2310 from Alappuzha?", "Cherthala", rows=ROWS)
    assert result["status"] == "PRIVATE_BUS_CANDIDATES"
    assert result["candidates"][0]["board_vehicle_match"] is True
    assert result["candidates"][0]["listed_times"][0]["listed_departure"] == "08:05 am"
    assert "ALAPPUZHA → CHERTHALA BUS STAND" in result["reason"]
    assert "not a live arrival time" in result["reason"]
    assert "Confirm" in result["reason"]


def test_private_bus_map_query_uses_both_place_names_without_claiming_current_service():
    result = lookup_private_bus("Show bus route from Alappuzha to Cherthala",
                                "Cherthala", map_query=True, rows=ROWS)
    assert result["candidates"][0]["vehicle_number"] == "KL 32 D 2310"
    assert "ALAPPUZHA → CHERTHALA BUS STAND" in result["reason"]
    assert "08:05" not in result["reason"]


def test_no_vehicle_or_origin_does_not_guess_a_private_bus():
    assert lookup_private_bus("KSRTC ആലപ്പുഴ", "Alappuzha", rows=ROWS) is None
