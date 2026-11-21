from backend.app.bus_headsigns import approximate_place_pin, place_map_answer, read_board_place


def test_malayalam_board_name_matches_stated_destination_without_claiming_stops():
    result = read_board_place("KSRTC ആലപ്പുഴ", "Alappuzha")
    assert result["status"] == "BOARD_NAME_MATCHES_DESTINATION"
    assert result["board_places"] == ["Alappuzha"]
    assert "conductor" in result["reason"]
    assert "stops" not in result


def test_other_board_name_does_not_rule_out_intermediate_destination():
    result = read_board_place("ചേർത്തല", "Alappuzha Beach")
    assert result["status"] == "BOARD_PLACE_READ"
    assert "may still pass" in result["reason"]


def test_ambiguous_and_unreadable_boards_do_not_pick_one_route():
    assert read_board_place("ആലപ്പുഴ ചേർത്തല", "Kollam")["status"] == "MULTIPLE_BOARD_PLACES"
    assert read_board_place("KSRTC", "Alappuzha") is None


def test_two_endpoint_board_can_show_an_indicative_major_place_corridor():
    result = read_board_place("ആലപ്പുഴ ചേർത്തല", "Kalavoor")
    assert result["status"] == "POSSIBLE_CORRIDOR"
    assert result["major_places"] == ["Alappuzha", "Kalavoor", "Cherthala"]
    assert "exact bus" in result["reason"]
    assert "timetable" not in result["reason"]


def test_map_pin_is_approximate_place_centre_not_bus_stop():
    pin = approximate_place_pin("Cherthala")
    assert pin["label"] == "Cherthala"
    assert "not a bus stop" in pin["precision"]
    assert approximate_place_pin("Alappuzha Beach") is None


def test_route_map_query_shows_sample_places_without_claiming_a_service():
    result = place_map_answer("Cherthala", "Show bus route from Alappuzha to Cherthala")
    assert result["status"] == "PIN_AVAILABLE"
    assert result["major_places"] == ["Alappuzha", "Kalavoor", "Cherthala"]
    assert "not a confirmed bus service" in result["reason"]
    model_combined_name = place_map_answer("Alappuzha to Cherthala",
                                           "Show bus route from Alappuzha to Cherthala")
    assert model_combined_name["map_pin"]["label"] == "Cherthala"
