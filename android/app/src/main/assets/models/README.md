# Local piano model

`piano.onnx` is the full float32 export of the original accepted Kong/ByteDance
Note_pedal checkpoint, generated with `tools/transcription/export_reference.py`.
The binary is intentionally ignored by Git. Provision it locally before building:

1. Download `CRNN_note_F1=0.9677_pedal_F1=0.9186.pth` from the
   [checkpoint record](https://zenodo.org/records/4034264). Its SHA-256 is
   `c3fa9730725bf4a762f1c14bc80cd5986eacda01b026f5a4a2525cd607876141`.
2. Set up the pinned Python environment and run the export/parity command in
   [the export guide](../../../../../../tools/transcription/README.md), using a
   local mono 16 kHz WAV for validation. Keep outputs under ignored `work/`.
3. Verify the exported model against the SHA-256 and size below, then copy it
   to `android/app/src/main/assets/models/piano.onnx` from the repository root.

Do not substitute another checkpoint.
There is no runtime download or server fallback.

SHA-256: 99418f7e760edd759d0cbf46c001c97bedf56dae621667376e1ba8a42a78fec4
Size: 154214211 bytes. Runtime: ONNX Runtime Android 1.23.2 CPU, optimizations disabled.

Reference implementation and regression/peak algorithm attribution:
Qiuqiang Kong et al., High-resolution Piano Transcription with Pedals by Regressing
Onsets and Offsets Times (2020), https://arxiv.org/abs/2010.01815 .
https://github.com/qiuqiangkong/piano_transcription_inference (package 0.0.6,
whose package metadata declares MIT); training: https://github.com/bytedance/piano_transcription .
Checkpoint source: https://zenodo.org/records/4034264 .
ONNX Runtime is MIT licensed: https://github.com/microsoft/onnxruntime .

The original checkpoint is CC BY 4.0 according to its Zenodo record. The ONNX
conversion preserves float32 weights and uses opset 17; it is not retrained or
quantized. Full model attribution, changes and license text are in `../licenses/`
and included in APK assets. See the root THIRD_PARTY_NOTICES.md.
The model binary stays Git-ignored. The bundled demos use CC0 and CC BY 3.0 (see ../licenses/DEMO.txt); the public source repository excludes model binaries and APKs.
