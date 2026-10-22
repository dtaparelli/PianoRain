# PianoRain

Read README.md, NOTES.md and the relevant ARCHITECTURE.md sections before work.

Kotlin/Compose Android app with Songs → Player, bundled audio/MIDI and offline piano-audio import. MediaCodec decoding and ONNX Runtime transcription produce saved source-relative timelines. Native Canvas animation follows Media3's audio position. No prerecorded animation/video, server, YouTube flow or audio upload.

Keep MIDI parsing/domain/layout pure Kotlin where practical. Preserve tempo maps, leading silence, channel-aware note pairing, real source timestamps, key-release versus pedal distinction, and clipping at the audio end. Countdown uses a separate negative display time; never shift source note timestamps. Seeking must immediately redraw held and upcoming notes. Media3 calls stay on its application thread. No independent accumulated musical clock.

Use the plum/lilac/rose theme with dynamic colors disabled. Slicer is read-only workflow inspiration, not a shared codebase or backend. Never reuse its com.slicer identity, amber/cyan/green palette, accounts, server configuration or mixer.

Pause when leaving Player/backgrounding. Preserve rotation state, release audio resources on owner destruction, and restore after process death paused. Single app module; avoid speculative infrastructure.

For changes run from android/ with JDK 17 and SDK 36:
`./gradlew testDebugUnitTest assembleDebug lintDebug --console=plain`.
Test MIDI/clock/geometry edge cases meaningfully; do not describe physical-device sync or rendering as verified by JVM tests. Track device checks separately. Keep README/NOTES current rather than appending session logs.

Do not commit local.properties, debug signing keys, build outputs or machine credentials. The initial personal audio/MIDI sample is authorized for local development, not public publishing. No uploading or remote publishing without authorization. Keep the local signing key stable for APK updates.
