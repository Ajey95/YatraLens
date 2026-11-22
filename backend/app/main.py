"""Consent-gated cloud adapters and source-aware trip decisions.

The Android device owns personal trip memory. This service does not retain raw
uploads, transcripts, images, or exact location in a server database.
"""

from __future__ import annotations

import os
import re
import json
import asyncio
import hashlib
from datetime import datetime, timezone
from pathlib import Path
from typing import Annotated, Any, Literal

import httpx
import requests
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from pydantic import BaseModel, Field

from backend.app.policy import Claim, evaluate_claims, translation_review_flags
from backend.app.geo_policy import assess_saved_destination
from backend.app.weather_policy import assess_forecast
from backend.app.weather_current import summarize_current
from backend.app.curated import cultural_lookup, discover
from backend.app.transport_policy import assess_route
from backend.app.bus_headsigns import (PLACE_CENTRES, approximate_place_pin,
    headsign_places, indicative_nearby_place, place_map_answer, read_board_place)
from backend.app.google_places import search_places, place_photo
from backend.app.vision_fallback import analyze_selected_image, TASKS as VISION_TASKS
from backend.app.wikipedia_culture import cultural_page
from backend.app.private_bus_reference import WEB_APP_URL, lookup_private_bus
from backend.app.agreement_policy import propose_agreements
from backend.app.scan_policy import scan_cues
from backend.app.imd_warning import SOURCE_URL as IMD_KERALA_URL, parse_kerala_warning, combine_with_forecast, in_alappuzha_pilot
from backend.app.assistant_plan import SuggestedIntent, fallback_suggestion, finalize_plan


def load_local_env() -> None:
    """Load ignored local secrets without adding a third-party configuration dependency."""
    path = Path(__file__).resolve().parents[2] / ".env"
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip())


load_local_env()

app = FastAPI(title="YatraLens gateway", version="0.1.0")
SARVAM = "https://api.sarvam.ai"


def now_utc() -> str:
    return datetime.now(timezone.utc).isoformat()


def require_auth(authorization: Annotated[str | None, Header()] = None) -> None:
    configured = os.environ.get("YATRALENS_DEV_TOKEN", "")
    if not configured or authorization != f"Bearer {configured}":
        raise HTTPException(status_code=401, detail="A valid device token is required")


def require_cloud_consent(consent: Annotated[str | None, Header(alias="X-YatraLens-Cloud-Consent")] = None) -> None:
    if consent != "approved-current-trip":
        raise HTTPException(status_code=403, detail="Current-trip cloud consent is required")


def sarvam_key() -> str:
    key = os.environ.get("SARVAM_API_KEY", "")
    if not key:
        raise HTTPException(status_code=503, detail="Sarvam is not configured; use local fallback")
    return key


def upstream_error(exc: Exception) -> HTTPException:
    if isinstance(exc, httpx.HTTPStatusError):
        code = exc.response.status_code
        return HTTPException(status_code=503, detail=f"Provider returned HTTP {code}; use local fallback")
    return HTTPException(status_code=503, detail="Provider unavailable; use local fallback")


def collect_ocr_spans(documents: list[dict[str, Any]]) -> list[dict[str, Any]]:
    spans: list[dict[str, Any]] = []
    for document_index, document in enumerate(documents):
        for page in document.get("pages") or []:
            for block in page.get("blocks") or []:
                text = block.get("text")
                if isinstance(text, str) and text.strip():
                    spans.append({
                        "document_index": document_index,
                        "page_num": page.get("page_num"),
                        "block_id": block.get("block_id"),
                        "text": text,
                        "bbox_norm": block.get("bbox_norm"),
                        "reading_order": block.get("reading_order"),
                        "confidence": None,  # Current parse response has no calibrated block confidence.
                    })
    return spans


class TranslationRequest(BaseModel):
    text: str = Field(min_length=1, max_length=2000)
    source_language_code: str
    target_language_code: str
    output_script: Literal["roman", "fully-native", "spoken-form-in-native"] | None = None


class ClaimInput(BaseModel):
    claim_id: str
    predicate: str
    value: str
    source_id: str
    observed_at: datetime
    valid_from: datetime | None = None
    valid_until: datetime | None = None
    entity_id: str | None = None
    status: str = "PROPOSED"


class EvaluationRequest(BaseModel):
    claims: list[ClaimInput]
    source_refs: list[str]


class RouteRecord(BaseModel):
    route_id: str = Field(min_length=1, max_length=100)
    source_id: str = Field(min_length=1, max_length=200)
    board_label: str = Field(min_length=1, max_length=200)
    stops: list[str] = Field(min_length=1, max_length=100)
    complete_stops: bool = False
    valid_until: datetime | None = None


class RouteRequest(BaseModel):
    board_text: str = ""
    destination: str = ""
    route_records: list[RouteRecord] = Field(default_factory=list, max_length=20)


class TransportMapRequest(BaseModel):
    destination: str = Field(min_length=1, max_length=120)
    request_text: str = Field(min_length=1, max_length=1000)


class SynthesisRequest(BaseModel):
    text: str = Field(min_length=1, max_length=2500)
    language_code: str
    speaker: str | None = None


