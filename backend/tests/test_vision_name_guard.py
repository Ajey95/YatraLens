from backend.app.vision_fallback import verified_printed_name


def test_landmark_identity_requires_name_in_visible_print() -> None:
    assert verified_printed_name("Padmanabhaswamy Temple", []) == ""
    assert verified_printed_name("Padmanabhaswamy Temple", ["Temple entrance"]) == ""
    assert verified_printed_name("Padmanabhaswamy Temple",
                                 ["Sree Padmanabhaswamy Temple, Thiruvananthapuram"]) == \
        "Padmanabhaswamy Temple"
