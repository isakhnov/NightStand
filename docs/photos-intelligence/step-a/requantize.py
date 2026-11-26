"""Nightstand Step A: the shipped INT8 export (dynamic ConvInteger) disagrees with fp32, so try
alternatives that keep accuracy and still shrink the file.

Usage: python -I requantize.py <model_dir> <out_dir> <synthetic_png_dir>

Variants written to <out_dir>:
  vision_w8.onnx     weight-only INT8: Conv/MatMul weights stored as per-output-channel symmetric int8
                     + DequantizeLinear; activations stay fp32 (compute is fp32)
  vision_fp16.onnx   all weights fp16, fp32 inputs/outputs (onnxconverter-common)
  vision_qdq.onnx    static QDQ, per-channel weights, activations calibrated on the generated images
                     (illustrative only: real calibration needs real photos)
Then compares each with fp32 (cosine of embeddings, zero-shot top-1) and times it.
"""
import sys, os, glob, time, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np, onnx
from onnx import numpy_helper, helper, TensorProto
import onnxruntime as ort
from PIL import Image, ImageEnhance, ImageFilter

MODEL_DIR, OUT, SYN = sys.argv[1:4]
os.makedirs(OUT, exist_ok=True)
FP32 = os.path.join(MODEL_DIR, "onnx", "vision_model.onnx")
INT8_XENOVA = os.path.join(MODEL_DIR, "onnx", "vision_model_quantized.onnx")


def preprocess(im):
    im = im.convert("RGB"); w, h = im.size; s = 256 / min(w, h)
    im = im.resize((max(256, round(w * s)), max(256, round(h * s))), Image.BILINEAR)
    w, h = im.size; l, t = (w - 256) // 2, (h - 256) // 2
    return (np.asarray(im.crop((l, t, l + 256, t + 256)), dtype=np.float32) / 255.0).transpose(2, 0, 1)[None]


# ---------------- weight-only int8
def weight_only_int8(src, dst, min_size=512, skip_depthwise=False):
    m = onnx.load(src)
    inits = {i.name: i for i in m.graph.initializer}
    consumers = {}
    for n in m.graph.node:
        for k, inp in enumerate(n.input):
            consumers.setdefault(inp, []).append((n, k))
    new_nodes, replaced = [], 0
    for name, init in list(inits.items()):
        uses = consumers.get(name, [])
        if not uses or not all((n.op_type == "Conv" and k == 1) or (n.op_type == "MatMul" and k == 1) for n, k in uses):
            continue
        w = numpy_helper.to_array(init).astype(np.float32)
        if w.size < min_size or w.ndim < 2:
            continue
        if skip_depthwise and any(a.name == "group" and a.i > 1 for n, _ in uses for a in n.attribute):
            continue
        axis = 0 if uses[0][0].op_type == "Conv" else w.ndim - 1
        red = tuple(i for i in range(w.ndim) if i != axis)
        scale = np.abs(w).max(axis=red) / 127.0
        scale[scale == 0] = 1e-8
        shape = [1] * w.ndim; shape[axis] = -1
        q = np.clip(np.round(w / scale.reshape(shape)), -127, 127).astype(np.int8)
        m.graph.initializer.remove(init)
        m.graph.initializer.extend([
            numpy_helper.from_array(q, name + "_q"),
            numpy_helper.from_array(scale.astype(np.float32), name + "_s"),
            numpy_helper.from_array(np.zeros(scale.shape, np.int8), name + "_z"),
        ])
        new_nodes.append(helper.make_node("DequantizeLinear", [name + "_q", name + "_s", name + "_z"], [name], axis=axis))
        replaced += 1
    for i, n in enumerate(new_nodes):
        m.graph.node.insert(i, n)
    onnx.save(m, dst)
    return replaced


def fp16(src, dst):
    from onnxconverter_common import float16
    m = onnx.load(src)
    m16 = float16.convert_float_to_float16(m, keep_io_types=True)
    onnx.save(m16, dst)