class WeatherAssessmentRequest(BaseModel):
    lat: float
    lon: float
    event_start: datetime
    event_end: datetime
    activity_label: str = Field(min_length=1, max_length=100)


class CurrentWeatherRequest(BaseModel):
    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)


class GeoCheckRequest(BaseModel):
    current_lat: float
    current_lon: float
    accuracy_m: float
    fix_at: datetime
    destination_lat: float
    destination_lon: float
    destination_source_id: str = Field(min_length=1, max_length=200)
    destination_verified_at: datetime | None = None


class PlaceLookupRequest(BaseModel):
    lat: float | None = Field(default=None, ge=-90, le=90)
    lon: float | None = Field(default=None, ge=-180, le=180)
    name_hint: str = Field(min_length=1, max_length=120)


class DiscoveryRequest(BaseModel):
    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)
    interest: str = Field(min_length=1, max_length=120)


class AgreementRequest(BaseModel):
    transcript: str = Field(min_length=1, max_length=2000)
    turn_owner: Literal["TRAVELLER", "LOCAL"]


class OcrInterpretRequest(BaseModel):
    recognized_text: str = Field(min_length=1, max_length=2000)
    image_sha256: str = Field(min_length=64, max_length=64)
    source_language_code: str = "ml-IN"
    target_language_code: str


class AssistantPlanRequest(BaseModel):
    utterance: str = Field(min_length=1, max_length=1000)
    follow_up_answer: str | None = Field(default=None, max_length=200)
    language_code: Literal["en-IN", "ml-IN", "te-IN", "hi-IN"]


ASSISTANT_INTENT_SCHEMA = {
    "type": "object", "properties": {
        "feature": {"type": "string", "enum": [f"F{i:02d}" for i in range(1, 14)] + ["UNKNOWN"]},
        "destination": {"type": "string"},
        "follow_up_question": {"type": "string"},
        "action": {"type": "string", "enum": ["CAPTURE_SCENE", "OPEN_TALK", "CAPTURE_TEXT",
            "OPEN_BOOKING", "OPEN_WEATHER", "CAPTURE_BUS_BOARD", "SHOW_BUS_MAP",
            "OPEN_CULTURE", "OPEN_MEMORY", "OPEN_ACCESSIBILITY", "OPEN_OFFLINE",
            "OPEN_DISCOVERY", "OPEN_SENSOR_CONTROLS", "OPEN_TASKS"]},
        "requires_camera": {"type": "boolean"},
        "decision_reason": {"type": "string"},
    }, "required": ["feature", "destination", "follow_up_question", "action",
                    "requires_camera", "decision_reason"],
    "additionalProperties": False,
}


@app.post("/v1/assistant/plan")
async def assistant_plan(payload: AssistantPlanRequest, _: None = Depends(require_auth),
                         __: None = Depends(require_cloud_consent)) -> dict[str, Any]:
    """Sarvam plans one task; the phone alone decides whether to activate a sensor."""
    messages = [
        {"role": "system", "content": (
            "You route requests for a travel assistant in Kerala. Choose a feature and its action. "
            "F01 scene question, F02 two-way speech, F03 read text, F04 booking/location, "
            "F05 weather, F06 bus route/board, F07 culture, F08 saved promises, "
            "F09 conflicting trip details, F10 accessibility, F11 offline/emergency, "
            "F12 local craft discovery, F13 sensor controls; UNKNOWN otherwise. "
            "Use the question context: weather needs location, culture and local shops need place search, "
            "conversation needs speech or selected text. Choose camera only when the traveller asks to see, "
            "point at, photograph, scan, or read something visible now. A named temple history or a shop search "
            "does not need a photo. For a bus route question without a pointed board choose SHOW_BUS_MAP. "
            "Extract a destination only when the traveller explicitly says where they want to go. "
            "Never answer route, weather, history, or booking facts. Do not request private data. "
            "Give a brief decision_reason without hidden reasoning. Use an empty string for unknown fields."
        )},
        {"role": "user", "content": f"Language: {payload.language_code}\nRequest: {payload.utterance}\n"
         f"Follow-up answer: {payload.follow_up_answer or ''}"},
    ]
    body = {"model": "sarvam-105b", "messages": messages, "reasoning_effort": "medium",
            "max_tokens": 800, "response_format": {"type": "json_schema",
            "json_schema": {"name": "yatralens_task", "strict": True,
                            "schema": ASSISTANT_INTENT_SCHEMA}}}
    planner_name = "sarvam-105b"
    planner_warning = None
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            response = await client.post(f"{SARVAM}/v1/chat/completions", json=body,
                                         headers={"api-subscription-key": sarvam_key()})
            response.raise_for_status()
            content = response.json()["choices"][0]["message"]["content"]
            suggestion = SuggestedIntent.model_validate_json(content)
    except (httpx.HTTPError, ValueError, KeyError, IndexError, TypeError):
        suggestion = fallback_suggestion(payload.utterance)
        planner_name = "local-rule-fallback"
        planner_warning = "Sarvam planning was unavailable; a local request match was used."
    plan = finalize_plan(payload.utterance, payload.follow_up_answer, suggestion)
    return {**plan, "model": planner_name, "planner_warning": planner_warning,
            "user_request": payload.utterance,
            "requires_action_confirmation": True}


