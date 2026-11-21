from backend.app.assistant_plan import SuggestedIntent, finalize_plan


def test_bus_request_asks_destination_then_plans_capture():
    suggestion = SuggestedIntent(feature="F06", destination="", follow_up_question="")
    first = finalize_plan("Read the buses in front of me", None, suggestion)
    assert first["action"] == "ASK_DESTINATION"
    assert first["status"] == "NEEDS_FOLLOW_UP"
    second = finalize_plan("Read the buses in front of me", "Alappuzha Beach", suggestion)
    assert second["action"] == "CAPTURE_BUS_BOARD"
    assert second["destination"] == "Alappuzha Beach"


def test_model_cannot_invent_destination_or_execute_action():
    suggestion = SuggestedIntent(feature="F06", destination="Kochi", follow_up_question="")
    assert finalize_plan("Which bus goes to my hotel?", None, suggestion)["status"] == "NEEDS_FOLLOW_UP"
    suggestion = SuggestedIntent(feature="F03", destination="", follow_up_question="")
    assert finalize_plan("Read this Malayalam notice", None, suggestion)["action"] == "CAPTURE_TEXT"


def test_bus_context_overrides_generic_ocr_model_label():
    suggestion = SuggestedIntent(feature="F03", destination="", follow_up_question="")
    plan = finalize_plan("Read the bus names in Malayalam and tell me where they go", None, suggestion)
    assert plan["feature"] == "F06"
    assert plan["follow_up_question"] == "Where are you planning to go?"


def test_bus_map_request_uses_map_without_camera():
    suggestion = SuggestedIntent(feature="F06", destination="Cherthala", follow_up_question="")
    plan = finalize_plan("Show the bus route map from Alappuzha to Cherthala", None, suggestion)
    assert plan["action"] == "SHOW_BUS_MAP"


def test_current_weather_request_cannot_be_misrouted_to_scene():
    suggestion = SuggestedIntent(feature="F01", destination="", follow_up_question="")
    plan = finalize_plan("What is the current weather here?", None, suggestion)
    assert plan["action"] == "OPEN_WEATHER"


def test_general_question_never_starts_camera_from_model_guess():
    wrong = SuggestedIntent(feature="F01", destination="", follow_up_question="")
    plan = finalize_plan("Can you help plan my afternoon?", None, wrong)
    assert plan["status"] == "NEEDS_FOLLOW_UP"
    assert plan["action"] == "ASK_CAMERA"


def test_named_culture_and_shops_override_scene_guess():
    wrong = SuggestedIntent(feature="F01", destination="", follow_up_question="")
    assert finalize_plan("Tell me the history of Ambalappuzha temple", None, wrong)["action"] == "OPEN_CULTURE"
    assert finalize_plan("Find a local coir craft shop", None, wrong)["action"] == "OPEN_DISCOVERY"


def test_bus_route_question_uses_map_without_photo():
    wrong = SuggestedIntent(feature="F01", destination="Alappuzha Beach", follow_up_question="")
    plan = finalize_plan("Does the bus go to Alappuzha Beach?", None, wrong)
    assert plan["action"] == "SHOW_BUS_MAP"


def test_nearby_landmark_is_destination_for_route_check():
    wrong = SuggestedIntent(feature="F06", destination="Cherthala", follow_up_question="")
    plan = finalize_plan("Does a bus from Alappuzha to Cherthala go near International Coir Museum?",
                         None, wrong)
    assert plan["action"] == "SHOW_BUS_MAP"
    assert plan["destination"] == "International Coir Museum"
