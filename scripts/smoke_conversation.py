"""Opt-in provider check for the Telugu traveller / Malayalam operator scenario.

Synthetic text only. This does not validate human speech accuracy or translation quality.
"""

from __future__ import annotations

import os

from fastapi.testclient import TestClient

from backend.app.main import app


def main() -> None:
    token = os.environ.get("YATRALENS_DEV_TOKEN")
    if not token:
        raise SystemExit("Set the ignored local .env values first")
    client = TestClient(app)
    headers = {"Authorization": f"Bearer {token}",
               "X-YatraLens-Cloud-Consent": "approved-current-trip"}
    turns = [
        ("te-IN", "ml-IN", "పడవ ఛార్జీ ఎంత? ప్రయాణం ఎంతసేపు? తిరిగి ఎప్పుడు వస్తుంది?"),
        ("ml-IN", "te-IN", "ചാർജ് 1200 രൂപ. യാത്ര ഒരു മണിക്കൂർ. മടങ്ങുന്നത് 16:00 ന്."),
    ]
    for source, target, original in turns:
        translation = client.post("/v1/text/translate", headers=headers, json={
            "text": original, "source_language_code": source, "target_language_code": target})
        print(source, "to", target, "HTTP", translation.status_code)
        translation.raise_for_status()
        translated = translation.json()["translated_text"]
        assert translated.strip()
        print("original", ascii(original))
        print("translated", ascii(translated))
        speech = client.post("/v1/speech/synthesize", headers=headers, json={
            "text": translated, "language_code": target})
        print("speech HTTP", speech.status_code)
        speech.raise_for_status()
        assert speech.json()["audio_base64"]
        print("speech model", speech.json()["model"], "audio chars", len(speech.json()["audio_base64"]))


if __name__ == "__main__":
    main()