@app.get("/v1/health")
def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "checked_at": now_utc(),
        "sarvam_configured": bool(os.environ.get("SARVAM_API_KEY")),
        "weather_provider": "Open-Meteo forecast",
        "official_warnings": "public IMD Kerala subdivision page; checked on demand for Alappuzha pilot",
        "personal_memory_storage": "Android device",
    }


@app.get("/v1/capabilities")
def capabilities(_: None = Depends(require_auth)) -> dict[str, Any]:
    configured = bool(os.environ.get("SARVAM_API_KEY"))
    return {
        "speech_to_text": {"available": configured, "provider": "Sarvam", "model": "saaras:v4"},
        "translation": {"available": configured, "provider": "Sarvam", "model": "sarvam-translate:v1"},
        "text_to_speech": {"available": configured, "provider": "Sarvam", "malayalam_model": "bulbul:v3"},
        "weather_forecast": {"available": True, "provider": "Open-Meteo"},
        "official_weather_warning": {"available": True, "scope": "Alappuzha pilot using dated Kerala subdivision page; live availability may change"},
        "transport_route": {"available": False, "reason": "No verified pilot route dataset"},
        "assistant_planning": {"available": configured, "provider": "Sarvam", "model": "sarvam-105b"},
    }


@app.post("/v1/context/evaluate")
def context_evaluate(payload: EvaluationRequest, _: None = Depends(require_auth)) -> dict[str, Any]:
    unknown = {claim.source_id for claim in payload.claims} - set(payload.source_refs)
    if unknown:
        raise HTTPException(status_code=422, detail="Every claim source must be present in source_refs")
    claims = [Claim(**item.model_dump()) for item in payload.claims]
    decision = evaluate_claims(claims)
    return {"decision": decision.kind, "may_assert": decision.may_assert,
            "evidence_ids": decision.evidence_ids, "reason": decision.reason, "evaluated_at": now_utc()}


@app.post("/v1/memory/propose-agreement")
def memory_propose_agreement(payload: AgreementRequest, _: None = Depends(require_auth),
                             __: None = Depends(require_cloud_consent)) -> dict[str, Any]:
    return propose_agreements(payload.transcript, payload.turn_owner)


@app.post("/v1/ocr/interpret")
async def ocr_interpret(payload: OcrInterpretRequest, _: None = Depends(require_auth),
                        __: None = Depends(require_cloud_consent)) -> dict[str, Any]:
    if not all(ch in "0123456789abcdefABCDEF" for ch in payload.image_sha256):
        raise HTTPException(status_code=422, detail="A valid image SHA-256 is required")
    translation = await translate(TranslationRequest(
        text=payload.recognized_text,
        source_language_code=payload.source_language_code,
        target_language_code=payload.target_language_code), None, None)
    return {"original_text": payload.recognized_text,
            "translated_text": translation["translated_text"],
            "image_sha256": payload.image_sha256,
            "translation_provider": translation["provider"],
            "review_flags": translation["review_flags"],
            "cues": scan_cues(payload.recognized_text),
            "status": "REVIEW_REQUIRED", "interpreted_at": now_utc()}


@app.post("/v1/transport/check")
def transport_check(payload: RouteRequest, _: None = Depends(require_auth),
                    __: None = Depends(require_cloud_consent)) -> dict[str, Any]:
    route_result = assess_route(payload.board_text, payload.destination,
                                [item.model_dump() for item in payload.route_records])
    if route_result["status"] == "UNKNOWN":
        try:
            route_result = lookup_private_bus(payload.board_text, payload.destination) or route_result
        except (OSError, ValueError):
            pass
    if route_result["status"] == "UNKNOWN":
        route_result = read_board_place(payload.board_text, payload.destination) or route_result
    origin = ("caller supplied route record" if payload.route_records else
              "community RTI private-bus schedule; current operation unverified" if
              route_result["status"] == "PRIVATE_BUS_CANDIDATES" else
              "indicative major-place corridor; service unverified" if
              route_result["status"] == "POSSIBLE_CORRIDOR" else
              "KSRTC depot-name list; no service timetable")
    return {**route_result,
            "board_text": payload.board_text, "destination": payload.destination,
            "map_pin": approximate_place_pin(payload.destination),
            "route_data_origin": origin,
            "private_bus_reference_app": WEB_APP_URL,
            "official_depot_enquiry": "https://keralartc.com/contact/depots-enquiry"}


