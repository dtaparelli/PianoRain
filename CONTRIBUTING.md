# Contributing to PianoRain

Use the [README](README.md) for setup and build instructions. Keep changes focused on the offline piano player and importer, in the existing single app module. Describe what changed, how you checked it, and any device checks still needed.

## Checks

Run from `android/` with JDK 17 and SDK 36:

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug --console=plain
```

Keep the MIDI reference and synthetic edge-case tests. They cover tempo maps, running status, channel-aware overlaps, missing releases, pedal distinction, clipping, held-note visibility, countdown geometry, black-key positions, event extraction, resampling and timeline persistence. Import completion tests cover terminal status persistence, storage failures and publication ordering.

JVM tests do not exercise Android service lifecycle, codecs, physical audio latency or frame rate.

## Implementation

Songs and Player are the app screens. License notices are maintained in the repository and included in APK assets.

### Data flow

```text
Selected audio → private copy → decode / resample → ONNX inference
               → note and pedal extraction → saved timeline → Ready song

Bundled MIDI → MIDI parser ─────┐
Saved imported timeline ────────┤→ visible notes → Compose Canvas
Audio → Media3 source position ─┘
```

The bundled Open Goldberg Aria (CC0) and Chopin Prelude Op. 28 No. 20 (CC BY 3.0) excerpts use PCM WAV and generated MIDI transcriptions. Imported songs use a private audio copy and versioned JSON timeline.

### Code map

- `data/`: bundled catalog, imported-song manifests, timeline serialization and library state.
- `domain/`: immutable song, note and pedal data; clipping at the audio boundary.
- `midi/`: pure Kotlin type-0/type-1 PPQ parser with tempo maps, running status and channel-aware FIFO note pairing. Missing releases close at file end; SMPTE/type-2 files are rejected.
- `playback/`: one ViewModel-owned Media3 player, transport, countdown, speed and saved position.
- `rendering/`: pure key geometry and visible-note queries, plus Compose Canvas drawing.
- `transcription/`: Android decoding, sinc resampling, ONNX inference, pure Kotlin event extraction and the serial import service.
- `ui/`: library/picker/progress, immersive player, controls and fixed theme.
- `tools/transcription/`: local model export and desktop parity validation.

### Playback and rendering

Media3's source position is the only musical clock. All player calls and frame reads stay on its application thread. Display frames update Canvas drawing without recomposing the whole screen.

The three-second countdown uses separate negative display time. Source timestamps never shift. Pause preserves an unfinished countdown; normal pause/resume does not repeat it. Seeking cancels countdown and immediately redraws held and upcoming notes. Scrubbing pauses audio, previews the target and restores the previous playback request when committed.

Notes use key-release durations; pedal events remain separate. Events clip to source duration. At playback end, rendering uses the exact source duration even when the player reports rounded milliseconds. The visible-note index uses prefix maximum releases to include long held notes after seeking.

Playback speed preserves pitch; notes continue to follow source time. Speed and position persist per song. Rotation retains the player; leaving Player or backgrounding pauses it. Owner destruction releases audio resources. A new process restores paused.

Player requests landscape and hides system bars with swipe access. Controls fade after three seconds of playback and reappear on touch. Pause, countdown, seeking, pointer holds and the speed picker keep controls visible; accessibility timeout preferences apply. Songs restores normal orientation. Dynamic colors are disabled.

### Offline import

OpenDocument grants access to selected audio. The importer copies it into app-private storage and releases the document grant once the copy is durable. Retry reuses a completed copy; removal deletes only the private song folder.

MediaExtractor/MediaCodec decodes to mono PCM on disk. Explicit encoder-delay/padding handling preserves source time, leading silence and real timestamp gaps. A 96-tap sinc resampler produces 16 kHz input with PCM16 precision.

The full Note_pedal float32 model runs through ONNX Runtime Android 1.23.2 with four CPU threads and graph optimization disabled. The ignored model asset is copied into private versioned storage and checksum-verified on initial provisioning. Inference uses ten-second windows with 50% overlap and quarter/three-quarter stitching. Predictions spool to disk; extraction reads one pitch at a time. Key releases and pedal events clip to the decoded audio end.

One user-started foreground service owns serial imports, a bounded wake lock and a cancellable notification. API 35+ uses mediaProcessing; API 34 uses specialUse. Cancellation is checked between processing steps and inference windows. Terminal status publication and service shutdown are coordinated on the main thread; manifest writes stay on the worker. Cancel intents do not prevent service shutdown.

Each song has an AtomicFile manifest. A complete timeline is saved before Ready metadata. If the terminal manifest cannot be saved, the in-memory row becomes a retryable failure so the library is usable. After process death, persisted processing rows become interrupted failures. Temporary PCM/prediction files are cleaned after completion, failure or cancellation.

### Storage and boundaries

Imported audio, timelines, manifests and playback preferences stay private to the app. No Internet permission, model download or server fallback is present. Model identity and attribution are in the [model README](android/app/src/main/assets/models/README.md).

## Current limitations

- Imports: ten minutes / 100 MB, mono/stereo 8–96 kHz; at least 500 MB free before processing. Cancellation waits for the current inference window.
- The APK packages arm64 native libraries and a roughly 154 MB model. Fresh checkouts need the ignored model provisioned separately.
- Piano transcription is approximate, especially with noise, reverb or other instruments. Decoder differences can affect low-confidence notes.
- Lilac/rose indicate note register, not hands. Tile lengths represent key duration, not pedal-extended sound.
- No background playback, note editing, hand assignment, MIDI export/synthesis, video export or microphone recording.
- The bundled demos are Aria (26.5 seconds, CC0) and Chopin Prelude Op. 28 No. 20 (30 seconds, CC BY 3.0), with approximate generated MIDI. Original source silence is preserved; the last 0.5 seconds fade out.
- The former personal assets and regression fixtures are backed up only under ignored `work/private-sample/`. Repository history excludes these files.

## Device checks

Primary device: Samsung Galaxy A55, Android 16 / API 36. Existing device checks cover local import/playback, cancel/retry, process interruption/recovery, background import, saved-library persistence and removal without altering the source file. These do not verify the latest service-shutdown and storage-failure fixes.

Still to check on the current APK:

- Cancel an import from Songs and from its notification; confirm service exit, then retry and complete another import.
- Exhaust storage during processing/final save; confirm a visible failure and that the library accepts further work after space is freed.
- First-launch airplane-mode import, unsupported/protected formats and API 26–35 devices.
- Wider AAC/MP3/WAV recordings, long-session memory/thermals and ten-minute imports.
- Speaker/wired/Bluetooth sync, frame rate, rotation, background pause, seek previews and accessibility on the current build.
- Play both public demos end to end, check perceptual note alignment and their fades, and seek through held chords and initial silence.
