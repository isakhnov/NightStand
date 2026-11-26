"""Compute the v2 prompt embeddings with MobileCLIP-S0's text encoder and write the app asset.

Usage: python -I make_prompts.py <model_dir> <out_dir> <asset_path>
  <model_dir>  holds onnx/text_model.onnx and tokenizer.json (only read)
  writes <out_dir>/prompt_embeddings_v2.json and <asset_path> (binary, see below)

Tokenisation as in ../validate.py: 77 tokens, padded with id 0 ("!"), the way OpenCLIP does.
Binary asset (little-endian): magic "NSPT", u16 version=2, u16 count, u16 dim, f32 logit_scale,
then per prompt: u8 kind (1 keep, 0 junk), u8 id_len, id bytes (ASCII), dim x float16 (L2-normalised).
"""
import sys, os, json, struct, hashlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer
from prompts_v2 import PROMPTS, TEMPLATE

MODEL_DIR, OUT, ASSET = sys.argv[1:4]
os.makedirs(OUT, exist_ok=True); os.makedirs(os.path.dirname(ASSET), exist_ok=True)
tok = Tokenizer.from_file(os.path.join(MODEL_DIR, "tokenizer.json"))
PAD = tok.token_to_id("!")
assert PAD == 0, PAD
texts = [TEMPLATE.format(p) for _, _, p in PROMPTS]
ids = np.full((len(texts), 77), PAD, dtype=np.int64)
for i, t in enumerate(texts):
    e = tok.encode(t).ids[:77]; ids[i, :len(e)] = e
o = ort.SessionOptions(); o.log_severity_level = 3
s = ort.InferenceSession(os.path.join(MODEL_DIR, "onnx", "text_model.onnx"), o, providers=["CPUExecutionProvider"])
T = s.run(["text_embeds"], {"input_ids": ids})[0].astype(np.float64)
T /= np.linalg.norm(T, axis=1, keepdims=True)

with open(ASSET, "wb") as f:
    f.write(b"NSPT"); f.write(struct.pack("<HHHf", 2, len(PROMPTS), T.shape[1], 100.0))
    for (pid, kind, _), v in zip(PROMPTS, T):
        b = pid.encode("ascii")
        f.write(struct.pack("<BB", 1 if kind == "keep" else 0, len(b))); f.write(b)
        f.write(v.astype("<f2").tobytes())
h = hashlib.sha256(open(ASSET, "rb").read()).hexdigest()
tt = T @ T.T; np.fill_diagonal(tt, -1)
n = len(PROMPTS)
cross = sorted(((tt[i, j], PROMPTS[i][0], PROMPTS[j][0]) for i in range(n) for j in range(i + 1, n)
                if PROMPTS[i][1] != PROMPTS[j][1]), reverse=True)[:8]
json.dump({
    "format": "nightstand.prompt_embeddings/v2", "template": TEMPLATE, "logit_scale": 100.0, "dim": int(T.shape[1]),
    "asset_sha256": h, "asset_bytes": os.path.getsize(ASSET),
    "closest_keep_junk_pairs": [(a, b, round(float(c), 3)) for c, a, b in cross],
    "prompts": [{"id": p, "kind": k, "text": TEMPLATE.format(t), "embedding": [round(float(x), 6) for x in v]}
                for (p, k, t), v in zip(PROMPTS, T)],
}, open(os.path.join(OUT, "prompt_embeddings_v2.json"), "w"), separators=(",", ":"))
print("prompts", n, "keep", sum(k == "keep" for _, k, _ in PROMPTS), "asset", os.path.getsize(ASSET), "B sha", h)
print("closest keep/junk pairs:", cross)