@app.post("/v1/transport/map")
async def transport_map(payload: TransportMapRequest, _: None = Depends(require_auth),
                        __: None = Depends(require_cloud_consent)) -> dict[str, Any]:
    answer = place_map_answer(payload.destination, payload.request_text)
    if approximate_place_pin(payload.destination) is None:
        answer = {"status": "UNKNOWN", "map_pin": None,
                  "reason": "The requested place has no confirmed pin yet; ask for a known town or retry live place search."}
        origin = next((name for name in headsign_places(payload.request_text)
                       if name in PLACE_CENTRES), "Alappuzha")
        try:
            places = await search_places(payload.destination + " Kerala", *PLACE_CENTRES[origin], count=1)
        except (httpx.HTTPError, ValueError):
            places = []
        if places:
            place = places[0]
            answer.update({"status": "PIN_AVAILABLE", "map_pin": {
                "label": place["name"], "latitude": place["latitude"],
                "longitude": place["longitude"],
                "precision": "Google Maps place pin; not a confirmed bus stop"},
                "source_url": place["source_url"], "source_kind": "Google Maps Places"})
            nearby = indicative_nearby_place(payload.request_text, place["name"])
            if nearby:
                answer.update(nearby)
            else:
                answer["reason"] = ("Found a place pin for " + place["name"] +
                    ". I cannot link it to this bus without a confirmed route or stop list. Ask the conductor.")
    try:
        private = lookup_private_bus(payload.request_text, payload.destination, map_query=True)
    except (OSError, ValueError):
        private = None
    if private is not None:
        answer.update({"status": private["status"], "reason": private["reason"],
                       "candidates": private["candidates"],
                       "source_url": private["source_url"],
                       "source_kind": private["source_kind"],
                       "private_bus_reference_app": WEB_APP_URL})
    return answer


@app.post("/v1/culture/lookup")
async def culture_lookup(payload: PlaceLookupRequest, _: None = Depends(require_auth),
                         __: None = Depends(require_cloud_consent)) -> dict[str, Any]:
    if (payload.lat is None) != (payload.lon is None):
        raise HTTPException(status_code=422, detail="Latitude and longitude must be supplied together")
    query = re.sub(r"^(?:please\s+)?(?:tell me about|tell me the history of|what is the history of|"
                   r"what do you know about|history of|describe|show me)\s+", "",
                   payload.name_hint.strip(), flags=re.IGNORECASE).strip(" ?.")
    query = query or payload.name_hint.strip()
    generic = bool(re.fullmatch(
        r"(?:this|that|nearby|pointed|the)?\s*(?:temple|shrine|church|mosque|landmark)"
        r"(?:\s+(?:near me|in front of me))?", query, flags=re.IGNORECASE))
    has_location = payload.lat is not None and payload.lon is not None
    if generic and not has_location:
        return {"status": "UNKNOWN", "reason":
                "I need a place name or a fresh location to find a nearby cultural site."}
    curated = (cultural_lookup(payload.lat, payload.lon, query) if has_location else
               {"status": "UNKNOWN", "reason": "No local curated match without a location"})
    if curated["status"] != "UNKNOWN":
        result = curated
    else:
        try:
            places = await search_places("temple near me" if generic else query,
                                         payload.lat, payload.lon, count=5 if generic else 3,
                                         radius_m=5_000 if generic else 15_000)
        except (httpx.HTTPError, ValueError):
            places = []
        if not places:
            page = None
            if not generic:
                try:
                    page = await cultural_page(query)
                except (httpx.HTTPError, requests.RequestException, ValueError):
                    pass
            if page is None:
                return {"status": "UNKNOWN", "reason":
                        "No sourced cultural page or mapped place matched this name. Try the sign's full name."}
            return {"status": "CANDIDATE_REQUIRES_CONFIRMATION", "name": page["title"],
                    "facts": [], "source_url": page["source_url"], "source_owner": "Wikipedia",
                    "identity_basis": "user-provided name and web page; physical site unverified",
                    "visitor_rules": None, "opening_status": "UNKNOWN", "web_summary": page["summary"],
                    "web_source_url": page["source_url"], "image_url": page["image_url"],
                    "image_source_url": page["image_source_url"],
                    "reason": "Confirm this is the site you mean before relying on its history."}
        place = min(places, key=lambda item: item["distance_km"] if item.get("distance_km") is not None
                    else float("inf")) if generic else places[0]
        result = {"status": "CANDIDATE_REQUIRES_CONFIRMATION", "place_id": place["place_id"],
                  "name": place["name"], "area": place["area"], "facts": [],
                  "source_url": place["source_url"], "source_owner": place["source_owner"],
                  "identity_basis": "nearby Google Maps candidate; visual identity unverified" if generic else
                                    "user name hint and Google Maps place",
                  "map_pin": {"label": place["name"], "latitude": place["latitude"],
                              "longitude": place["longitude"], "precision": "Google Maps place pin"},
                  "visitor_rules": None, "opening_status": "UNKNOWN",
                  "reason": "Confirm the site name. Visitor rules and hours are not verified."}
        if place.get("photo_name"):
            try:
                encoded = await place_photo(place["photo_name"])
                if encoded:
                    result["place_photo_base64"] = encoded
                    result["place_photo_source_url"] = place["source_url"]
                    result["place_photo_attribution"] = ", ".join(
                        entry.get("displayName", "") for entry in place.get("photo_attributions", [])
                        if entry.get("displayName"))[:160]
            except (httpx.HTTPError, ValueError):
                pass
    try:
        page = await cultural_page(result["name"])
    except (httpx.HTTPError, requests.RequestException, ValueError):
        page = None
    if page:
        result = {**result, "web_summary": page["summary"],
                  "web_source_url": page["source_url"], "image_url": page["image_url"],
                  "image_source_url": page["image_source_url"]}
    return result


