"""Run five synthetic traveller/operator exchanges through live speech services.

This checks the cloud path, not a real speaker, microphone, or human translation
review. No audio or personal transcript is saved to disk.
"""

from __future__ import annotations

import base64
import os

import requests

from backend.app.main import load_local_env


BASE = "http://127.0.0.1:8000"
EXCHANGES = [
    ("Boat jetty ki charge entha? Return time enti?",
     "ടിക്കറ്റ് നിരക്ക് ആയിരത്തി ഇരുനൂറ് രൂപ. മടക്കം നാലുമണിക്ക്."),
    ("Iddaru ki 1200 rupayala? Life jackets istara?",
     "അതെ, രണ്ട് പേർക്കാണ്. ലൈഫ് ജാക്കറ്റ് നൽകും."),
    ("Trip entha sepu? Malli ekkadiki return?",
     "യാത്ര ഒരു മണിക്കൂർ. ഇതേ ജെട്ടിയിലേക്ക് മടങ്ങും."),
    ("Boarding ekkada? Eppudu start avuthundi?",
     "ഈ ജെട്ടിയിൽ മൂന്നരയ്ക്ക് ബോർഡിംഗ് തുടങ്ങും."),
    ("Heavy rain unte boat trip cancel avuthunda?",
     "ശക്തമായ മഴയുണ്ടെങ്കിൽ സർവീസ് മാറാം. കൗണ്ടറിൽ സ്ഥിരീകരിക്കുക."),
]


def post(path: str, headers: dict[str, str], *, json=None, files=None, data=None) -> dict:
    response = requests.post(BASE + path, headers=headers, json=json, files=files,
                             data=data, timeout=60)
    response.raise_for_status()
    return response.json()


def turn(headers: dict[str, str], source_text: str, source: str, target: str) -> None:
    synthetic = post("/v1/speech/synthesize", headers,
                     json={"text": source_text, "language_code": source})
    source_audio = base64.b64decode(synthetic["audio_base64"])
    heard = post("/v1/speech/transcribe", headers,
                 files={"file": ("synthetic.wav", source_audio, "audio/wav")},
                 data={"mode": "codemix"})
    transcript = heard["transcript"].strip()
    assert transcript, "Synthetic source speech had no transcript"
    print(f"  {source} heard: {transcript}", flush=True)
    translation_request = {"text": transcript, "source_language_code": source,
                           "target_language_code": target}
    if target == "te-IN":
        translation_request["output_script"] = "roman"
    translated = post("/v1/text/translate", headers, json=translation_request)
    output = translated["translated_text"].strip()
    assert output, "Translation was empty"
    spoken = post("/v1/speech/synthesize", headers,
                  json={"text": output, "language_code": target})
    assert len(base64.b64decode(spoken["audio_base64"])) > 1000
    print(f"  {target} reply: {output}")
    print(f"  voice: {spoken['model']}; review: {translated['review_flags']}")


def main() -> None:
    load_local_env()
    token = os.environ["YATRALENS_DEV_TOKEN"]
    headers = {"Authorization": f"Bearer {token}",
               "X-YatraLens-Cloud-Consent": "approved-current-trip"}
    for number, (traveller, local) in enumerate(EXCHANGES, 1):
        print(f"Exchange {number}")
        turn(headers, traveller, "te-IN", "ml-IN")
        turn(headers, local, "ml-IN", "te-IN")


if __name__ == "__main__":
    main()
