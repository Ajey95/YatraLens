from backend.app.agreement_policy import propose_agreements


def test_local_quote_has_exact_spans_and_stays_unconfirmed():
    transcript = "charge ₹1200. യാത്ര 1 മണിക്കൂർ. മടങ്ങുന്നത് വൈകിട്ട് 4 pm."
    result = propose_agreements(transcript, "LOCAL")
    assert result["agreement_state"] == "UNCONFIRMED_QUOTE"
    assert [item["kind"] for item in result["proposals"]] == [
        "fare_inr", "trip_duration", "return_time_text"]
    assert result["proposals"][0]["normalized_candidate"] == "1200"
    for item in result["proposals"]:
        assert transcript[item["start_char"]:item["end_char"]] == item["raw_excerpt"]
        assert item["state"] == "PROPOSED_NEEDS_REVIEW"
