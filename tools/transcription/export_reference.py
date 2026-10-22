# SPDX-License-Identifier: Apache-2.0
# Reference package: Qiuqiang Kong's piano_transcription_inference 0.0.6 (MIT declaration).
# See ../../THIRD_PARTY_NOTICES.md for model and adapted-code attribution.
"""Export the accepted checkpoint unchanged; generate desktop and device fixtures.
Run using the original piano_transcription_inference environment plus pinned ONNX deps.
Personal audio, weights, outputs and intermediate tensors belong under ignored work/.
"""
import argparse, hashlib, json, os, time
from pathlib import Path
os.environ.setdefault('OMP_NUM_THREADS', '4')
import numpy as np
np.float = float
np.int = int
import torch
import soundfile as sf
from piano_transcription_inference import PianoTranscription
from piano_transcription_inference.utilities import RegressionPostProcessor

p = argparse.ArgumentParser()
p.add_argument('--checkpoint', required=True)
p.add_argument('--wav', required=True)
p.add_argument('--output', required=True)
p.add_argument('--reuse-export', action='store_true')
a = p.parse_args()
out = Path(a.output); out.mkdir(parents=True, exist_ok=True)
torch.set_num_threads(4)
engine = PianoTranscription(checkpoint_path=a.checkpoint, device='cpu')
engine.model.eval()
audio, sr = sf.read(a.wav)
assert sr == 16000 and audio.ndim == 1
padded = np.pad(audio, (0, (-len(audio)) % 160000))
segments = engine.enframe(padded[None, :], 160000).astype(np.float32)
keys = ['reg_onset_output', 'reg_offset_output', 'frame_output', 'velocity_output',
        'reg_pedal_onset_output', 'reg_pedal_offset_output', 'pedal_frame_output']
class Export(torch.nn.Module):
    def __init__(self, model):
        super().__init__(); self.model = model
    def forward(self, waveform):
        values = self.model(waveform)
        return tuple(values[k] for k in keys)
model = Export(engine.model).eval()
x = torch.from_numpy(segments[0:1])
if not a.reuse_export:
  with torch.no_grad():
    print('Export starting', flush=True)
    torch.onnx.export(model, x, str(out/'piano.onnx'), input_names=['waveform'],
                      output_names=keys, opset_version=17, do_constant_folding=True)
print('Export complete', flush=True)
import onnx, onnxruntime as ort
onnx.checker.check_model(str(out/'piano.onnx'))
opts = ort.SessionOptions(); opts.intra_op_num_threads=4; opts.inter_op_num_threads=1
opts.graph_optimization_level=ort.GraphOptimizationLevel.ORT_DISABLE_ALL
session = ort.InferenceSession(str(out/'piano.onnx'), sess_options=opts, providers=['CPUExecutionProvider'])
reference = {k: [] for k in keys}; converted = {k: [] for k in keys}; errors = {}
for i, segment in enumerate(segments):
    segment.tofile(out/f'window-{i}.f32')
    with torch.no_grad():
        t = time.perf_counter(); expected = model(torch.from_numpy(segment[None,:])); pt_seconds=time.perf_counter()-t
    t=time.perf_counter(); actual=session.run(keys, {'waveform':segment[None,:]}); ort_seconds=time.perf_counter()-t
    for k, ref, result in zip(keys,expected,actual):
        ref=ref.numpy(); reference[k].append(ref[0]); converted[k].append(result[0])
        errors[k]=max(errors.get(k,0),float(np.max(np.abs(ref-result))))
        result.astype('<f4').tofile(out/f'window-{i}-{k}.f32')
    print(json.dumps({'window':i,'pytorch_seconds':pt_seconds,'onnx_seconds':ort_seconds,'max_abs':errors}),flush=True)
post=RegressionPostProcessor(100, classes_num=88,onset_threshold=.3,offset_threshold=.3,frame_threshold=.1,pedal_offset_threshold=.2)
results={}
for label, frames in [('pytorch',reference),('onnx',converted)]:
    stitched={k:engine.deframe(np.stack(v))[:len(audio)] for k,v in frames.items()}
    notes,pedals=post.output_dict_to_midi_events(stitched)
    def native(value):
        if isinstance(value,dict): return {k:native(v) for k,v in value.items()}
        if isinstance(value,(list,tuple)): return [native(v) for v in value]
        return value.item() if isinstance(value,np.generic) else value
    results[label]=native({'notes':notes,'pedals':pedals})
    (out/f'{label}-events.json').write_text(json.dumps(results[label],indent=2))
manifest={'checkpoint_sha256':hashlib.sha256(Path(a.checkpoint).read_bytes()).hexdigest(),
          'model_sha256':hashlib.sha256((out/'piano.onnx').read_bytes()).hexdigest(),
          'model_bytes':(out/'piano.onnx').stat().st_size,'sample_rate':sr,'audio_samples':len(audio),
          'audio_seconds':len(audio)/sr,'windows':len(segments),'window_samples':160000,
          'output_names':keys,'maximum_absolute_error':errors,
          'pytorch_notes':len(results['pytorch']['notes']),'onnx_notes':len(results['onnx']['notes']),
          'pytorch_pedals':len(results['pytorch']['pedals']),'onnx_pedals':len(results['onnx']['pedals'])}
(out/'manifest.json').write_text(json.dumps(manifest,indent=2));print(json.dumps(manifest),flush=True)
