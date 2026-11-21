<p align="center">
  <img src="docs/assets/yatralens-banner.svg" alt="YatraLens: see, speak, remember and act during a journey" width="100%" />
</p>

<h1 align="center">YatraLens</h1>

<p align="center"><strong>A context-aware travel companion for journeys across India's languages and places.</strong></p>

<p align="center">
  <img src="https://img.shields.io/badge/status-Android%20prototype-2d6a4f" alt="Status: Android prototype" />
  <img src="https://img.shields.io/badge/platform-Android%20ARM64-3ddc84" alt="Platform: Android ARM64" />
  <img src="https://img.shields.io/badge/app-Kotlin-7f52ff" alt="App: Kotlin" />
  <img src="https://img.shields.io/badge/API-FastAPI-009688" alt="API: FastAPI" />
  <img src="https://img.shields.io/badge/pilot-Alappuzha%2C%20Kerala-276c70" alt="Pilot: Alappuzha, Kerala" />
</p>

<p align="center">
  <a href="#the-problem">Problem</a> · <a href="#the-solution">Solution</a> ·
  <a href="#target-users">Users</a> · <a href="#features-and-use-cases">Features</a> ·
  <a href="#architecture">Architecture</a> · <a href="#run-locally">Run locally</a>
</p>

> **Project stage:** Working local development prototype. The [master PRD](YATRALENS_MASTER_PRD.md) defines the complete F01–F13 product; [implementation status](IMPLEMENTATION_STATUS.md) records phone-tested behavior and unfinished acceptance gates. The prototype is not a production travel or safety service.

## The problem

Crossing a state border can make ordinary travel decisions difficult: Which bus is mine? What does a Malayalam board say? How do I ask the conductor or auto driver? Where is my boarding jetty? Did the operator change the time? Camera search, translation and maps help with individual questions, but the traveller still carries the journey's context between them and decides which answer to trust.

The Alappuzha pilot starts with a real traveller's experience of struggling with bus boards, conversations, food and shop names, and locating the final destination. A confident but unsupported route or boarding claim can be worse than an honest request to confirm with a local person.

## The solution

**YatraLens connects physical observations to an active trip.** A user-started Android session brings together permitted camera and microphone input, location and motion, time, weather, saved destinations and source-linked trip memory. It can read a sign, help two people speak across languages, compare new information with earlier evidence, and suggest a useful next question or action.

```text
Observe with permission → interpret → attach source and time → compare with trip context
→ explain uncertainty → help the traveller act → remember what they approve
```

The goal is to answer: **“Given where I am, what I can see or hear, and what I am trying to do, what should I do next?”** The product's intended value is the connected, evidence-aware journey. OCR, translation, maps and voice AI are existing capabilities that YatraLens brings into that workflow.

Its **Physical Context Engine** maintains the trip situation and actual sensor health. “24/7” means an available, recoverable trip session, not uninterrupted camera or microphone capture. Android lifecycle, permissions, heat and battery can interrupt sensing; the app must show the real state.

## Target users

| User | Need |
| --- | --- |
| Regional Indian traveller | Understand signs and conversations in an unfamiliar language while moving through a real trip. |
| Family or group traveller | Keep boarding points, times, quotes and weather-sensitive plans together. |
| Traveller with low vision or reading difficulty | Larger controls, speech output and help reading signs. YatraLens is not a certified mobility aid. |
| Local operator or vendor | Communicate with visitors without depending on English; speech capture and retention require notice and consent. |
| Tourism or community partner | Contribute sourced local information without fabricated listings or hidden ranking. |

## A journey through Alappuzha

1. **Find the bus.** The traveller selects a destination and scans a Malayalam board. YatraLens shows the recognized text and source image. If the available reference cannot confirm the route, it prepares a Malayalam question for the conductor.
2. **Talk to someone nearby.** The traveller asks in Telugu or English; the local person replies in Malayalam. Each side can review translated text before speech playback or saving.
3. **Find the right place.** A saved jetty or accommodation point is compared with a fresh GPS fix, with accuracy shown. The traveller can open a map.
4. **Remember the detail.** A fare or boarding time becomes a proposed claim linked to consented conversation evidence. The traveller reviews it before relying on it.
5. **Handle a change.** If a later statement disagrees with a booking, YatraLens shows both sources and asks for clarification before giving a definitive reminder.
6. **Adapt to conditions.** A forecast overlapping a boat ride can prompt an operator check. A forecast is never presented as an official cancellation.

## Features and use cases

These are the thirteen PRD feature groups. **Prototype** means a connected implementation path exists; it does not mean field reliability or every PRD acceptance test is complete. See [implementation status](IMPLEMENTATION_STATUS.md) for current evidence.

