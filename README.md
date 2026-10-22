# PianoRain

An offline Android app that plays piano recordings with falling notes and a live keyboard.
Import piano audio, wait for **Ready offline**, then open it in the player. Transcription runs on the phone and is approximate. The bundled demo is a 26.5-second excerpt of Bach’s *Goldberg Variations — Aria*, played by Kimiko Ishizaka (Open Goldberg Variations, CC0). Its falling notes are an approximate transcription.

## Features

- Local audio import with progress, cancellation, retry and removal.
- Landscape piano roll drawn live from note events.
- Play/pause, seeking, ten-second skips and 25%–100% playback speed with preserved pitch.
- Three-second initial countdown and saved playback position. Leaving Player or backgrounding pauses audio; process restart restores paused.
- No account, server or audio upload.

Imports support mono/stereo audio at 8–96 kHz, up to 10 minutes / 100 MB. M4A/AAC, MP3 and PCM WAV support depends on Android's decoders. Keep at least 500 MB free. Cancelling waits for the current inference window to finish. Removing a song deletes the app's copy; the original file stays intact.

## Built with

Kotlin, Jetpack Compose, Media3 ExoPlayer, Android MediaCodec and ONNX Runtime. A single app module handles the library, transcription and playback. The interface uses a fixed plum/lilac/rose theme.

## Build

Requires JDK 17 and Android SDK 36. The app supports Android 8.0+ (API 26) and currently packages arm64 native libraries.

1. Provision the ignored ONNX model using the [model instructions](android/app/src/main/assets/models/README.md). A fresh checkout does not include it.
2. Open `android/` in Android Studio, or set `sdk.dir` in `android/local.properties`.
3. Create a local debug key once if it does not already exist, then build:

```sh
cd android
keytool -genkeypair -keystore app/debug.keystore -storepass android -keypass android -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -validity 10000
./gradlew testDebugUnitTest assembleDebug lintDebug --console=plain
```

Keep the same debug key for APK updates. Do not commit it or `local.properties`.
The APK is `android/app/build/outputs/apk/debug/app-debug.apk`; application ID is `com.pianorain`.

## Development

See [CONTRIBUTING.md](CONTRIBUTING.md) for developer guidance, code structure, timing rules, limitations and device checks.
Unit tests protect MIDI timing, note extraction, geometry and import completion. They do not verify physical audio sync or rendering performance.

## License

Original code and documentation use [Apache 2.0](LICENSE). The model uses CC BY 4.0; libraries retain their own licenses. See [third-party notices](THIRD_PARTY_NOTICES.md), also available offline through **Songs → Licenses**.

The demo recording, edited excerpt, MIDI and regression data use CC0; see the [demo attribution](android/app/src/main/assets/licenses/DEMO.txt). The former personal sample is kept only in ignored local storage. The local repository starts from a single snapshot of the current version; private assets are excluded from its history.