def qdq_static(src, dst, imgs):
    from onnxruntime.quantization import quantize_static, CalibrationDataReader, QuantFormat, QuantType, quant_pre_process
    pre = dst + ".pre.onnx"
    quant_pre_process(src, pre, skip_symbolic_shape=True)
    data = []
    for im in imgs:
        for v in (im, ImageEnhance.Brightness(im).enhance(0.7), im.transpose(Image.FLIP_LEFT_RIGHT), im.filter(ImageFilter.GaussianBlur(2))):
            data.append(preprocess(v))
    class R(CalibrationDataReader):
        def __init__(self): self.it = iter(data)
        def get_next(self):
            x = next(self.it, None); return None if x is None else {"pixel_values": x}
    quantize_static(pre, dst, R(), quant_format=QuantFormat.QDQ, per_channel=True,
                    activation_type=QuantType.QUInt8, weight_type=QuantType.QInt8)
    os.remove(pre)


from onnx import version_converter
FP32_17 = os.path.join(OUT, "vision_fp32_opset17.onnx")
onnx.save(version_converter.convert_version(onnx.load(FP32), 17), FP32_17)

imgs = [Image.open(p).convert("RGB") for p in sorted(glob.glob(os.path.join(SYN, "*.png")))]
variants = {"xenova_int8_dynamic": INT8_XENOVA}
out = {}
try:
    p = os.path.join(OUT, "vision_w8.onnx"); out["w8_replaced_tensors"] = weight_only_int8(FP32_17, p); variants["weight_only_int8"] = p
except Exception as e:
    out["w8_error"] = repr(e)
try:
    p = os.path.join(OUT, "vision_w8_big.onnx")
    out["w8_big_replaced_tensors"] = weight_only_int8(FP32_17, p, min_size=16384, skip_depthwise=True)
    variants["weight_only_int8_big_nondepthwise"] = p
except Exception as e:
    out["w8_big_error"] = repr(e)
try:
    p = os.path.join(OUT, "vision_fp16.onnx"); fp16(FP32, p); variants["fp16"] = p
except Exception as e:
    out["fp16_error"] = repr(e)
try:
    p = os.path.join(OUT, "vision_qdq.onnx"); qdq_static(FP32_17, p, imgs); variants["static_qdq_synthetic_calib"] = p
except Exception as e:
    out["qdq_error"] = repr(e)[:400]


def sess(p, threads=4):
    o = ort.SessionOptions(); o.intra_op_num_threads = threads; o.log_severity_level = 3
    return ort.InferenceSession(p, o, providers=["CPUExecutionProvider"])


def emb(s, x):
    e = s.run(None, {"pixel_values": x})[0][0].astype(np.float64); return e / np.linalg.norm(e)


prompts = json.load(open(os.path.join(os.path.dirname(SYN), "prompt_embeddings.json")))
T = np.array([p["embedding"] for p in prompts["prompts"]])
X = [preprocess(im) for im in imgs]
# held-out check images that were NOT used for calibration: flips + crops of the generated ones
X_hold = [preprocess(im.crop((im.width // 8, 0, im.width, im.height)).rotate(3)) for im in imgs]
ref = sess(FP32)
R = [emb(ref, x) for x in X + X_hold]
top_ref = [int(np.argmax(T @ r)) for r in R]
for name, p in variants.items():
    try:
        s = sess(p)
        E = [emb(s, x) for x in X + X_hold]
        cos = [float(a @ b) for a, b in zip(E, R)]
        agree = sum(int(np.argmax(T @ e)) == t for e, t in zip(E, top_ref))
        x = X[0]
        for _ in range(3): s.run(None, {"pixel_values": x})
        ts = []
        for _ in range(20):
            t0 = time.perf_counter(); s.run(None, {"pixel_values": x}); ts.append((time.perf_counter() - t0) * 1000)
        s1 = sess(p, 1)
        for _ in range(2): s1.run(None, {"pixel_values": x})
        ts1 = []
        for _ in range(10):
            t0 = time.perf_counter(); s1.run(None, {"pixel_values": x}); ts1.append((time.perf_counter() - t0) * 1000)
        out[name] = {"size_MB": round(os.path.getsize(p) / 1e6, 1), "cos_vs_fp32_min": round(min(cos), 4),
                     "cos_vs_fp32_mean": round(float(np.mean(cos)), 4), "top1_agree": f"{agree}/{len(E)}",
                     "median_ms_4thr": round(sorted(ts)[10], 1), "median_ms_1thr": round(sorted(ts1)[5], 1)}
    except Exception as e:
        out[name] = {"error": repr(e)[:300]}
out["fp32"] = {"size_MB": round(os.path.getsize(FP32) / 1e6, 1)}
json.dump(out, open(os.path.join(OUT, "requantize_results.json"), "w"), indent=1)
print(json.dumps(out, indent=1))