| Feature | Use-case scenario | State |
| --- | --- | --- |
| **F01 · Point & Ask** | Point at a jetty and ask what the camera can identify. | Prototype: CameraX and on-device Mirage scene cues; exact boarding guidance needs field validation. |
| **F02 · Talk & Listen** | Ask a Malayalam boat operator about fare and return time in Telugu. | Prototype: two-way speech/translation tested with a synthetic operator turn; fluent-human review remains. |
| **F03 · Read Anything** | Photograph a Malayalam bus board or notice and hear an explanation. | Prototype: selected crop, Indic OCR, translation and review; street-sign accuracy is unbenchmarked. |
| **F04 · Geospatial Intelligence** | Check whether the current location matches a saved booking jetty. | Prototype: accuracy-aware distance/bearing and map handoff; real booking-point check remains. |
| **F05 · Weather-Aware Guide** | Check a planned boat ride against forecasts and available warnings. | Prototype: sourced Open-Meteo assessment and Kerala warning lookup; live alert needs validation. |
| **F06 · Smart Transport** | Scan a bus destination and ask whether it may serve the intended stop. | Prototype: source-aware `MATCH`/`NO_MATCH`/`UNKNOWN` and pinned community reference; no live timetable guarantee. |
| **F07 · Cultural Companion** | Ask about a nearby temple's known history or visitor information. | Prototype: source-linked pilot lookup; visual site identity and wider coverage are limited. |
| **F08 · Trip Memory** | Ask later, “What fare and boarding time did the operator say?” | Prototype: local evidence, provisional claims, timeline and deletion; live agreement review needs a full check. |
| **F09 · Evidence & Conflicts** | Booking says 15:30; a later conversation suggests 16:00. | Prototype: conflict cards and user resolution; full live disagreement flow remains. |
| **F10 · Accessibility** | Use large actions and spoken results to read a sign. | Partial: labels and controls exist; TalkBack, scaling and contrast audits remain. |
| **F11 · Offline Assistance** | Lose data and retrieve saved phrases, destination and trip evidence. | Partial: local memory, phrasebook and emergency references; offline ASR and maps remain future work. |
| **F12 · Local Discovery** | Find a sourced coir craft experience or nearby operator. | Prototype: small Alappuzha pilot; merchant coverage and live availability are limited. |
| **F13 · Physical Context Engine** | Keep an authorized trip available as location, weather and agreements change. | Prototype: foreground sensing, sensor state and alert policy; endurance and recovery checks remain. |

## Screenshots and demo video

> Replace these placeholders with **real captures** from the Android build. Label synthetic inputs and fixtures in the demo.

| Home and trip | Sign scan | Conversation | Memory and conflicts |
| :---: | :---: | :---: | :---: |
| ![Home screenshot placeholder](docs/assets/screenshot-placeholder.svg) | ![Sign scan screenshot placeholder](docs/assets/screenshot-placeholder.svg) | ![Conversation screenshot placeholder](docs/assets/screenshot-placeholder.svg) | ![Memory screenshot placeholder](docs/assets/screenshot-placeholder.svg) |
| `docs/screenshots/home.png` | `docs/screenshots/sign-scan.png` | `docs/screenshots/conversation.png` | `docs/screenshots/memory-conflict.png` |

**Demo video:** _Coming soon._ Add a link to a real, captioned walkthrough of a bus-board scan, local conversation and source-backed trip decision.

## Tech stack

| Layer | Technology in this repository | Role |
| --- | --- | --- |
| Android app | Kotlin, Android Views, Gradle Kotlin DSL, Android SDK 36 | Home, assistant bubble, trip UI and consent controls. |
| Sensing | CameraX, `AudioRecord`, Android location and motion/system sensors, foreground service | Permissioned observations and truthful sensor state. |
| On-device AI | Cactus/Mirage via JNI; bundled Vosk wake model | Local scene cues and opt-in English “Hello assistant” wake pilot. Live wake reliability is unverified. |
| Local memory | Android `SQLiteOpenHelper` | Trips, destinations, evidence, claims, conflicts and timeline. |
| Backend | Python, FastAPI, Pydantic, Uvicorn, HTTPX | Local API bridge, validation, policies and provider calls. |
| Indian-language AI | Sarvam Saaras, translation, Bulbul, 105B and document OCR | Consented speech, translation, synthesis, planning and selected-crop OCR. |
| External data | Open-Meteo, public IMD Kerala warning page, curated sources, community bus reference | Weather, culture, discovery and cautious transport candidates. |
| Verification | pytest, JUnit, Android instrumentation | Policy, backend and selected device checks. |

