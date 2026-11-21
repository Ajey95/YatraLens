"""Consent-gated cloud adapters and source-aware trip decisions.

The Android device owns personal trip memory. This service does not retain raw
uploads, transcripts, images, or exact location in a server database.
"""

from __future__ import annotations

import os
import asyncio
import hashlib
from datetime import datetime, timezone
from pathlib import Path
from typing import Annotated, Any, Literal

import httpx
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from pydantic import BaseModel, Field

from backend.app.policy import Claim, evaluate_claims, translation_review_flags
from backend.app.geo_policy import assess_saved_destination
from backend.app.weather_policy import assess_forecast
from backend.app.curated import cultural_lookup, discover
from backend.app.transport_policy import assess_route
from backend.app.agreement_policy import propose_agreements
from backend.app.scan_policy import scan_cues


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
    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)
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


@app.get("/v1/health")
def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "checked_at": now_utc(),
        "sarvam_configured": bool(os.environ.get("SARVAM_API_KEY")),
        "weather_provider": "Open-Meteo forecast",
        "official_warnings": "unavailable until a permitted feed is verified",
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
        "official_weather_warning": {"available": False, "reason": "No verified structured IMD feed"},
        "transport_route": {"available": False, "reason": "No verified pilot route dataset"},
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
    return {**assess_route(payload.board_text, payload.destination,
                           [item.model_dump() for item in payload.route_records]),
            "board_text": payload.board_text, "destination": payload.destination,
            "route_data_origin": "caller supplied; source must be checked by the traveller",
            "official_depot_enquiry": "https://keralartc.com/contact/depots-enquiry"}


@app.post("/v1/culture/lookup")
def culture_lookup(payload: PlaceLookupRequest, _: None = Depends(require_auth),
                   __: None = Depends(require_cloud_consent)) -> dict[str, Any]:
    return cultural_lookup(payload.lat, payload.lon, payload.name_hint)


@app.post("/v1/discovery/search")
def discovery_search(payload: DiscoveryRequest, _: None = Depends(require_auth),
                     __: None = Depends(require_cloud_consent)) -> dict[str, Any]:
    return discover(payload.lat, payload.lon, payload.interest)


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
            "hourly_units": forecast.get("hourly_units"), "hourly": forecast.get("hourly", {})}


@app.get("/v1/weather/warnings")
def weather_warnings(_: None = Depends(require_auth)) -> dict[str, Any]:
    return {"status": "unavailable", "official_page": "https://mausam.imd.gov.in/",
            "reason": "A structured, licensed warning source has not been verified"}


@app.post("/v1/weather/assess")
async def weather_assess(payload: WeatherAssessmentRequest, _: None = Depends(require_auth),
                         __: None = Depends(require_cloud_consent)) -> dict[str, Any]:
    if payload.event_end <= payload.event_start:
        raise HTTPException(status_code=422, detail="Activity end must follow start")
    forecast = await weather_forecast(payload.lat, payload.lon, None, None)
    decision = assess_forecast(payload.event_start, payload.event_end, forecast["hourly"])
    return {**decision, "activity_label": payload.activity_label,
            "forecast_source_url": forecast["source_url"],
            "forecast_retrieved_at": forecast["retrieved_at"],
            "source_kind": "forecast_not_official_warning"}


@app.post("/v1/text/translate")
async def translate(payload: TranslationRequest, _: None = Depends(require_auth),
                    __: None = Depends(require_cloud_consent)) -> dict[str, Any]:
    key = sarvam_key()
    body = {"input": payload.text, "source_language_code": payload.source_language_code,
            "target_language_code": payload.target_language_code, "model": "sarvam-translate:v1"}
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
            "model": "sarvam-translate:v1", "translated_at": now_utc(),
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
    key = sarvam_key()
    image = await file.read(8_000_001)
    if not image or len(image) > 8_000_000:
        raise HTTPException(status_code=413, detail="Image must be nonempty and under 8 MB")
    if image.startswith(b"\xff\xd8\xff"):
        mime, extension = "image/jpeg", "jpg"
    elif image.startswith(b"\x89PNG\r\n\x1a\n"):
        mime, extension = "image/png", "png"
    else:
        raise HTTPException(status_code=415, detail="Only actual JPEG or PNG bytes are accepted")
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
        raise upstream_error(exc) from exc
    documents = parsed.get("documents", [])
    spans = collect_ocr_spans(documents)
    return {"status": "COMPLETED" if spans else "NO_TEXT", "provider": "Sarvam Document AI",
            "model": "sarvam-vision-v1", "language": language,
            "job_id": job_id, "image_sha256": hashlib.sha256(image).hexdigest(),
            "processed_at": now_utc(), "scene_text_quality": "not validated for street signs",
            "text": "\n".join(span["text"] for span in spans), "spans": spans,
            "documents": documents, "usage": parsed.get("usage", {}),
            "provider_status": parsed.get("status")}

