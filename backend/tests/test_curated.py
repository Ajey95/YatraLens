from fastapi.testclient import TestClient

from backend.app.main import app


def test_culture_requires_consent_and_never_claims_visual_identity(monkeypatch):
    monkeypatch.setenv("YATRALENS_DEV_TOKEN", "test-token")
    monkeypatch.setenv("GOOGLE_MAPS_API_KEY", "")
    from backend.app import main
    async def no_page(_name):
        return None
    monkeypatch.setattr(main, "cultural_page", no_page)
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
    monkeypatch.setenv("GOOGLE_MAPS_API_KEY", "")
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


def test_live_place_candidates_keep_source_and_unknown_opening(monkeypatch):
    monkeypatch.setenv("YATRALENS_DEV_TOKEN", "test-token")
    from backend.app import main

    async def fake_places(*_args, **_kwargs):
        return [{"place_id": "test-id", "name": "Example craft shop", "area": "Alappuzha",
                 "latitude": 9.5, "longitude": 76.34, "source_url": "https://maps.google.com/example",
                 "source_owner": "Google Maps Places", "primary_type": "store"}]

    monkeypatch.setattr(main, "search_places", fake_places)
    client = TestClient(app)
    headers = {"Authorization": "Bearer test-token", "X-YatraLens-Cloud-Consent": "approved-current-trip"}
    result = client.post("/v1/discovery/search", headers=headers,
                         json={"lat": 9.5, "lon": 76.34, "interest": "coir craft"}).json()
    assert result["results"][0]["name"] == "Example craft shop"
    assert result["results"][0]["opening_status"] == "UNKNOWN"
    assert result["results"][0]["map_pin"]["latitude"] == 9.5


def test_culture_adds_separate_web_summary_and_image_source(monkeypatch):
    monkeypatch.setenv("YATRALENS_DEV_TOKEN", "test-token")
    from backend.app import main

    async def page(_name):
        return {"summary": "A source linked historical description.",
                "source_url": "https://en.wikipedia.org/wiki/Example",
                "image_url": "https://upload.wikimedia.org/example.jpg",
                "image_source_url": "https://en.wikipedia.org/wiki/Example"}

    monkeypatch.setattr(main, "cultural_page", page)
    client = TestClient(app)
    headers = {"Authorization": "Bearer test-token", "X-YatraLens-Cloud-Consent": "approved-current-trip"}
    result = client.post("/v1/culture/lookup", headers=headers,
                         json={"lat": 9.39, "lon": 76.37,
                               "name_hint": "Ambalappuzha temple"}).json()
    assert result["source_owner"] == "Kerala Tourism"
    assert result["web_summary"] == "A source linked historical description."
    assert result["image_url"].startswith("https://upload.wikimedia.org/")


def test_landmark_near_indicative_bus_corridor_needs_conductor_confirmation(monkeypatch):
    monkeypatch.setenv("YATRALENS_DEV_TOKEN", "test-token")
    from backend.app import main

    async def fake_places(*_args, **_kwargs):
        return [{"place_id": "museum", "name": "International Coir Museum",
                 "area": "Kalavoor", "latitude": 9.58, "longitude": 76.33,
                 "source_url": "https://maps.google.com/museum", "source_owner": "Google Maps Places",
                 "primary_type": "museum"}]

    monkeypatch.setattr(main, "search_places", fake_places)
    client = TestClient(app)
    headers = {"Authorization": "Bearer test-token", "X-YatraLens-Cloud-Consent": "approved-current-trip"}
    result = client.post("/v1/transport/map", headers=headers, json={
        "destination": "International Coir Museum",
        "request_text": "Does the bus from Alappuzha to Cherthala go near International Coir Museum?"}).json()
    assert result["status"] == "POSSIBLY_NEAR_CORRIDOR_PLACE"
    assert result["nearest_named_place"] == "Kalavoor"
    assert "does not confirm" in result["reason"]
