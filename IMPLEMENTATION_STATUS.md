# YatraLens implementation status

This file reports implemented behavior, not PRD acceptance. The product brief is `YATRALENS_MASTER_PRD.md`. The build is a local development prototype.

## Verified on the physical Android phone

- User-started foreground trip with camera, microphone, and location permissions and a persistent notification.
- CameraX delivered real frames; AudioRecord delivered real microphone packets; GPS/network location, heading, accelerometer, gyroscope, battery/charging, thermal, connectivity, wall clock, and monotonic clock delivered live values.
- Camera pause and microphone mute stopped their packet counts; resume restarted the pipelines. Camera and microphone continued during a home/background transition. The screen-lock and 30-minute endurance gates remain open.
- A user-selected spoken turn reached the local backend and Sarvam Saaras v4. The observed transcript was `Where is my boat jetty?`; the spoken target was `Where is the boat jetty?`. This is one successful end-to-end run, not an accuracy evaluation.
- Four trip memory tests passed on the connected phone, covering conflict resolution, unsourced claim rejection, separate validity windows, and reviewed speech/OCR evidence provenance.
- The rebuilt app reported live microphone/GPS/motion/system input. Mute held audio packets at 401, and separate microphone resume advanced them to 419. Its persistent notification read `Camera THROTTLED • Mic ON • Location ON` while Android reported thermal status 3 (severe).
- After cooldown to status 2, CameraX accepted live frames and sampled scene luminance. A user-approved center crop reached `/v1/ocr/indic`; the backend returned HTTP 200 and the app displayed a completed Document AI result with Malayalam lines, English text, and digits. This verifies the camera-to-provider round trip and some Malayalam recognition, but no line-by-line accuracy comparison with the target has been done. The phone returned to severe status after continued sensing, and ending the trip stopped both camera and mic.
- Offline trip-memory readback opened on the phone and showed the correct empty state for the current trip. A real saved evidence item has not yet been read back through that screen.
- The pinned Mirage 4.0.0 model and Android ARM64 runtime loaded on the phone through JNI and returned structured scene and object JSON for a synthetic red image (`MirageNativeTest`, 1 passed). The corrected real camera capture was attempted, but Android thermal status 3 paused CameraX before capture; real scene quality remains unverified.

## Implemented and built; physical phone retest pending

- Camera retry on cooldown, separate camera resume, heading and gyroscope events in the physical context state, and microphone read-failure cleanup. Thermal pause, cooldown recovery, and microphone resume are device verified. The device heats back to severe within a short sensing session, so sustained camera availability remains unverified.
- Explicit visible-app trip recovery can reuse an ACTIVE local trip after an abrupt process death. This path is built and installed but has not been exercised on the phone.
- User-triggered CameraX still capture, center text crop, consent-gated Sarvam Document AI OCR, and optional review/save of OCR text with provider and image fingerprint. The photo is held in memory for the request and is not saved locally by default.
- Review/save of spoken transcript, persistent source-linked trip evidence, offline evidence readback, and validity-window-aware conflict detection.
- A dim selected capture now turns on the torch only when recent luminance is below 45/255 and a flash unit exists, waits 500 ms, captures, then turns the torch off. This builds; a dim-room phone check is pending.
- Local traveller-entered destination storage, a schema migration, and offline storage of reviewed phrases are built. These are not yet exercised through the phone screen.
- The latest APK is installed. Offline readback with saved real evidence remains untested on the phone. The separate rapid-capture queue is built, but that exact queued timing was not independently asserted.

## Backend checks