@app.post("/v1/discovery/search")
async def discovery_search(payload: DiscoveryRequest, _: None = Depends(require_auth),
                           __: None = Depends(require_cloud_consent)) -> dict[str, Any]:
    curated = discover(payload.lat, payload.lon, payload.interest)
    try:
        places = await search_places(payload.interest + " shops or places nearby",
                                     payload.lat, payload.lon, count=5)
    except (httpx.HTTPError, ValueError):
        places = []
    live = [{"place_id": item["place_id"], "name": item["name"], "area": item["area"],
             "category": item["primary_type"] or "local place",
             "description": "Nearby Google Maps place candidate; confirm products and access.",
             "source_url": item["source_url"], "source_owner": item["source_owner"],
             "opening_status": "UNKNOWN", "contact": None, "seller_verified": False,
             "map_pin": {"label": item["name"], "latitude": item["latitude"],
                         "longitude": item["longitude"], "precision": "Google Maps place pin"}}
            for item in places]
    results = live + curated["results"]
    return {"status": "CANDIDATES" if results else "UNKNOWN", "results": results,
            "reason": "Confirm access and products before visiting" if results else
                      "No nearby place candidate matched this interest"}


@app.post("/v1/geo/check")
def geo_check(payload: GeoCheckRequest, _: None = Depends(require_auth),
              __: None = Depends(require_cloud_consent)) -> dict[str, Any]:
    try:
        decision = assess_saved_destination(
            payload.current_lat, payload.current_lon, payload.accuracy_m, payload.fix_at,
            payload.destination_lat, payload.destination_lon, payload.destination_source_id,
            payload.destination_verified_at)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    return {"status": decision.status, "distance_m": decision.distance_m,
            "bearing_degrees": decision.bearing_degrees, "reason": decision.reason,
            "destination_source_id": decision.source_id, "checked_at": now_utc(),
            "navigation_route": None}


@app.get("/v1/weather/forecast")
async def weather_forecast(lat: float, lon: float, _: None = Depends(require_auth),
                           __: None = Depends(require_cloud_consent)) -> dict[str, Any]:
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        raise HTTPException(status_code=422, detail="Invalid coordinates")
    params = {
        "latitude": lat, "longitude": lon,
        "hourly": "precipitation_probability,rain,wind_speed_10m,wind_gusts_10m,temperature_2m",
        "current": "temperature_2m,relative_humidity_2m,precipitation,weather_code,wind_speed_10m,wind_gusts_10m",
        "forecast_days": 2, "timezone": "UTC",
    }
    try:
        async with httpx.AsyncClient(timeout=15) as client:
            response = await client.get("https://api.open-meteo.com/v1/forecast", params=params)
            response.raise_for_status()
            forecast = response.json()
    except (httpx.HTTPError, ValueError) as exc:
        raise upstream_error(exc) from exc
    return {"provider": "Open-Meteo", "kind": "forecast_not_official_warning",
            "retrieved_at": now_utc(), "source_url": str(response.url),
            "latitude": forecast.get("latitude"), "longitude": forecast.get("longitude"),
            "hourly_units": forecast.get("hourly_units"), "hourly": forecast.get("hourly", {}),
            "current_units": forecast.get("current_units"), "current": forecast.get("current", {})}


@app.post("/v1/weather/current")
async def weather_current(payload: CurrentWeatherRequest, _: None = Depends(require_auth),
                          __: None = Depends(require_cloud_consent)) -> dict[str, Any]:
    forecast = await weather_forecast(payload.lat, payload.lon, None, None)
    return summarize_current(forecast["current"], forecast["source_url"],
                             datetime.fromisoformat(forecast["retrieved_at"]))


async def read_public_imd_warning() -> dict[str, Any]:
    try:
        async with httpx.AsyncClient(timeout=12) as client:
            response = await client.get(IMD_KERALA_URL)
            response.raise_for_status()
            if len(response.content) > 500_000:
                raise ValueError("IMD page exceeded the parser size limit")
        return parse_kerala_warning(response.text, datetime.now(timezone.utc))
    except (httpx.HTTPError, ValueError) as exc:
        return {"status": "unavailable", "reason": f"IMD page could not be checked: {type(exc).__name__}"}


@app.get("/v1/weather/warnings")
async def weather_warnings(_: None = Depends(require_auth)) -> dict[str, Any]:
    return {**await read_public_imd_warning(), "source_url": IMD_KERALA_URL,
            "scope_note": "Kerala subdivision advisory; not a district closure or a boat cancellation"}


