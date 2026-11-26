"""Nightstand Step A: interleaved CPU latency of the vision-model variants (less noisy than one-after-another).
Usage: python -I bench.py <name>=<path.onnx> ...   (batch 1, 3x256x256)"""
import sys, time, json
import numpy as np, onnxruntime as ort

models = dict(a.split("=", 1) for a in sys.argv[1:])
x = np.random.default_rng(0).random((1, 3, 256, 256), dtype=np.float32)
res = {}
for threads in (1, 4):
    sess = {}
    for k, p in models.items():
        o = ort.SessionOptions(); o.intra_op_num_threads = threads; o.log_severity_level = 3
        t0 = time.perf_counter(); s = ort.InferenceSession(p, o, providers=["CPUExecutionProvider"])
        load = (time.perf_counter() - t0) * 1000
        for _ in range(5): s.run(None, {"pixel_values": x})
        sess[k] = (s, load)
    ts = {k: [] for k in models}
    for _ in range(40):
        for k, (s, _) in sess.items():
            t0 = time.perf_counter(); s.run(None, {"pixel_values": x}); ts[k].append((time.perf_counter() - t0) * 1000)
    for k, v in ts.items():
        v.sort()
        res.setdefault(k, {})[f"{threads}thr"] = {"min": round(v[0], 1), "median": round(v[len(v) // 2], 1), "load_ms": round(sess[k][1])}
print(json.dumps(res, indent=1))
