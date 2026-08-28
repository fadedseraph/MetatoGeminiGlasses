# MetatoGeminiGlasses 🕶️⚡

<p align="center">
  <strong>Native Android Multimodal Live AI Assistant for Ray-Ban Meta Smart Glasses & Bluetooth Headsets, powered by Google Gemini Live (WebSocket) and REST APIs.</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Compose" />
  <img src="https://img.shields.io/badge/AI%20Engine-Gemini%202.0%20Multimodal%20Live-8E75B2?style=for-the-badge&logo=google&logoColor=white" alt="Gemini" />
  <img src="https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge" alt="MIT License" />
</p>

---

## 📖 Overview

**MetatoGeminiGlasses** transforms your smart glasses (Ray-Ban Meta, Ray-Ban Stories, or standard Bluetooth audio headsets) into an interactive, full-duplex AI companion powered by **Google Gemini 2.0 Multimodal Live**. 

Speak naturally hands-free through your smart glasses, stream continuous visual context through your phone camera, and capture high-resolution photos on your glasses that are automatically piped directly into your live Gemini conversation.

---

## ✨ Features

- **🗣️ Full-Duplex Conversational Voice (Gemini Live)**
  - Low-latency bidirectional WebSocket connection streaming 16kHz PCM audio from glasses microphones and playing 24kHz PCM audio through glasses open-ear speakers.
  - Native **barge-in interruption** (<10ms hardware buffer purge) when speaking over the assistant.

- **📸 Seamless Ray-Ban Meta Photo Sync**
  - High-speed `MediaStore` `ContentObserver` monitoring auto-imported media from the Meta View app.
  - **In Live Sessions:** Newly synced photos are pushed straight into the ongoing Gemini Live WebSocket context without intrusive pop-ups, maintaining full conversational memory.
  - **In Idle Mode:** Photos trigger the built-in **Snapshot Inspector** for deep multimodal scene analysis via Gemini REST API.

- **👓 Smart Glasses Bluetooth Routing**
  - Modern Android 12+ (API 31+) `AudioManager.setCommunicationDevice` with fallback to legacy Bluetooth SCO.
  - Real-time connection status badges and auto-reconnect handling.

- **👁️ CameraX Vision Streamer**
  - Lifecycle-bound phone camera viewfinder with rate-limited (1–2 FPS) YUV-to-JPEG downscaling and Base64 streaming.

- **🎨 Futuristic Glassmorphism HUD Overlay**
  - Jetpack Compose Material 3 Heads-Up Display featuring live subtitle transcription tickers, audio waveform RMS visualizers, reticle targets, and a quick-action dock.

- **🧪 Offline Developer Mock Sandbox**
  - Standalone mock engine simulating WebSocket handshakes, simulated streaming tokens, synthetic 24kHz musical tone PCM generation, and zero-latency mic loopback testing without requiring an API key.

---

## 🏛️ Architecture

MetatoGeminiGlasses is built following **Clean Architecture** and reactive unidirectional data flow (MVI/StateFlow) with Koin Dependency Injection:

```
┌─────────────────────────────────────────────────────────────┐
│                 Presentation Layer (Compose M3)             │
│   • LiveHudScreen & Glassmorphic HUD Overlays               │
│   • LiveHudViewModel / StateFlow UI State & Events          │
└──────────────────────────────┬──────────────────────────────┘
                               │ StateFlow / UseCases
┌──────────────────────────────▼──────────────────────────────┐
│                        Domain Layer                         │
│   • Models (GlassesPhoto, AudioChunk, GeminiMessage)        │
│   • UseCases (StartSession, SendAudio, SendFrame, BargeIn)  │
│   • Repository Interfaces                                   │
└──────────────┬───────────────────────────────┬──────────────┘
               │                               │
┌──────────────▼──────────────┐ ┌──────────────▼──────────────┐
│          Data Layer         │ │         Media Layer         │
│ • GeminiLiveWebSocket (Bidi)│ │ • GlassesPhotoSyncManager   │
│ • GeminiRestClient (REST)   │ │ • AudioCaptureManager(16kHz)│
│ • DataStore Preferences     │ │ • AudioPlaybackManager(24kHz│
│ • GeminiMockEngine (Offline)│ │ • BluetoothAudioManager     │
│                             │ │ • CameraManager & CameraX   │
└─────────────────────────────┘ └─────────────────────────────┘
```

---

## 🚀 Getting Started

### Prerequisites
1. **Android Studio** (Koala / Ladybug or newer recommended).
2. **Android SDK** with Min SDK 26 (Android 8.0+) and Target SDK 34 (Android 14+).
3. **Google Gemini API Key** (from [Google AI Studio](https://aistudio.google.com/)).
4. *(Optional for Glasses)* **Ray-Ban Meta Smart Glasses** paired to your phone via the official **Meta View** app.

### Setting Up Ray-Ban Meta Photo Sync
To enable automatic photo sync from your glasses into Gemini:
1. Open the **Meta View** app on your Android device.
2. Go to **Settings > Media**.
3. Turn **ON** **"Auto-import media"**.
4. When you capture a photo via the glasses button or *"Hey Meta, take a photo"*, the image will sync to your gallery and MetatoGeminiGlasses will immediately ingest it into your live session.

---

## 🛠️ Building & Running

### 1. Clone the repository
```bash
git clone https://github.com/fadedseraph/MetatoGeminiGlasses.git
cd MetatoGeminiGlasses
```

### 2. Run Unit & Integration Tests
```powershell
./gradlew testDebugUnitTest
```

### 3. Build Debug APK
```powershell
./gradlew assembleDebug
```
The output APK will be located at:
`app/build/intermediates/apk/debug/app-debug.apk`

---

## ⚙️ Configuration & Settings

You can configure settings via the in-app **Settings Drawer** (tap the gear icon on the HUD):
* **Gemini API Key:** Enter your Google Gemini API key.
* **Developer Mock Mode:** Toggle offline sandbox mode to test without consuming tokens or network connectivity.
* **Mic Loopback Mode:** Real-time microphone capture -> speaker playback loop for hardware audio testing.
* **Auto-Analyze Glasses Photos:** Toggle automatic ingestion of photos taken on Ray-Ban Meta smart glasses.
* **Model Selection:** Choose between `gemini-2.0-flash-exp` (Live Bidi), `gemini-1.5-flash`, and `gemini-1.5-pro`.
* **Voice Selection:** Choose from prebuilt Gemini voice personas (`Aoede`, `Charon`, `Fenrir`, `Kore`, `Puck`).

---

## 📜 License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.

---

## 🙏 Acknowledgments
- Inspired by the open-source concept of [GlassifAI](https://github.com/iannellomarco/GlassifAI).
- Powered by [Google Gemini Live Multimodal API](https://ai.google.dev/).
