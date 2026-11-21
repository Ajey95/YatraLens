from datetime import datetime, timezone

from fastapi.testclient import TestClient

from backend.app.main import app


def test_cloud_ocr_requires_auth_and_current_trip_consent(monkeypatch):
    monkeypatch.setenv("YATRALENS_DEV_TOKEN", "test-token")
    client = TestClient(app)
    jpeg = b"\xff\xd8\xfffake"
    files = {"file": ("crop.jpg", jpeg, "image/jpeg")}
    assert client.post("/v1/ocr/indic", files=files).status_code == 401
    headers = {"Authorization": "Bearer test-token"}
    assert client.post("/v1/ocr/indic", headers=headers, files=files).status_code == 403
    headers["X-YatraLens-Cloud-Consent"] = "approved-current-trip"
    files = {"file": ("crop.jpg", b"not-an-image", "image/jpeg")}
    assert client.post("/v1/ocr/indic", headers=headers, files=files).status_code == 415


def test_unsourced_context_claim_is_rejected(monkeypatch):
    monkeypatch.setenv("YATRALENS_DEV_TOKEN", "test-token")
    client = TestClient(app)
    response = client.post("/v1/context/evaluate", headers={"Authorization": "Bearer test-token"}, json={
        "claims": [{"claim_id": "c1", "predicate": "boarding_time", "value": "15:30",
                    "source_id": "unknown", "observed_at": "2026-11-21T10:00:00Z"}],
        "source_refs": [],
    })
    assert response.status_code == 422


def test_geo_check_requires_consent_and_returns_sourced_uncertainty(monkeypatch):
    monkeypatch.setenv("YATRALENS_DEV_TOKEN", "test-token")
    client = TestClient(app)
    payload = {"current_lat": 9.5, "current_lon": 76.35, "accuracy_m": 12,
               "fix_at": datetime.now(timezone.utc).isoformat(), "destination_lat": 9.5,
               "destination_lon": 76.353, "destination_source_id": "fixture-booking"}
    headers = {"Authorization": "Bearer test-token"}
    assert client.post("/v1/geo/check", headers=headers, json=payload).status_code == 403
    headers["X-YatraLens-Cloud-Consent"] = "approved-current-trip"
    result = client.post("/v1/geo/check", headers=headers, json=payload)
    assert result.status_code == 200
    assert result.json()["status"] == "POSSIBLE_DESTINATION_MISMATCH"
    assert result.json()["destination_source_id"] == "fixture-booking"


def test_assistant_plan_requires_selected_text_consent(monkeypatch):
    monkeypatch.setenv("YATRALENS_DEV_TOKEN", "test-token")
    client = TestClient(app)
    payload = {"utterance": "Read this bus board", "language_code": "en-IN"}
    assert client.post("/v1/assistant/plan", json=payload).status_code == 401
    assert client.post("/v1/assistant/plan", json=payload,
                       headers={"Authorization": "Bearer test-token"}).status_code == 403
