from datetime import datetime, timezone

import httpx
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


def test_visual_fallback_requires_selected_frame_consent(monkeypatch):
    monkeypatch.setenv("YATRALENS_DEV_TOKEN", "test-token")
    called = []

    async def fake_vision(image, mime, task, question):
        called.append((image, mime, task, question))
        return {"status": "READY_FOR_REVIEW", "answer": "A temple-like building is visible."}

    monkeypatch.setattr("backend.app.main.analyze_selected_image", fake_vision)
    client = TestClient(app)
    files = {"file": ("frame.jpg", b"\xff\xd8\xfffixture", "image/jpeg")}
    data = {"task": "culture", "question": "What temple is this?"}
    assert client.post("/v1/vision/analyze", files=files, data=data).status_code == 401
    headers = {"Authorization": "Bearer test-token"}
    assert client.post("/v1/vision/analyze", files=files, data=data,
                       headers=headers).status_code == 403
    assert called == []
    headers["X-YatraLens-Cloud-Consent"] = "approved-current-trip"
    response = client.post("/v1/vision/analyze", files=files, data=data, headers=headers)
    assert response.status_code == 200
    assert called == [(b"\xff\xd8\xfffixture", "image/jpeg", "culture", "What temple is this?")]


def test_selected_text_crop_uses_openai_vision_first(monkeypatch):
    monkeypatch.setenv("YATRALENS_DEV_TOKEN", "test-token")
    monkeypatch.setenv("SARVAM_API_KEY", "unused-test-key")

    async def fake_vision(image, mime, task, question=""):
        assert (mime, task) == ("image/jpeg", "text")
        return {"provider": "OpenAI visual fallback", "model": "fixture-model",
                "visible_text": ["ടിക്കറ്റ് നിരക്ക്: ₹1200"]}

    monkeypatch.setattr("backend.app.main.analyze_selected_image", fake_vision)
    response = TestClient(app).post("/v1/ocr/indic",
        files={"file": ("frame.jpg", b"\xff\xd8\xfffixture", "image/jpeg")},
        data={"language": "ml-IN", "purpose": "selected_text_crop"},
        headers={"Authorization": "Bearer test-token",
                 "X-YatraLens-Cloud-Consent": "approved-current-trip"})
    assert response.status_code == 200
    assert response.json()["provider"] == "OpenAI visual fallback"
    assert "₹1200" in response.json()["text"]


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


def test_assistant_plan_routes_clear_camera_request_when_model_json_is_incomplete(monkeypatch):
    monkeypatch.setenv("YATRALENS_DEV_TOKEN", "test-token")
    monkeypatch.setenv("SARVAM_API_KEY", "fixture-key")

    async def incomplete_reply(self, url, *args, **kwargs):
        return httpx.Response(200, request=httpx.Request("POST", url),
                              json={"choices": [{"message": {"content": None}}]})

    monkeypatch.setattr(httpx.AsyncClient, "post", incomplete_reply)
    response = TestClient(app).post("/v1/assistant/plan", json={
        "utterance": "What do you see in front of me?", "language_code": "te-IN",
    }, headers={"Authorization": "Bearer test-token",
                "X-YatraLens-Cloud-Consent": "approved-current-trip"})
    assert response.status_code == 200
    assert response.json()["action"] == "CAPTURE_SCENE"
    assert response.json()["model"] == "local-rule-fallback"


def test_tengglish_turn_uses_reviewed_sarvam_translation_fallback(monkeypatch):
    monkeypatch.setenv("YATRALENS_DEV_TOKEN", "test-token")
    monkeypatch.setenv("SARVAM_API_KEY", "fixture-key")

    async def provider_reply(self, url, *args, **kwargs):
        payload = ({"choices": [{"message": {"content": None}}]}
                   if url.endswith("chat/completions") else
                   {"translated_text": "ബോട്ട് ജെട്ടി നിരക്ക് എത്രയാണ്?"})
        return httpx.Response(200, request=httpx.Request("POST", url), json=payload)

    monkeypatch.setattr(httpx.AsyncClient, "post", provider_reply)
    response = TestClient(app).post("/v1/text/translate", json={
        "text": "Boat jetty fare entha?", "source_language_code": "te-IN",
        "target_language_code": "ml-IN",
    }, headers={"Authorization": "Bearer test-token",
                "X-YatraLens-Cloud-Consent": "approved-current-trip"})
    assert response.status_code == 200
    assert response.json()["model"] == "mayura:v1"
    assert "TENGGLISH_TRANSLATION_REVIEW_BEFORE_SPEAKING" in response.json()["review_flags"]


def test_malayalam_reply_rewrites_english_heavy_result_as_tengglish(monkeypatch):
    monkeypatch.setenv("YATRALENS_DEV_TOKEN", "test-token")
    monkeypatch.setenv("SARVAM_API_KEY", "fixture-key")

    async def provider_reply(self, url, *args, **kwargs):
        payload = ({"choices": [{"message": {"content":
                   '{"translation":"Charge 1200 rupees. Returns at 4 o’clock."}'}}]}
                   if url.endswith("chat/completions") else
                   {"translated_text": "1200 rupaayalu charge. Naalugu gantalaku thirigi vastaam."})
        return httpx.Response(200, request=httpx.Request("POST", url), json=payload)

    monkeypatch.setattr(httpx.AsyncClient, "post", provider_reply)
    response = TestClient(app).post("/v1/text/translate", json={
        "text": "ചാർജ് 1200 രൂപ. തിരിച്ചു വരുന്നത് 4 മണിക്ക്.",
        "source_language_code": "ml-IN", "target_language_code": "te-IN",
        "output_script": "roman",
    }, headers={"Authorization": "Bearer test-token",
                "X-YatraLens-Cloud-Consent": "approved-current-trip"})
    assert response.status_code == 200
    assert response.json()["translated_text"].startswith("1200 rupaayalu")


def test_bus_board_reads_known_place_without_inventing_route(monkeypatch):
    monkeypatch.setenv("YATRALENS_DEV_TOKEN", "test-token")
    client = TestClient(app)
    response = client.post("/v1/transport/check", json={
        "board_text": "KSRTC ആലപ്പുഴ", "destination": "Alappuzha",
    }, headers={"Authorization": "Bearer test-token",
                "X-YatraLens-Cloud-Consent": "approved-current-trip"})
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "BOARD_NAME_MATCHES_DESTINATION"
    assert body["board_places"] == ["Alappuzha"]
    assert "no service timetable" in body["route_data_origin"]
