# Local piano model

`piano.onnx` is the full float32 export of the original accepted Kong/ByteDance
Note_pedal checkpoint, generated with `tools/transcription/export_reference.py`.
The binary is intentionally ignored by Git. For this local build, copy the verified
`work/conversion/reference/piano.onnx` here; do not substitute another checkpoint.
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
and available offline in Songs → Licenses. See the root THIRD_PARTY_NOTICES.md.
The model binary stays Git-ignored. The bundled demo is CC0 (see ../licenses/DEMO.txt); no public
release, model upload or APK distribution has been authorized.
