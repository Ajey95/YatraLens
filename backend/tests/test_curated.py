from fastapi.testclient import TestClient

from backend.app.main import app


def test_culture_requires_consent_and_never_claims_visual_identity(monkeypatch):
    monkeypatch.setenv("YATRALENS_DEV_TOKEN", "test-token")
    client = TestClient(app)
    payload = {"lat": 9.39, "lon": 76.37, "name_hint": "Ambalappuzha temple"}
    auth = {"Authorization": "Bearer test-token"}
    assert client.post("/v1/culture/lookup", headers=auth, json=payload).status_code == 403
    auth["X-YatraLens-Cloud-Consent"] = "approved-current-trip"
    result = client.post("/v1/culture/lookup", headers=auth, json=payload).json()
    assert result["status"] == "CANDIDATE_REQUIRES_CONFIRMATION"
    assert result["identity_basis"] == "user name hint and coarse GPS area"
    assert result["visitor_rules"] is None
    assert result["source_url"].startswith("https://www.keralatourism.org/")
    payload["name_hint"] = "unknown shrine"
    assert client.post("/v1/culture/lookup", headers=auth, json=payload).json()["status"] == "UNKNOWN"


def test_discovery_has_source_and_no_invented_hours_or_map_pin(monkeypatch):
    monkeypatch.setenv("YATRALENS_DEV_TOKEN", "test-token")
    client = TestClient(app)
    headers = {"Authorization": "Bearer test-token", "X-YatraLens-Cloud-Consent": "approved-current-trip"}
    result = client.post("/v1/discovery/search", headers=headers,
                         json={"lat": 9.506, "lon": 76.353, "interest": "coir craft"}).json()
    assert result["status"] == "CANDIDATES"
    assert result["results"][0]["source_owner"] == "Coir Board"
    assert result["results"][0]["opening_status"] == "UNKNOWN"
    assert result["results"][0]["map_pin"] is None
    far = client.post("/v1/discovery/search", headers=headers,
                      json={"lat": 28.6, "lon": 77.2, "interest": "coir craft"}).json()
    assert far["status"] == "UNKNOWN"
