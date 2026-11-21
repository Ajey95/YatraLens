from datetime import datetime, timezone

from backend.app.policy import Claim, evaluate_claims, route_decision, translation_review_flags


NOW = datetime(2026, 11, 21, 10, 0, tzinfo=timezone.utc)


def test_material_time_conflict_blocks_firm_advice():
    claims = [
        Claim("a", "boarding_time", "15:30", "booking", NOW, entity_id="boat-1"),
        Claim("b", "boarding_time", "16:00", "operator-audio", NOW, entity_id="boat-1"),
    ]
    decision = evaluate_claims(claims, NOW)
    assert decision.kind == "ASK_CLARIFICATION"
    assert not decision.may_assert
    assert decision.evidence_ids == ("booking", "operator-audio")


def test_route_requires_verified_stops():
    assert route_decision("Alappuzha Beach", "Alappuzha Beach", None) == "UNKNOWN"
    assert route_decision("Beach", "Alappuzha Beach", ["Alappuzha Beach"]) == "MATCH"


def test_unverified_single_claim_is_tentative():
    claim = Claim("a", "boarding_time", "15:30", "booking", NOW, entity_id="boat-1")
    decision = evaluate_claims([claim], NOW)
    assert decision.kind == "INFORM_TENTATIVELY"
    assert not decision.may_assert


def test_translation_flags_changed_time_even_when_money_is_preserved():
    assert translation_review_flags("₹1200, return 16:00", "₹1200, return 4:00 PM") == [
        "NUMBERS_OR_TIME_CHANGED_REVIEW_BEFORE_SPEAKING"
    ]
    assert translation_review_flags("₹1200, 16:00", "₹१२००, १६:००") == []
    assert translation_review_flags("₹1,200 return 16:00", "₹1200 മടക്കം 16:00") == []