@app.post("/v1/weather/assess")
async def weather_assess(payload: WeatherAssessmentRequest, _: None = Depends(require_auth),
                         __: None = Depends(require_cloud_consent)) -> dict[str, Any]:
    if payload.event_end <= payload.event_start:
        raise HTTPException(status_code=422, detail="Activity end must follow start")
    forecast = await weather_forecast(payload.lat, payload.lon, None, None)
    decision = assess_forecast(payload.event_start, payload.event_end, forecast["hourly"])
    parsed = await read_public_imd_warning() if in_alappuzha_pilot(payload.lat, payload.lon) else {}
    decision = combine_with_forecast(decision, parsed, payload.lat, payload.lon,
                                     payload.event_start, payload.event_end)
    return {**decision, "activity_label": payload.activity_label,
            "forecast_source_url": forecast["source_url"],
            "forecast_retrieved_at": forecast["retrieved_at"],
            "source_kind": "forecast_with_kerala_subdivision_check" if parsed else
                           "forecast_not_official_warning"}


@app.post("/v1/text/translate")
async def translate(payload: TranslationRequest, _: None = Depends(require_auth),
                    __: None = Depends(require_cloud_consent)) -> dict[str, Any]:
    key = sarvam_key()
    # Mayura can treat romanized Telugu questions as Malayalam statements. For
    # Tengglish turns, ask the reasoning model to preserve the speaker's intent.
    tengglish = (payload.source_language_code == "te-IN" and
                 payload.target_language_code == "ml-IN" and
                 bool(re.search(r"[A-Za-z]", payload.text)) and
                 not bool(re.search(r"[\u0c00-\u0c7f]", payload.text)))
    reverse_tengglish = (payload.source_language_code == "ml-IN" and
                         payload.target_language_code == "te-IN" and
                         payload.output_script == "roman")
    if tengglish or reverse_tengglish:
        translation_model = "sarvam-105b"
        fallback_flags: list[str] = []
        direction = ("Translate Malayalam travel speech into romanized Telugu-English "
                     "(Tengglish). Preserve exact meaning, uncertainty, numbers, times and names. "
                     "Keep numerals exactly as given. Return only the translation."
                     if reverse_tengglish else
                     "Translate romanized Telugu-English travel speech to Malayalam. Preserve "
                     "intent, question form, names, amounts and times. Infer obvious speech "
                     "recognition spelling variants only.")
        body = {"model": "sarvam-105b", "reasoning_effort": "low", "max_tokens": 1200,
                "response_format": {"type": "json_schema", "json_schema": {
                    "name": "translation", "strict": True,
                    "schema": {"type": "object", "properties": {"translation": {"type": "string"}},
                               "required": ["translation"], "additionalProperties": False}}},
                "messages": [
                    {"role": "system", "content": direction},
                    {"role": "user", "content": payload.text},
                ]}
        try:
            async with httpx.AsyncClient(timeout=30) as client:
                response = await client.post(f"{SARVAM}/v1/chat/completions", json=body,
                                             headers={"api-subscription-key": key})
                response.raise_for_status()
                content = response.json()["choices"][0]["message"]["content"]
                translated = json.loads(content)["translation"]
                if not isinstance(translated, str) or not translated.strip():
                    raise ValueError("Empty Tengglish translation")
        except (httpx.HTTPError, ValueError, KeyError, IndexError, TypeError):
            # The reasoning endpoint sometimes returns incomplete JSON. Keep the
            # reviewed turn usable through Sarvam's translation endpoint.
            fallback_body = {"input": payload.text,
                             "source_language_code": payload.source_language_code,
                             "target_language_code": payload.target_language_code,
                             "model": "mayura:v1"}
            if reverse_tengglish:
                fallback_body["output_script"] = "roman"
            try:
                async with httpx.AsyncClient(timeout=30) as client:
                    fallback = await client.post(f"{SARVAM}/translate", json=fallback_body,
                                                 headers={"api-subscription-key": key})
                    fallback.raise_for_status()
                    translated = fallback.json()["translated_text"]
                    if not isinstance(translated, str) or not translated.strip():
                        raise ValueError("Empty fallback translation")
            except (httpx.HTTPError, ValueError, KeyError, TypeError) as exc:
                raise upstream_error(exc) from exc
            translation_model = "mayura:v1"
            fallback_flags.append("TENGGLISH_TRANSLATION_REVIEW_BEFORE_SPEAKING")
        format_flags: list[str] = []
        if tengglish:
            translated = re.sub(r"^(?:മലയാളം|Malayalam)\s*[:：]\s*", "", translated,
                                flags=re.IGNORECASE).strip()
            malayalam_chars = len(re.findall(r"[\u0d00-\u0d7f]", translated))
            if malayalam_chars < max(4, len(translated) // 4):
                try:
                    async with httpx.AsyncClient(timeout=30) as client:
                        rewritten = await client.post(f"{SARVAM}/v1/chat/completions", json={
                            **body, "messages": [
                                {"role": "system", "content": (
                                    "Write the supplied translation entirely in Malayalam script. "
                                    "Keep its meaning, question form, names, amounts and times. "
                                    "Return a JSON translation field only.")},
                                {"role": "user", "content": translated},
                            ]}, headers={"api-subscription-key": key})
                        rewritten.raise_for_status()
                        translated = json.loads(rewritten.json()["choices"][0]["message"]["content"])[
                            "translation"].strip()
                except (httpx.HTTPError, ValueError, KeyError, IndexError, TypeError):
                    pass
                if len(re.findall(r"[\u0d00-\u0d7f]", translated)) < max(4, len(translated) // 4):
                    format_flags.append("MALAYALAM_SCRIPT_REVIEW_BEFORE_SPEAKING")
        if reverse_tengglish and len(re.findall(
                r"\b(if|there|is|the|will|be|to|at|for|same|people|travel|confirm|return|ticket|rate)\b",
                translated, re.IGNORECASE)) >= 2:
            try:
                async with httpx.AsyncClient(timeout=30) as client:
                    roman = await client.post(f"{SARVAM}/translate", json={
                        "input": translated, "source_language_code": "en-IN",
                        "target_language_code": "te-IN", "model": "mayura:v1",
                        "output_script": "roman"}, headers={"api-subscription-key": key})
                    roman.raise_for_status()
                    translated = roman.json()["translated_text"].strip()
                    if not translated:
                        raise ValueError("Empty Tengglish output")
            except (httpx.HTTPError, ValueError, KeyError, TypeError):
                format_flags.append("TENGGLISH_FORMAT_REVIEW_BEFORE_SPEAKING")
        return {"original": payload.text, "translated_text": translated.strip(),
                "source_language_code": payload.source_language_code,
                "target_language_code": payload.target_language_code, "provider": "Sarvam",
                "model": translation_model, "output_script": payload.output_script,
                "translated_at": now_utc(),
                "review_flags": translation_review_flags(payload.text, translated) +
                                format_flags + fallback_flags}
    model = "mayura:v1" if payload.output_script else "sarvam-translate:v1"
    body = {"input": payload.text, "source_language_code": payload.source_language_code,
            "target_language_code": payload.target_language_code, "model": model}
    if payload.output_script:
        body["output_script"] = payload.output_script
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            response = await client.post(f"{SARVAM}/translate", json=body, headers={"api-subscription-key": key})
            response.raise_for_status()
            data = response.json()
    except (httpx.HTTPError, ValueError) as exc:
        raise upstream_error(exc) from exc
    return {"original": payload.text, "translated_text": data.get("translated_text", ""),
            "source_language_code": data.get("source_language_code", payload.source_language_code),
            "target_language_code": payload.target_language_code, "provider": "Sarvam",
            "model": model, "output_script": payload.output_script, "translated_at": now_utc(),
            "review_flags": translation_review_flags(payload.text, data.get("translated_text", ""))}


@app.post("/v1/speech/transcribe")
async def transcribe(file: Annotated[UploadFile, File()],
                     mode: Annotated[Literal["transcribe", "translate", "verbatim", "translit", "codemix"], Form()] = "transcribe",
                     _: None = Depends(require_auth), __: None = Depends(require_cloud_consent)) -> dict[str, Any]:
    key = sarvam_key()
    audio = await file.read(6_000_001)
    if len(audio) > 6_000_000 or not audio:
        raise HTTPException(status_code=413, detail="Audio must be a nonempty clip under 6 MB")
    if file.content_type not in {"audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp4", "audio/ogg", "audio/aac"}:
        raise HTTPException(status_code=415, detail="Unsupported audio type")
    try:
        async with httpx.AsyncClient(timeout=45) as client:
            response = await client.post(f"{SARVAM}/speech-to-text",
                headers={"api-subscription-key": key}, data={"model": "saaras:v4", "mode": mode},
                files={"file": (file.filename or "recording.wav", audio, file.content_type)})
            response.raise_for_status()
            data = response.json()
    except (httpx.HTTPError, ValueError) as exc:
        raise upstream_error(exc) from exc
    transcript = data.get("transcript") or ""
    return {"status": "OK" if transcript.strip() else "NO_SPEECH",
            "transcript": transcript, "language_code": data.get("language_code"),
            "provider": "Sarvam", "model": "saaras:v4", "mode": mode,
            "transcribed_at": now_utc(), "request_id": data.get("request_id")}


@app.post("/v1/speech/synthesize")
async def synthesize(payload: SynthesisRequest, _: None = Depends(require_auth),
                     __: None = Depends(require_cloud_consent)) -> dict[str, Any]:
    key = sarvam_key()
    # Malayalam has a dedicated v3 language path; v4 Flash has no Malayalam persona.
    model = "bulbul:v3" if payload.language_code == "ml-IN" else "bulbul:v4-flash"
    speaker = payload.speaker or ("shubh" if model == "bulbul:v3" else "simran_en_customer")
    body = {"text": payload.text, "language_code": payload.language_code,
            "model": model, "speaker": speaker}
    try:
        async with httpx.AsyncClient(timeout=45) as client:
            response = await client.post(f"{SARVAM}/text-to-speech", json=body,
                headers={"api-subscription-key": key})
            response.raise_for_status()
            data = response.json()
    except (httpx.HTTPError, ValueError) as exc:
        raise upstream_error(exc) from exc
    audios = data.get("audios") or []
    if not audios:
        raise HTTPException(status_code=503, detail="Provider returned no audio")
    return {"audio_base64": audios[0], "mime_type": "audio/wav", "model": model,
            "speaker": speaker,
            "provider": "Sarvam", "generated_at": now_utc()}


@app.post("/v1/ocr/indic")
async def indic_ocr(file: Annotated[UploadFile, File()],
                    language: Annotated[str, Form()] = "ml-IN",
                    purpose: Annotated[str, Form()] = "selected_text_crop",
                    _: None = Depends(require_auth),
                    __: None = Depends(require_cloud_consent)) -> dict[str, Any]:
    """Parse a user-selected document-like text crop; street-scene quality is unverified."""
    if purpose != "selected_text_crop":
        raise HTTPException(status_code=422, detail="Only a selected text crop is accepted")
    key = os.environ.get("SARVAM_API_KEY", "").strip()
    image = await file.read(8_000_001)
    if not image or len(image) > 8_000_000:
        raise HTTPException(status_code=413, detail="Image must be nonempty and under 8 MB")
    if image.startswith(b"\xff\xd8\xff"):
        mime, extension = "image/jpeg", "jpg"
    elif image.startswith(b"\x89PNG\r\n\x1a\n"):
        mime, extension = "image/png", "png"
    else:
        raise HTTPException(status_code=415, detail="Only actual JPEG or PNG bytes are accepted")
    async def fallback_ocr() -> dict[str, Any]:
        result = await analyze_selected_image(image, mime, "text")
        lines = [line.strip() for line in result["visible_text"] if line.strip()]
        return {"status": "COMPLETED" if lines else "NO_TEXT",
                "provider": result["provider"], "model": result["model"],
                "language": language, "job_id": None,
                "image_sha256": hashlib.sha256(image).hexdigest(),
                "processed_at": now_utc(), "scene_text_quality": "OpenAI vision; review against photo",
                "text": "\n".join(lines), "spans": [{"text": line} for line in lines]}
    try:
        primary = await fallback_ocr()
        if primary["text"].strip():
            return primary
    except (httpx.HTTPError, ValueError, RuntimeError):
        pass
    if not key:
        raise HTTPException(status_code=503, detail="Visual OCR providers are unavailable")
    headers = {"api-subscription-key": key}
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            created = await client.post(f"{SARVAM}/doc-ai/v1/job/parse", headers=headers,
                data={"language": language, "content_type": "printed", "model": "sarvam-vision-v1"},
                files={"file": (f"selected-crop.{extension}", image, mime)})
            created.raise_for_status()
            job_id = created.json()["job_id"]
            status = "pending"
            for _ in range(45):
                status_response = await client.get(f"{SARVAM}/doc-ai/v1/job/{job_id}/status", headers=headers)
                status_response.raise_for_status()
                status = status_response.json().get("status", "unknown")
                if status in {"completed", "partially_completed", "failed", "rejected"}:
                    break
                await asyncio.sleep(1)
            if status not in {"completed", "partially_completed"}:
                return {"status": "UNAVAILABLE", "provider_status": status, "job_id": job_id,
                        "image_sha256": hashlib.sha256(image).hexdigest(),
                        "reason": "Document OCR did not complete; retake or enter text manually"}
            result = await client.get(f"{SARVAM}/doc-ai/v1/job/{job_id}/results",
                                      params={"format": "json"}, headers=headers)
            result.raise_for_status()
            parsed = result.json()
    except (httpx.HTTPError, ValueError, KeyError) as exc:
        try:
            return await fallback_ocr()
        except (httpx.HTTPError, ValueError, RuntimeError):
            raise upstream_error(exc) from exc
    documents = parsed.get("documents", [])
    spans = collect_ocr_spans(documents)
    if not spans or len("".join(span["text"] for span in spans).strip()) < 5:
        try:
            return await fallback_ocr()
        except (httpx.HTTPError, ValueError, RuntimeError):
            pass
    return {"status": "COMPLETED" if spans else "NO_TEXT", "provider": "Sarvam Document AI",
            "model": "sarvam-vision-v1", "language": language,
            "job_id": job_id, "image_sha256": hashlib.sha256(image).hexdigest(),
            "processed_at": now_utc(), "scene_text_quality": "not validated for street signs",
            "text": "\n".join(span["text"] for span in spans), "spans": spans,
            "documents": documents, "usage": parsed.get("usage", {}),
            "provider_status": parsed.get("status")}


@app.post("/v1/vision/analyze")
async def vision_analyze(file: Annotated[UploadFile, File()],
                         task: Annotated[str, Form()],
                         question: Annotated[str, Form()] = "",
                         _: None = Depends(require_auth),
                         __: None = Depends(require_cloud_consent)) -> dict[str, Any]:
    if task not in VISION_TASKS or len(question) > 500:
        raise HTTPException(status_code=422, detail="Unknown selected visual task")
    image = await file.read(8_000_001)
    if not image or len(image) > 8_000_000:
        raise HTTPException(status_code=413, detail="Selected frame must be under 8 MB")
    mime = "image/jpeg" if image.startswith(b"\xff\xd8\xff") else (
        "image/png" if image.startswith(b"\x89PNG\r\n\x1a\n") else "")
    if not mime:
        raise HTTPException(status_code=415, detail="Only JPEG or PNG frames are accepted")
    try:
        return await analyze_selected_image(image, mime, task, question)
    except (httpx.HTTPError, ValueError, RuntimeError) as exc:
        raise upstream_error(exc) from exc

