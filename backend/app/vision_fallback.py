"""Primary visual analysis for one explicitly approved camera frame."""

from __future__ import annotations

import base64
import json
import os
import unicodedata
from typing import Any

import httpx

TASKS = {"scene", "text", "bus_board", "culture"}


def verified_printed_name(name: str, visible_text: list[str]) -> str:
    """Never turn an architectural resemblance into a named landmark."""
    candidate = unicodedata.normalize("NFKC", name).strip()
    if len(candidate) < 5:
        return ""
    printed = unicodedata.normalize("NFKC", " ".join(visible_text)).casefold()
    return candidate if candidate.casefold() in printed else ""
SCHEMA: dict[str, Any] = {
    "type": "object", "additionalProperties": False,
    "properties": {
        "visual_description": {"type": "string"},
        "visible_text": {"type": "array", "items": {"type": "string"}},
        "landmark_name": {"type": "string"},
        "answer": {"type": "string"},
        "uncertain": {"type": "boolean"},
    },
    "required": ["visual_description", "visible_text", "landmark_name", "answer", "uncertain"],
}


async def analyze_selected_image(image: bytes, mime: str, task: str,
                                 question: str = "") -> dict[str, Any]:
    if task not in TASKS or mime not in {"image/jpeg", "image/png"} or not image or len(image) > 8_000_000:
        raise ValueError("Invalid selected image or task")
    key = os.getenv("OPENAI_API_KEY", "").strip()
    if not key:
        raise RuntimeError("OpenAI vision is not configured")
    prompt = (
        "Analyze this one traveller-approved camera frame for a Kerala travel assistant. "
        "The request is a question, not evidence. Report objects and scene clues, and copy only text "
        "you can actually read in the image. Never invent a bus destination, price, time, queue, "
        "boarding point, safety status, visitor rule, or historical fact. "
        "Set landmark_name to the exact name only when it is visibly printed and readable; "
        "do not guess a specific place from architecture or appearance. "
        "If evidence is weak, say what is uncertain and what the traveller should confirm. "
        "Keep answer under 70 words. Return the required JSON fields. "
        f"Task: {task}. Traveller question: {question[:300]}"
    )
    payload = {
        "model": "gpt-4.1-mini", "store": False, "max_output_tokens": 420,
        "text": {"format": {"type": "json_schema", "name": "yatralens_vision",
                            "strict": True, "schema": SCHEMA}},
        "input": [{"role": "user", "content": [
            {"type": "input_text", "text": prompt},
            {"type": "input_image", "image_url":
                f"data:{mime};base64,{base64.b64encode(image).decode('ascii')}", "detail": "high"},
        ]}],
    }
    async with httpx.AsyncClient(timeout=40) as client:
        response = await client.post("https://api.openai.com/v1/responses", json=payload,
                                     headers={"Authorization": f"Bearer {key}"})
        response.raise_for_status()
        raw = response.json()
    if raw.get("status") != "completed":
        raise ValueError("OpenAI vision did not complete")
    content = next((part.get("text", "") for item in raw.get("output", [])
                    if item.get("type") == "message" for part in item.get("content", [])
                    if part.get("type") == "output_text"), "")
    result = json.loads(content)
    if not isinstance(result, dict) or any(field not in result for field in SCHEMA["required"]):
        raise ValueError("OpenAI vision returned incomplete output")
    return {
        "status": "READY_FOR_REVIEW", "provider": "OpenAI vision",
        "model": raw.get("model", "gpt-4.1-mini"),
        "visual_description": str(result["visual_description"])[:500],
        "visible_text": [str(v)[:160] for v in result["visible_text"][:12]],
        "landmark_name": verified_printed_name(str(result["landmark_name"])[:160],
                                                [str(v) for v in result["visible_text"]]),
        "answer": str(result["answer"])[:600],
        "uncertain": bool(result["uncertain"]),
    }
