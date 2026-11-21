"""Live Sarvam Document AI contract check on a labeled synthetic image."""

from __future__ import annotations

import os
from pathlib import Path
from fastapi.testclient import TestClient
from backend.app.main import app


def main() -> None:
    image = Path(__file__).resolve().parents[1] / "sample-data" / "synthetic_malayalam_notice.png"
    client = TestClient(app)
    response = client.post("/v1/ocr/indic",
        headers={"Authorization": f"Bearer {os.environ['YATRALENS_DEV_TOKEN']}",
                 "X-YatraLens-Cloud-Consent": "approved-current-trip"},
        data={"language": "ml-IN", "purpose": "selected_text_crop"},
        files={"file": (image.name, image.read_bytes(), "image/png")})
    result = response.json()
    print("http_status", response.status_code)
    print("status", result.get("status", result.get("detail")))
    print("provider_status", result.get("provider_status"))
    print("job_id_present", bool(result.get("job_id")))
    print("document_count", len(result.get("documents", [])))
    if result.get("documents"):
        print("first_document_keys", sorted(result["documents"][0].keys()))
        pages = result["documents"][0].get("pages") or []
        if pages:
            print("first_page_keys", sorted(pages[0].keys()))
            print("first_page_ascii", ascii(str(pages[0]))[:1600])


if __name__ == "__main__":
    main()

