# Piano model export

Local development tooling for the model used by the Android importer. Weights, personal audio, generated tensors and reports belong under ignored `work/`.

`export_reference.py` exports the full Note_pedal checkpoint to ONNX opset 17, checks the graph, and compares predictions and extracted events against PyTorch. It requires a 16 kHz mono WAV for parity validation. Its window tensors and JSON reports are local validation outputs, not app assets.

Use Python 3.9 with `piano_transcription_inference==0.0.6`, torch 2.2.2, numpy 1.26.4 and soundfile; add the dependencies in `requirements-conversion.txt` to that environment.

```sh
python export_reference.py --checkpoint /path/to/checkpoint.pth --wav /path/to/mono-16000.wav --output ../../work/conversion/reference
```

Run this command from `tools/transcription/`. `--reuse-export` validates an existing export. Desktop ONNX Runtime is pinned to 1.19.2; Android uses 1.23.2. Both disable graph optimizations because fusion fails with this export.

Check the resulting model against the identity in [the model README](../../android/app/src/main/assets/models/README.md) before placing it in Android assets. Check redistribution terms before sharing weights or a model-containing APK.
