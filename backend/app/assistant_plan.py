"""Validate a model's suggested intent before the phone performs any action."""

from __future__ import annotations

import re
from typing import Literal

from pydantic import BaseModel, Field

Feature = Literal["F01", "F02", "F03", "F04", "F05", "F06", "F07", "F08", "F09", "F10", "F11", "F12", "F13", "UNKNOWN"]


class SuggestedIntent(BaseModel):
    feature: Feature
    destination: str = Field(max_length=120)
    follow_up_question: str = Field(max_length=240)
    action: str = Field(default="", max_length=40)
    requires_camera: bool = False
    decision_reason: str = Field(default="", max_length=200)


ACTION_FOR = {
    "F01": "CAPTURE_SCENE", "F02": "OPEN_TALK", "F03": "CAPTURE_TEXT",
    "F04": "OPEN_BOOKING", "F05": "OPEN_WEATHER", "F06": "CAPTURE_BUS_BOARD",
    "F07": "OPEN_CULTURE", "F08": "OPEN_MEMORY", "F09": "OPEN_MEMORY",
    "F10": "OPEN_ACCESSIBILITY", "F11": "OPEN_OFFLINE", "F12": "OPEN_DISCOVERY",
    "F13": "OPEN_SENSOR_CONTROLS", "UNKNOWN": "OPEN_TASKS",
}

ALLOWED_ACTIONS = {feature: {action} for feature, action in ACTION_FOR.items()}
ALLOWED_ACTIONS["F06"] = {"CAPTURE_BUS_BOARD", "SHOW_BUS_MAP"}


def finalize_plan(request: str, follow_up_answer: str | None, suggestion: SuggestedIntent) -> dict:
    feature = suggestion.feature
    request_lower = request.casefold()
    answer = (follow_up_answer or "").strip()
    visual_terms = ("point", "camera", "photo", "picture", "look at", "in front of me",
                    "what is this", "what's this", "scan", "this sign", "this board",
                    "this bus", "that bus", "this temple", "that temple")
    visual_request = any(term in request_lower for term in visual_terms)
    # Deterministic task cues override a mistaken model label before sensors start.
    if any(marker in request_lower for marker in ("weather", "temperature", "raining now", "മഴ", "వాతావరణ", "मौसम")):
        feature = "F05"
    elif any(marker in request_lower for marker in ("bus", "ബസ്", "బస్స", "बस")):
        feature = "F06"
    elif any(marker in request_lower for marker in ("talk to", "talk with", "translate", "conversation",
                                                   "local person", "operator", "conductor")) and not any(
            marker in request_lower for marker in ("read", "notice", "sign")):
        feature = "F02"
    elif any(marker in request_lower for marker in ("shop", "artisan", "handicraft", "craft workshop",
                                                   "local market", "coir craft")):
        feature = "F12"
    elif any(marker in request_lower for marker in ("temple", "church", "mosque", "palace",
                                                   "museum", "history", "architecture", "customs")):
        feature = "F07"
    destination = suggestion.destination.strip()
    # A route destination is a traveller-stated slot, never an LLM invention.
    if feature == "F06":
        if answer:
            destination = answer[:120]
        elif match := re.search(r"\bnear\s+([A-Za-z][A-Za-z0-9 -]{2,100}?)(?:\?|$)", request, re.I):
            destination = match.group(1).strip()
        elif match := re.search(r"\bto\s+([A-Za-z][A-Za-z0-9 -]{2,100}?)(?:\?|$)", request, re.I):
            destination = match.group(1).strip()
        elif destination.casefold() not in request_lower:
            destination = ""
        if re.match(r"^(my|our|the|a|this|that)\b", destination, re.I):
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
    if feature == "F01" and not visual_request:
        if answer.casefold() in {"no", "not now", "don't", "do not"}:
            return {"status": "READY", "feature": "UNKNOWN", "action": "OPEN_TASKS",
                    "destination": None, "follow_up_question": None,
                    "reason": "A general question does not authorize a photo"}
        if answer.casefold() not in {"yes", "use camera", "look"}:
            return {"status": "NEEDS_FOLLOW_UP", "feature": feature,
                    "action": "ASK_CAMERA", "destination": None,
                    "follow_up_question": "Should I use the camera to look at what is in front of you?",
                    "reason": "A general question does not authorize a photo"}
    action = suggestion.action if suggestion.action in ALLOWED_ACTIONS[feature] else ACTION_FOR[feature]
    if feature == "F06" and not visual_request:
        action = "SHOW_BUS_MAP"
    elif feature == "F06" and visual_request:
        action = "CAPTURE_BUS_BOARD"
    return {"status": "READY", "feature": feature, "action": action,
            "destination": destination or None, "follow_up_question": None,
            "needs_camera": action in {"CAPTURE_SCENE", "CAPTURE_TEXT", "CAPTURE_BUS_BOARD"} or
                            (feature == "F07" and visual_request),
            "decision_reason": suggestion.decision_reason[:200],
            "reason": "Review the proposed action and any required sharing before proceeding"}