See [third-party model provenance](THIRD_PARTY_MODELS.md), [bus-data limitations](docs/bus-data.md) and the [local wake pilot](docs/local-wake.md).

## Architecture

This diagram reflects the **current prototype's main boundaries**. The [PRD](YATRALENS_MASTER_PRD.md) describes the intended full system separately.

```mermaid
flowchart LR
    U[Traveller] --> UI[Home / assistant bubble / trip screen]
    UI --> CONSENT{Permissions and request consent}
    CONSENT --> SERVICE[TripSensingService foreground session]
    CONSENT --> GW[CloudGateway approved request]
    subgraph PHONE[Android phone]
      CAM[CameraX] --> SERVICE
      MIC[AudioRecord] --> SERVICE
      LOC[GPS + accuracy + heading] --> SERVICE
      SYS[Motion + battery + network + thermal] --> SERVICE
      SERVICE --> PCE[PhysicalContextEngine trip situation]
      SERVICE --> VISION[Mirage / Cactus JNI local scene cues]
      SERVICE --> DB[(TripMemoryStore local SQLite)]
      DB --> POLICY[Freshness + conflict + alert policies]
      PCE --> POLICY
      POLICY --> UI
      VISION --> UI
      DB --> UI
      WAKE[Vosk wake pilot / assistant gesture] --> UI
    end
    GW --> API[FastAPI local development gateway]
    subgraph PROVIDERS[Backend policies and external sources]
      API --> GUARD[Authentication + cloud consent + source rules]
      GUARD --> SARVAM[Sarvam speech / translation / TTS / planning / OCR]
      GUARD --> WEATHER[Open-Meteo + public IMD warning page]
      GUARD --> REFS[Curated places + pinned bus reference]
    end
    API --> GW
    GW --> UI
    UI -->|Reviewed evidence and decisions| DB
```

**Trust boundary:** Trip memory stays on the phone. Selected image crops, spoken turns, typed text or location reach the backend for an approved feature. The Sarvam key stays on the backend. Today's debug APK uses a disposable token and ADB reverse to a loopback server; it is not a production deployment.

### Sequence: read a sign and check a bus

```mermaid
sequenceDiagram
    actor Traveller
    participant UI as Android UI
    participant Sensor as TripSensingService
    participant Mirage as On-device Mirage
    participant API as FastAPI
    participant Sarvam as Sarvam OCR / Translate
    participant Bus as Transport policy
    participant Memory as Local memory
    Traveller->>UI: Start trip and allow camera
    UI->>Sensor: Begin permitted camera sensing
    Sensor->>Mirage: Analyze selected scene locally
    Mirage-->>UI: Tentative scene cues
    Traveller->>UI: Approve selected crop for cloud OCR
    UI->>Sensor: Capture crop
    Sensor->>API: Upload crop with consent
    API->>Sarvam: Extract Malayalam text
    Sarvam-->>API: Text and regions
    API-->>UI: Original text and source details
    opt Traveller requests translation
      UI->>API: Interpret reviewed OCR text
      API->>Sarvam: Translate selected text
      Sarvam-->>API: Translation
      API-->>UI: Translation with original text
    end
    UI->>API: Compare board with destination
    API->>Bus: Check available reference
    Bus-->>API: MATCH / NO_MATCH / UNKNOWN with caveats
    API-->>UI: Source-aware transport result
    UI-->>Traveller: Show evidence and conductor question
    opt Traveller reviews and saves text
      UI->>Memory: Store source-linked OCR evidence
    end
```

### Sequence: communicate, remember and resolve a changed time

```mermaid
sequenceDiagram
    actor Traveller
    actor Local as Local person
    participant App as Android conversation UI
    participant API as FastAPI
    participant Sarvam as Sarvam language APIs
    participant Store as Local TripMemoryStore
    Traveller->>App: Start a consented speech turn
    App->>API: Send approved audio
    API->>Sarvam: Transcribe and translate
    Sarvam-->>API: Original and Malayalam wording
    API-->>App: Original and Malayalam wording
    Traveller->>App: Review and play question
    App->>API: Request speech for reviewed wording
    API->>Sarvam: Synthesize Malayalam audio
    Sarvam-->>API: Speech audio
    API-->>App: Speech audio
    App->>Local: Speak reviewed Malayalam
    Local->>App: Reply in Malayalam with consent
    App->>API: Send approved reply
    API->>Sarvam: Transcribe and translate reply
    Sarvam-->>API: Transcript and traveller-language text
    API-->>App: Transcript and traveller-language text
    App-->>Traveller: Show words, numbers and uncertainty
    Traveller->>App: Approve saving quoted time
    App->>Store: Save evidence and provisional claim
    Store->>Store: Compare with booking claim
    alt Material conflict
      Store-->>App: Both sources; block definitive reminder
      App-->>Traveller: Ask whether change was confirmed
      Traveller->>App: Resolve or leave unresolved
      App->>Store: Record auditable decision
    end
```

