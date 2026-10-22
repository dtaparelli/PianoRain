# Third-party notices

PianoRain's original code is Apache 2.0. Dependencies and model weights retain their own terms. The Aria demo is CC0; the Chopin recording/excerpt is CC BY 3.0; private material stays outside the current source tree. See [NOTICE](NOTICE).

## Included in the APK

Open **Songs → Licenses** to read the same offline documents stored in [assets/licenses](android/app/src/main/assets/licenses/):

- `PIANORAIN.txt`: PianoRain copyright, demo attribution and private-material exclusion and full Apache 2.0 license.
- `MODEL.txt`: model creator/source, CC BY 4.0 attribution, ONNX conversion details and adapted-code attribution/MIT terms.
- `CC-BY-4.0.txt`: full model license.
- `DEMO.txt`, `CC0-1.0.txt` and `CC-BY-3.0.txt`: Open Goldberg Aria and Ivan Ilic Chopin sources, credits, excerpt/transcription changes and full demo license terms.
- `ANDROID-DEPENDENCIES.txt`: resolved runtime module inventory, Apache 2.0 terms and embedded artifact license text.
- `ONNX-RUNTIME.txt`: ONNX Runtime 1.23.2 MIT license and its complete upstream third-party notices. The upstream notice bundle covers multiple build configurations; inclusion does not imply every listed component is in this arm64 APK.

The [model distribution record](https://zenodo.org/records/4034264) lists CC BY 4.0. Model weights are ignored by Git; a separately provisioned export is bundled in local APKs. Their license is separate from the application code.

The inference package 0.0.6 [declares MIT in its metadata](https://github.com/qiuqiangkong/piano_transcription_inference/blob/master/setup.py); it has no standalone license file. The [training repository](https://github.com/bytedance/piano_transcription#license) declares Apache 2.0. `NoteExtractor.kt` and the export wrapper preserve their provenance.

## Development-only dependencies

JUnit 4.13.2 is EPL 1.0; Hamcrest 1.3 is BSD 3-Clause; JSON-java 20240303 is public domain. These test dependencies are not packaged in the APK. Gradle 8.11.1 and the Kotlin/Android build plugins use Apache 2.0 for their own code, with separate notices for their dependencies. The Gradle wrapper retains its upstream source headers.

Python model-export tooling runs locally and is not included in the APK. It uses piano_transcription_inference (MIT declaration), PyTorch (BSD-style), NumPy (BSD 3-Clause), SoundFile (BSD 3-Clause), ONNX (Apache 2.0), ONNX Runtime (MIT), protobuf (BSD 3-Clause), FlatBuffers (Apache 2.0), coloredlogs/humanfriendly (MIT), and their transitive dependencies. Native tools and transitive packages retain their own licenses; do not redistribute an export environment under PianoRain's license.

## Maintaining the notices

After dependency changes, resolve `:app:dependencies --configuration debugRuntimeClasspath`, check the relevant POMs (including inherited licenses), and collect applicable LICENSE/NOTICE files from the resolved AAR/JARs. Refresh the inventory and ONNX version-specific notices. Preserve full license texts and required attribution in both the repository and APK. A model replacement needs its own source/license review.

The personal demo and old regression fixtures are no longer in the app or test resources. They remain only in ignored private backups, outside the fresh repository history; do not publish those backups. The public source repository excludes model binaries, APKs and private backups.
