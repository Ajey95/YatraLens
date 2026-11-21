"""Opt-in live provider contract smoke test; no audio is written to disk."""

from __future__ import annotations

import base64
import os

from fastapi.testclient import TestClient

from backend.app.main import app


def main() -> None:
    token = os.environ.get("YATRALENS_DEV_TOKEN")
    if not token:
        raise SystemExit("Set YATRALENS_DEV_TOKEN in ignored .env first")
    client = TestClient(app)
    headers = {"Authorization": f"Bearer {token}",
               "X-YatraLens-Cloud-Consent": "approved-current-trip"}
    synthesis = client.post("/v1/speech/synthesize", headers=headers,
        json={"text": "Where is the boat jetty?", "language_code": "en-IN",
              "speaker": "simran_en_customer"})
    print("tts_status", synthesis.status_code)
    if synthesis.status_code != 200:
        print("tts_error", synthesis.json().get("detail"))
        return
    wav = base64.b64decode(synthesis.json()["audio_base64"])
    print("tts_wav_bytes", len(wav), "model", synthesis.json()["model"])
    speech = client.post("/v1/speech/transcribe", headers=headers,
        data={"mode": "transcribe"}, files={"file": ("generated.wav", wav, "audio/wav")})
    print("stt_status", speech.status_code)
    if speech.status_code == 200:
        print("stt_transcript_ascii", ascii(speech.json()["transcript"]))
    else:
        print("stt_error", speech.json().get("detail"))


if __name__ == "__main__":
    main()

