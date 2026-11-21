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
