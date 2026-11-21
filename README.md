# YatraLens

Native Android travel companion prototype with user-started sensor capture, two-way speech translation, selected-text OCR, local trip memory, and source-aware weather checks. The full F01–F13 product contract is in `YATRALENS_MASTER_PRD.md`; current verification and unfinished work are in `IMPLEMENTATION_STATUS.md`.

## Local development

1. Copy `.env.example` to `.env` and set a Sarvam API key and a disposable local development token. `.env` is ignored by Git.
2. Install `backend/requirements.txt`, then run `python -m uvicorn backend.app.main:app --host 127.0.0.1 --port 8000`.
3. Connect an Android ARM64 phone with USB debugging and run `adb reverse tcp:8000 tcp:8000`.
4. In `android/`, run `./gradlew :app:assembleDebug` (Windows: `.\gradlew.bat :app:assembleDebug`) and install the debug APK from `android/app/build/outputs/apk/debug/`.

The debug APK contacts the local backend through ADB reverse. It is not a production deployment. Raw audio and camera frames are not saved by default. Selected uploads require in-app approval. The bundled on-device Mirage model and license provenance are documented in `THIRD_PARTY_MODELS.md`.

## Checks

- Backend: `python -m pytest -q backend/tests`
- Android APK: `./gradlew :app:assembleDebug`
- Connected phone tests: `./gradlew :app:connectedDebugAndroidTest`
