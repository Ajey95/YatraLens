"""Create a clearly labeled synthetic Malayalam operator reply for phone mic testing."""

from __future__ import annotations

import base64
import os
from pathlib import Path
import tempfile

from fastapi.testclient import TestClient

from backend.app.main import app


def main() -> None:
    token = os.environ.get("YATRALENS_DEV_TOKEN")
    if not token:
        raise SystemExit("Set the ignored local .env values first")
    response = TestClient(app).post("/v1/speech/synthesize", headers={
        "Authorization": f"Bearer {token}",
        "X-YatraLens-Cloud-Consent": "approved-current-trip",
    }, json={
        "text": "ചാർജ് ആയിരത്തി ഇരുനൂറ് രൂപ. യാത്ര ഒരു മണിക്കൂർ. മടങ്ങുന്നത് വൈകിട്ട് നാല് മണിക്ക്.",
        "language_code": "ml-IN",
    })
    response.raise_for_status()
    output = Path(tempfile.gettempdir()) / "yatralens-synthetic-local-malayalam.wav"
    output.write_bytes(base64.b64decode(response.json()["audio_base64"]))
    print("synthetic_audio", output, "bytes", output.stat().st_size)


if __name__ == "__main__":
    main()
