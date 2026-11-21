"""Extract tentative quote fields while retaining exact evidence spans."""

from __future__ import annotations

import re


AMOUNT = re.compile(r"(?:₹\s*|\b(?:rs\.?|inr)\s*)([\d,]+(?:\.\d{1,2})?)|([\d,]+(?:\.\d{1,2})?)\s*(?:rupees?\b|രൂപ\b)", re.IGNORECASE)
DURATION = re.compile(r"\b(\d+(?:\.\d+)?)\s*(hours?|hrs?|minutes?|mins?)\b|(\d+(?:\.\d+)?)\s*(മണിക്കൂർ|മിനിറ്റ്)", re.IGNORECASE)
CLOCK = re.compile(r"\b(?:[01]?\d|2[0-3]):[0-5]\d\b|\b(?:1[0-2]|[1-9])\s*(?:am|pm)\b", re.IGNORECASE)


def _proposal(kind: str, match: re.Match, value: str) -> dict:
    return {"kind": kind, "raw_excerpt": match.group(), "normalized_candidate": value,
            "start_char": match.start(), "end_char": match.end(),
            "state": "PROPOSED_NEEDS_REVIEW"}


def propose_agreements(transcript: str, turn_owner: str) -> dict:
    proposals: list[dict] = []
    for match in AMOUNT.finditer(transcript):
        value = (match.group(1) or match.group(2)).replace(",", "")
        proposals.append(_proposal("fare_inr", match, value))
    for match in DURATION.finditer(transcript):
        amount = match.group(1) or match.group(3)
        unit = match.group(2) or match.group(4)
        if unit.casefold().startswith(("hour", "hr")) or unit == "മണിക്കൂർ":
            value = f"{amount} hour"
        else:
            value = f"{amount} minute"
        proposals.append(_proposal("trip_duration", match, value))
    for match in CLOCK.finditer(transcript):
        proposals.append(_proposal("return_time_text", match, match.group().strip()))
    proposals.sort(key=lambda item: item["start_char"])
    return {"turn_owner": turn_owner, "transcript": transcript, "proposals": proposals,
            "agreement_state": "UNCONFIRMED_QUOTE" if turn_owner == "LOCAL" else "TRAVELLER_SPEECH",
            "reason": "Check transcription, speaker, price and time with the local person before accepting"}