- FastAPI exposes consent-gated Sarvam speech recognition, translation, speech synthesis, and selected-crop OCR; Open-Meteo forecast and activity overlap assessment; source-aware claim evaluation; and honest `UNKNOWN` transport/official-warning fallbacks.
- A consent-gated geospatial check compares a fresh, accurate GPS fix with a saved destination and returns distance, bearing and a possible mismatch without claiming a route or exact entrance. Nearby-jetty fixtures and stale-fix behavior pass backend tests; no user booking coordinates have been entered on the phone.
- A separate phone location consent gates an on-demand forecast check for an entered activity/start offset/duration. The app records source URL and retrieval time in memory. A live consented phone run succeeded as described above.
- Consent-gated cultural and local discovery pilot endpoints return source-linked candidates for a named Ambalappuzha temple and the International Coir Museum, leaving unverified hours, rules and exact map pins unknown. Transport compares a bus board only against explicitly supplied dated route records and otherwise returns `UNKNOWN`; no verified live route dataset is installed.
- OCR interpretation returns Sarvam translation and exact candidate number/date/time spans. Agreement extraction returns tentative price, duration and return-time spans from a transcript. Both require review and have not yet completed a phone flow.
- Sarvam speech synthesis to transcription, a live translation call, live forecast, and a synthetic Malayalam Document AI fixture completed through the provider. The synthetic OCR image is a contract test, not real sign accuracy evidence.
- The Telugu traveller → Malayalam operator and Malayalam operator → Telugu traveller text examples each translated and synthesized through live Sarvam endpoints. In the return-time example, the numeric clock text changed from `16:00` to an evening `4:00` phrasing; the API now flags changed numeric/time tokens for review.
- A real English traveller turn reached Sarvam STT, showed Malayalam translation in the app, then produced audible Malayalam Bulbul speech only after the traveller tapped Speak. The microphone paused for playback and resumed afterward.
- For the reverse turn, a clearly labeled synthetic Malayalam operator reply was played through the laptop speaker and recorded by the phone's real microphone. Saaras transcribed it as Malayalam/code-mixed speech; the app displayed Telugu text containing ₹1200, a one-hour trip and a 4 p.m. return. This is a phone mic-to-translation integration check, not a human local-person or translation-quality validation.
- The traveller reviewed that Telugu text and tapped Speak; Telugu Bulbul audio was audible on the phone. Playback finished and the microphone resumed. Both turn directions therefore work through the device, with the reverse input labeled synthetic.
- The final conversation-only mode was verified separately on the phone: `Camera OFF • Mic ON • Location ON`, live mic packets and a GPS fix, with no camera frame pipeline opened. End Trip turned the camera and mic off.
- The on-demand boat-ride weather check ran on the physical phone with separate location-sharing consent, a live GPS fix, and a planned start 60 minutes later. The app displayed `NO_ALERT`, maximum rain probability 0%, maximum gust 7.9 km/h, the Open-Meteo source URL, and retrieval time. This means configured forecast thresholds were not met for that interval; it is not an official no-warning or water-safety clearance. The first attempt rejected a transient coarse location update despite a precise GPS fix shortly before it. A follow-up code change now retains a more accurate recent fix; that change built successfully but has not been rerun on the phone.
- `python -m pytest -q backend/tests` currently passes 18 tests. Backend does not retain user media or trip history.

## Not complete against the PRD

- On-device Mirage is integrated and passes a synthetic phone inference test, but a real scene question/answer with human-checked guidance and validated Malayalam sign reading is not complete.
- The two-way conversation demo is working, but it has no fluent-human translation review, local-person identity verification, full conversation history, or long-call mode. The new curated source endpoints still need Android flow verification. No verified route/GTFS dataset, official structured weather warning feed, or production alert delivery is installed.
- No complete offline ASR/vision package, downloadable maps, preferences/accessibility flow, production authentication, HTTPS deployment, lifecycle recovery proof, or 30-minute power/thermal benchmark.
- The Android screen now has a compact trip and conversation view with expandable sign, weather, memory and diagnostics sections. It has not had a TalkBack or accessibility audit.

## Run locally

1. Put `SARVAM_API_KEY` and `YATRALENS_DEV_TOKEN` in the ignored `.env` using `.env.example` as a template.
2. Install `backend/requirements.txt` and run `uvicorn backend.app.main:app --host 127.0.0.1 --port 8000` from the repository root.
3. Run `adb reverse tcp:8000 tcp:8000` with the unlocked phone attached.
4. Build from `android/` with `.\gradlew.bat :app:assembleDebug`; install `android/app/build/outputs/apk/debug/app-debug.apk`.

The debug APK contains only a disposable development token. It contacts `127.0.0.1` through ADB reverse; it is not a deployable production client.