### Sequence: assess weather against a plan

```mermaid
sequenceDiagram
    actor Traveller
    participant App as Android app
    participant Sensor as TripSensingService
    participant API as FastAPI
    participant Weather as Open-Meteo / IMD page
    participant Policy as Weather policy
    participant Store as Local memory
    Traveller->>App: Ask about planned boat ride
    App->>Sensor: Request location with consent
    Sensor-->>App: Fix, accuracy and time
    App->>API: Send approved fix and activity window
    API->>Weather: Retrieve forecast and warning status
    Weather-->>API: Values, source and checked-at time
    API->>Policy: Assess place and time overlap
    Policy-->>API: Sourced risk / no configured alert / unavailable
    API-->>App: Assessment and provenance
    App-->>Traveller: Explain and suggest operator check
    opt Traveller saves context
      App->>Store: Save timestamped source note
    end
```

### Repository map

```text
android/                 Native Android app, on-device models and device tests
  app/src/main/java/com/yatralens/
backend/app/             FastAPI gateway, provider calls and decision policies
backend/tests/           Backend policy and API tests
docs/bus-data.md         Community transport reference and its limitations
docs/local-wake.md       Wake-phrase pilot and its validation status
THIRD_PARTY_MODELS.md    Bundled model provenance
YATRALENS_MASTER_PRD.md  Full product contract and acceptance scenarios
IMPLEMENTATION_STATUS.md Current tested behavior and open gates
```

## Run locally

**Prerequisites:** ARM64 Android 10+ phone with USB debugging; Android SDK 36, NDK `27.1.12297006`, CMake and JDK 17; Python 3.12 or compatible Python 3. A Sarvam key is needed for cloud language features. Test real sensing on a physical phone.

From the repository root, start the backend:

```powershell
Copy-Item .env.example .env
# Edit .env: add SARVAM_API_KEY and a disposable YATRALENS_DEV_TOKEN.
python -m pip install -r backend/requirements.txt
python -m uvicorn backend.app.main:app --host 127.0.0.1 --port 8000
```

In another terminal, connect and install the app:

```powershell
adb reverse tcp:8000 tcp:8000
Set-Location android
.\gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build reads `YATRALENS_DEV_TOKEN` from `.env` at build time. Keep it disposable. **Never put the Sarvam key in the APK or commit `.env`.** See [current setup notes](IMPLEMENTATION_STATUS.md#run-locally).

### Checks

```powershell
python -m pytest -q backend/tests
Set-Location android
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest  # Connected phone required
```

## Privacy and reliability limits

- Camera, microphone and location use require Android permissions and user-started controls. Cloud requests have separate consent. Raw audio and camera frames are not retained by default.
- A transcript or OCR result can be wrong. Review money, times, place names and agreements; an operator quote is not a confirmed booking.
- Community transport references may be historical. Forecasts cannot prove that a boat is operating or safe.
- Production sign-in, HTTPS deployment, certified emergency/mobility guidance, long-running battery/thermal performance and lifecycle recovery are not complete. See [implementation status](IMPLEMENTATION_STATUS.md).

## Future directions

1. Validate an end-to-end Alappuzha journey with real Malayalam signs, fluent speakers and dated transport evidence.
2. Complete TalkBack, large-text, contrast and low-vision interaction testing.
3. Improve offline speech, downloadable maps and recovery without a network.
4. Measure 30-minute-plus sensing, heat, battery and Android lifecycle recovery across devices.
5. Add production authentication, HTTPS and documented data/provider retention controls.
6. Expand curated local information and language-pair evaluations beyond Kerala.

## Contributing and license

Issues and focused pull requests are useful for reproducible Android bugs, permissioned sign samples, accessibility feedback and source-backed transport corrections. Read the [PRD](YATRALENS_MASTER_PRD.md), [implementation status](IMPLEMENTATION_STATUS.md) and [model provenance](THIRD_PARTY_MODELS.md) first. Do not submit API keys or private trip data; label synthetic fixtures clearly.

**License status:** There is no top-level `LICENSE` file yet. The code is not currently offered under an open-source license; the owner must choose and add one before reuse or redistribution terms are established. Bundled third-party models and data retain their own terms.
