"""Validate TFLite variants of the vision encoder against the fp32 ONNX reference.

Usage: python -I validate_tflite.py <vision_model.onnx> <prompt_embeddings_v2.json> <out_json> <a.tflite> [<b.tflite> ...]

For each of 20 synthetic images (synth.images20(): validate.py's 10 generators + 10 variants):
  cosine(TFLite embedding, fp32 ONNX embedding), and whether the top prompt (v2 set) agrees.
Also: near-duplicate / different-scene cosines with the TFLite model (for the look-alike radius),
per-variant op list with versions (to check against the on-device runtime), size, SHA-256, CPU latency.
Pass bar: min cosine >= 0.98 and 20/20 top-1 agreement.
"""
import sys, os, json, time, hashlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
import onnxruntime as ort
from PIL import Image, ImageEnhance, ImageFilter
from synth import images20, preprocess, img_landscape, jpeg, W, H

ONNX, PROMPTS, OUTJ = sys.argv[1:4]
VARIANTS = sys.argv[4:]
pj = json.load(open(PROMPTS))
T = np.array([p["embedding"] for p in pj["prompts"]]); pids = [p["id"] for p in pj["prompts"]]
kinds = np.array([p["kind"] for p in pj["prompts"]])


def l2n(v):
    v = np.asarray(v, dtype=np.float64).reshape(-1); return v / np.linalg.norm(v)


def margin(e):
    z = 100.0 * (T @ e)
    lse = lambda a: a.max() + np.log(np.exp(a - a.max()).sum())
    return float(lse(z[kinds == "keep"]) - lse(z[kinds == "junk"]))


def sha256(p):
    return hashlib.sha256(open(p, "rb").read()).hexdigest()


o = ort.SessionOptions(); o.log_severity_level = 3
ref = ort.InferenceSession(ONNX, o, providers=["CPUExecutionProvider"])
imgs = images20()
X = {k: preprocess(v) for k, v in imgs.items()}
R = {k: l2n(ref.run(None, {"pixel_values": x})[0]) for k, x in X.items()}

from ai_edge_litert.interpreter import Interpreter


class TFL:
    def __init__(self, path, threads=1):
        self.it = Interpreter(model_path=path, num_threads=threads); self.it.allocate_tensors()
        self.inp = self.it.get_input_details()[0]; self.out = self.it.get_output_details()[0]
        self.nhwc = self.inp["shape"][-1] == 3

    def __call__(self, x):
        if self.nhwc: x = x.transpose(0, 2, 3, 1)
        self.it.set_tensor(self.inp["index"], np.ascontiguousarray(x, dtype=np.float32)); self.it.invoke()
        return l2n(self.it.get_tensor(self.out["index"]))


def ops(path):
    try:
        from ai_edge_litert import schema_py_generated as S
    except Exception:
        return None
    m = S.Model.GetRootAsModel(open(path, "rb").read(), 0)
    names = {v: k for k, v in S.BuiltinOperator.__dict__.items() if not k.startswith("_")}
    out = {}
    for i in range(m.OperatorCodesLength()):
        c = m.OperatorCodes(i)
        code = max(c.BuiltinCode(), c.DeprecatedBuiltinCode())
        out[names.get(code, str(code))] = max(out.get(names.get(code, str(code)), 0), c.Version())
    return out


results = {"reference": {"onnx": os.path.basename(ONNX), "sha256": sha256(ONNX)}, "variants": {}}
base = img_landscape()
dups = {
    "shift_3pct": base.crop((30, 0, W, H - 22)).resize((W, H)),
    "brightness_0.85": ImageEnhance.Brightness(base).enhance(0.85),
    "jpeg_q50": jpeg(base, 50),
    "rotate_2deg": base.rotate(2, resample=Image.BILINEAR, fillcolor=(0, 0, 0)).crop((20, 20, W - 20, H - 20)),
    "zoom_1.15": base.crop((67, 50, W - 67, H - 50)).resize((W, H)),
    "motion_blur": base.filter(ImageFilter.GaussianBlur(4)),
    "other_sunset_palette": img_landscape(1),
}
for path in VARIANTS:
    name = os.path.basename(path)
    try:
        m = TFL(path)
        per = {}
        for k, x in X.items():
            e = m(x)
            rt, et = int(np.argmax(T @ R[k])), int(np.argmax(T @ e))
            per[k] = {"cos": round(float(e @ R[k]), 5), "top_ref": pids[rt], "top_tflite": pids[et],
                      "margin_ref": round(margin(R[k]), 2), "margin_tflite": round(margin(e), 2)}
        cos = [v["cos"] for v in per.values()]
        agree = sum(v["top_ref"] == v["top_tflite"] for v in per.values())
        side = sum((v["margin_ref"] > 0) == (v["margin_tflite"] > 0) for v in per.values())
        eb = m(preprocess(base))
        dupc = {k: round(float(m(preprocess(v)) @ eb), 4) for k, v in dups.items()}
        E = [m(X[k]) for k in list(X)[:10]]
        diff = [float(E[i] @ E[j]) for i in range(10) for j in range(i + 1, 10)]
        dupc["different_scenes_min_mean_max"] = [round(min(diff), 4), round(float(np.mean(diff)), 4), round(max(diff), 4)]
        x = X["landscape"]
        for _ in range(3): m(x)
        ts = []
        for _ in range(15):
            t0 = time.perf_counter(); m(x); ts.append((time.perf_counter() - t0) * 1000)
        results["variants"][name] = {
            "size_bytes": os.path.getsize(path), "sha256": sha256(path),
            "input": [int(v) for v in m.inp["shape"]], "layout": "NHWC" if m.nhwc else "NCHW",
            "cos_min": round(min(cos), 5), "cos_mean": round(float(np.mean(cos)), 5),
            "top1_agree": f"{agree}/{len(per)}", "keep_junk_side_agree": f"{side}/{len(per)}",
            "pass": bool(min(cos) >= 0.98 and agree == len(per)),
            "median_ms_1thr_x86": round(sorted(ts)[7], 1),
            "near_dup_cos_to_base": dupc, "ops": ops(path), "per_image": per,
        }
    except Exception as e:
        import traceback
        results["variants"][name] = {"error": traceback.format_exc()[-1200:]}
json.dump(results, open(OUTJ, "w"), indent=1)
for k, v in results["variants"].items():
    print(k, {kk: v.get(kk) for kk in ("size_bytes", "cos_min", "cos_mean", "top1_agree", "keep_junk_side_agree", "pass", "median_ms_1thr_x86", "error")})
