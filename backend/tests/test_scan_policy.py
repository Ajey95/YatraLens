from fastapi.testclient import TestClient

from backend.app.main import app
from backend.app.scan_policy import scan_cues


def test_ocr_cues_keep_exact_spans_and_do_not_claim_notice_meaning():
    text = "ഫെറി 15:30 21/11/2026 ₹1200"
    cues = scan_cues(text)
    assert cues["status"] == "CANDIDATES_NEED_REVIEW"
    assert cues["times"][0]["text"] == "15:30"
    assert cues["dates"][0]["text"] == "21/11/2026"
    for category in ("times", "dates", "numeric_tokens"):
        for item in cues[category]:
            assert text[item["start_char"]:item["end_char"]] == item["text"]


def test_ocr_interpret_requires_consent_before_cloud_translation(monkeypatch):
    monkeypatch.setenv("YATRALENS_DEV_TOKEN", "test-token")
    client = TestClient(app)
    result = client.post("/v1/ocr/interpret", headers={"Authorization": "Bearer test-token"},
                         json={"recognized_text": "15:30", "image_sha256": "a" * 64,
                               "target_language_code": "te-IN"})
    assert result.status_code == 403
