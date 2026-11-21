"""Deterministic OCR cues; these are candidates, never a notice interpretation."""

from __future__ import annotations

import re


TIME = re.compile(r"\b(?:[01]?\d|2[0-3]):[0-5]\d\b|\b(?:1[0-2]|[1-9])\s*(?:am|pm)\b", re.IGNORECASE)
DATE = re.compile(r"\b(?:[0-3]?\d[/-][01]?\d[/-]\d{2,4}|\d{4}-[01]\d-[0-3]\d)\b")
NUMBER = re.compile(r"(?<!\w)(?:₹\s*)?\d+(?:[.,]\d+)*(?!\w)")


def scan_cues(text: str) -> dict:
    def found(pattern: re.Pattern) -> list[dict]:
        return [{"text": match.group(), "start_char": match.start(), "end_char": match.end()}
                for match in pattern.finditer(text)]

    return {"times": found(TIME), "dates": found(DATE), "numeric_tokens": found(NUMBER),
            "status": "CANDIDATES_NEED_REVIEW",
            "reason": "OCR may alter characters or digits; confirm the source sign before acting"}
