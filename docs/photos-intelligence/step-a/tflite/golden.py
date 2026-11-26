"""Write the JVM test fixture: fp32 ONNX embeddings of 6 synthetic images with their expected
top prompt and keep/junk margin under the shipped prompt table (PromptTableTest checks the Kotlin
maths against these).

Usage: python -I golden.py <vision_model.onnx> <prompt_embeddings_v2.json> <out.json>
"""
import sys, os, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np, onnxruntime as ort
from synth import SYNTH, preprocess

ONNX, PJ, OUT = sys.argv[1:4]
pj = json.load(open(PJ))
T = np.array([p["embedding"] for p in pj["prompts"]], dtype=np.float32).astype(np.float16).astype(np.float64)  # as shipped (fp16)
kinds = np.array([p["kind"] for p in pj["prompts"]]); ids = [p["id"] for p in pj["prompts"]]
o = ort.SessionOptions(); o.log_severity_level = 3
s = ort.InferenceSession(ONNX, o, providers=["CPUExecutionProvider"])
out = []
for name in ("target", "receipt", "landscape", "red_car", "group_cartoon", "dark_frame"):
    e = s.run(None, {"pixel_values": preprocess(SYNTH[name][0]())})[0][0].astype(np.float64); e /= np.linalg.norm(e)
    z = 100 * (T @ e)
    lse = lambda a: a.max() + np.log(np.exp(a - a.max()).sum())
    out.append({"name": name, "top": ids[int(np.argmax(z))], "margin": float(lse(z[kinds == "keep"]) - lse(z[kinds == "junk"])),
                "embedding": [round(float(v), 6) for v in e]})
os.makedirs(os.path.dirname(OUT), exist_ok=True)
json.dump(out, open(OUT, "w"), separators=(",", ":"))
print([(g["name"], g["top"], round(g["margin"], 2)) for g in out])
