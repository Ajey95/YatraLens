"""Validate a model's suggested intent before the phone performs any action."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

Feature = Literal["F01", "F02", "F03", "F04", "F05", "F06", "F07", "F08", "F09", "F10", "F11", "F12", "F13", "UNKNOWN"]


class SuggestedIntent(BaseModel):
    feature: Feature
    destination: str = Field(max_length=120)
    follow_up_question: str = Field(max_length=240)


ACTION_FOR = {
    "F01": "CAPTURE_SCENE", "F02": "OPEN_TALK", "F03": "CAPTURE_TEXT",
    "F04": "OPEN_BOOKING", "F05": "OPEN_WEATHER", "F06": "CAPTURE_BUS_BOARD",
    "F07": "OPEN_CULTURE", "F08": "OPEN_MEMORY", "F09": "OPEN_MEMORY",
    "F10": "OPEN_ACCESSIBILITY", "F11": "OPEN_OFFLINE", "F12": "OPEN_DISCOVERY",
    "F13": "OPEN_SENSOR_CONTROLS", "UNKNOWN": "OPEN_TASKS",
}


def finalize_plan(request: str, follow_up_answer: str | None, suggestion: SuggestedIntent) -> dict:
    feature = suggestion.feature
    request_lower = request.casefold()
    # Board reading is part of transport when the user names a bus. The model
    # can otherwise mistake the word "read" for generic OCR (F03).
    if any(marker in request_lower for marker in ("weather", "temperature", "raining now", "മഴ", "వాతావరణ", "मौसम")):
        feature = "F05"
    elif any(marker in request_lower for marker in ("bus", "ബസ്", "బస్స", "बस")):
        feature = "F06"
    destination = suggestion.destination.strip()
    answer = (follow_up_answer or "").strip()
    # A route destination is a traveller-stated slot, never an LLM invention.
    if feature == "F06":
        if answer:
            destination = answer[:120]
        elif destination.casefold() not in request_lower:
            destination = ""
        if not destination:
            return {"status": "NEEDS_FOLLOW_UP", "feature": feature,
                    "action": "ASK_DESTINATION", "destination": None,
                    "follow_up_question": "Where are you planning to go?",
                    "reason": "A bus board alone cannot establish the traveller's destination"}
    if feature == "UNKNOWN":
        return {"status": "NEEDS_FOLLOW_UP", "feature": feature,
                "action": "OPEN_TASKS", "destination": None,
                "follow_up_question": "What would you like help with: a sign, a bus, a conversation, weather, or your trip details?",
                "reason": "The requested task was not clear"}
    action = ACTION_FOR[feature]
    if feature == "F06" and any(word in request_lower for word in ("map", "route")) and not any(
        word in request_lower for word in ("point", "read", "scan", "board", "sign")
    ):
        action = "SHOW_BUS_MAP"
    return {"status": "READY", "feature": feature, "action": action,
            "destination": destination or None, "follow_up_question": None,
            "reason": "Review the proposed action and any required sharing before proceeding"}
