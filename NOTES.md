# PianoRain development notes

## Current limits

- Imports: ten minutes / 100 MB, mono/stereo 8–96 kHz; at least 500 MB free before processing. Cancellation waits for the current inference window.
- The APK packages arm64 native libraries and a roughly 154 MB model. Fresh checkouts need the ignored model provisioned separately.
- Piano transcription is approximate, especially with noise, reverb or other instruments. Decoder differences can affect low-confidence notes.
- Lilac/rose indicate note register, not hands. Tile lengths represent key duration, not pedal-extended sound.
- No background playback, note editing, hand assignment, MIDI export/synthesis, video export or microphone recording.
- The bundled demo is a 26.5-second CC0 Open Goldberg Aria excerpt, with approximate generated MIDI. Original source silence is preserved; the last 0.5 seconds fade out.
- The former personal assets and regression fixtures are backed up only under ignored `work/private-sample/`. The repository has a fresh current-version history that excludes these files.

## Licenses

Original code/documentation use Apache 2.0. Model weights retain CC BY 4.0. Full notices are bundled under `assets/licenses/` and readable offline through Songs → Licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). No public upload or distribution is authorized.

## Automated checks

Run from `android/` with JDK 17 and SDK 36:

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug --console=plain
```

Keep the MIDI reference and synthetic edge-case tests. They cover tempo maps, running status, channel-aware overlaps, missing releases, pedal distinction, clipping, held-note visibility, countdown geometry, black-key positions, event extraction, resampling and timeline persistence. Import completion tests cover terminal status persistence, storage failures and publication ordering.

JVM tests do not exercise Android service lifecycle, codecs, physical audio latency or frame rate.

## Device checks

Primary device: Samsung Galaxy A55, Android 16 / API 36. Existing device checks cover local import/playback, cancel/retry, process interruption/recovery, background import, saved-library persistence and removal without altering the source file. These do not verify the latest service-shutdown and storage-failure fixes.

Still to check on the current APK:

- Cancel an import from Songs and from its notification; confirm service exit, then retry and complete another import.
- Exhaust storage during processing/final save; confirm a visible failure and that the library accepts further work after space is freed.
- First-launch airplane-mode import, unsupported/protected formats and API 26–35 devices.
- Wider AAC/MP3/WAV recordings, long-session memory/thermals and ten-minute imports.
- Speaker/wired/Bluetooth sync, frame rate, rotation, background pause, seek previews and accessibility on the current build.
- Open Songs → Licenses offline, scroll/select notice text, rotate, and return using both back controls.
- Play the public Aria demo end to end, check perceptual note alignment and the fade, and seek through its initial silence.
