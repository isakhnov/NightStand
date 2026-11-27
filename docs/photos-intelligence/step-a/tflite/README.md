# Step A model: MobileCLIP-S0 image encoder as TFLite (LiteRT via Play services)

Status: **built and validated here; not yet run on the phone.** 2026-11-26.

The user's size decision (no +26 MB ONNX Runtime) changed the plan in [`../STRATEGY.md`](../STRATEGY.md) §2.6/§12:

| | STRATEGY (ONNX Runtime) | Built (this folder) |
|---|---|---|
| Runtime | ORT 1.28 AAR, +10.6–28.7 MB | **LiteRT from Google Play services** (`com.google.android.gms:play-services-tflite-java:16.4.0`): not in the APK; only a 0.58 MB arm64 JNI client is |
| Model | 12.9 MB in the APK | **13.1 MB, downloaded on demand** when Smart selection is turned on (or imported from a file), SHA-256-checked, stored in app files |
| APK | ~26–44 MB | **2.9 MB** (was 1.9 MB) |

16.5.0 of the Play services artifact is newer but its Kotlin metadata (2.2) doesn't compile with this project's Kotlin 1.9.24; 16.4.0 does.

## The shipped file

| | |
|---|---|
| File | `dist/mobileclip_s0_image.tflite` (git-ignored; upload it as a release asset) |
| Download URL (app default) | `https://github.com/isakhnov/NightStand/releases/download/model-mobileclip-s0-v1/mobileclip_s0_image.tflite` |
| Size | **13,086,656 bytes** (13.1 MB) |
| SHA-256 | **`8eaa055f1250e69b1f77271f4a6cb092aaefbdc8ff8a485f773df61e8b1a39bc`** (`ModelStore.SHA256`) |
| Input | `pixel_values` float32 **NHWC [1, 256, 256, 3]**, RGB/255, no mean/std (the app reads the layout from the tensor shape) |
| Output | `image_embeds` float32 [1, 512], not normalised (the app L2-normalises) |
| Quantisation | weight-only INT8, per-output-channel symmetric, explicit `DEQUANTIZE`, all maths in fp32; only `CONV_2D`/`FULLY_CONNECTED` weights with ≥ 16,384 elements (depthwise and small tensors stay fp32) |
| Ops | ABS, ADD, AVERAGE_POOL_2D, BATCH_MATMUL, CONV_2D, DEPTHWISE_CONV_2D, DEQUANTIZE, DIV, EXP, FULLY_CONNECTED, LOGISTIC, MEAN, MUL, NEG, PAD, RESHAPE, SOFTMAX, SPLIT_V, SUB, TRANSPOSE — builtins only, no Flex/select-TF ops |
| Source | Xenova/mobileclip_s0 `onnx/vision_model.onnx` (fp32), SHA-256 `c5423c45…c32456` (see note below) |
| Licence | Apple's MobileCLIP sample-code licence (personal use; read before distributing) |

## Validation (20 synthetic images, fp32 ONNX = reference) [M]

`validate_tflite.py`: the 10 generators from `../validate.py` (copied verbatim into `synth.py`) plus 10 variants
(rotation, darker, other palette, crop, JPEG q30, blur, flip, zoom, portrait crop, brighter). Top prompt and
keep/junk side use the **v2 prompt table** below. Raw numbers: [`validation.json`](validation.json).

| Variant | Size | cos vs fp32 min / mean | Top-1 | Keep/junk side | Pass (≥ 0.98, 20/20) |
|---|---|---|---|---|---|
| fp32 TFLite | 45.6 MB | 1.0000 / 1.0000 | 20/20 | 20/20 | yes |
| fp16 weights | 22.9 MB | 0.99986 / 0.99993 | 20/20 | 20/20 | yes |
| **weight-only INT8, large non-depthwise (shipped)** | **13.1 MB** | **0.9845 / 0.9924** | **20/20** | **20/20** | **yes** |
| weight-only INT8, every tensor | 12.1 MB | 0.907 / 0.950 | 20/20 | 20/20 | no |
| dynamic-range INT8 (hybrid kernels) | 12.1 MB | 0.847 / 0.911 | 20/20 | 20/20 | no |

Same pattern as the ONNX study: quantising depthwise/small tensors or activations costs too much accuracy;
the selective weight-only file keeps it. x86 CPU (this sandbox, Python LiteRT 2.3, 1 thread) runs all
variants at ~150 ms; dynamic-range is ~2× faster but fails the bar. **Phone latency is unmeasured** — the
Debug tab shows the last scoring latency.

**Look-alike radius** (TFLite w8, cosine to a generated landscape): shift 3 % 0.997 · brightness 0.999 ·
JPEG q50 0.998 · rotate 2° 0.996 · zoom 0.996 · blur 0.986 · same layout, other colours 0.977; different
scenes 0.11–0.50 (mean 0.29). So every setting — **Strict 0.95 / Normal 0.92 / Wide 0.88** — catches all
edits of one shot and is ≥ 0.38 away from unrelated scenes; they differ only in how far a *different shot of
the same subject* reaches (real photos, to tune on the phone).

Note: the vision ONNX's SHA-256 today (`c5423c45…`) differs from the one recorded in `../prompt_embeddings.json`
(`17d3c037…`), though its outputs reproduce the earlier validation to 3–4 decimals (near-duplicate cosines,
10/10 zero-shot). Probably a re-serialised copy; re-download from Hugging Face if that matters.

## Prompt table v2 (the user's "no grey areas" rule)

[`prompts_v2.py`](prompts_v2.py) → [`make_prompts.py`](make_prompts.py) (MobileCLIP-S0 text encoder,
77 tokens padded with id 0 as in `../validate.py`) → `app/src/main/assets/smart/prompts_v2.bin`
(53,810 B, fp16, SHA-256 `11842eb9…ab5c`) and [`prompt_embeddings_v2.json`](prompt_embeddings_v2.json).
The 32 v1 prompts are unchanged (cos 1.0 to v1), 20 added: **27 keep** (people, family, couples, kids,
babies, celebrations, crowds at events, pets, wildlife, landmarks, skylines, travel streets, mountains,
beaches, nature, flowers, cool/classic cars, motorcycles, **firearm collections, rifles, handguns, knife and
sword collections**) and **25 junk** (targets, panels, wiring, parking pillars and bays, receipts,
screenshots, documents, notes, tickets, labels, whiteboards, product boxes, sell/return shots, price tags,
monitors, dashboards, damage, tools, **food, menus, memes, drawings**, blurry, dark). Margin = LSE(keep) −
LSE(junk) at logit scale 100; > 0 = keep. All 20 test images land on the expected prompt; the drawing
prompt does not swallow the vector-drawn car/landscape/group.

## Re-run

From a scripts folder outside the repo, with a venv holding `onnx2tf[tensorflow]==2.6.9`, `ai-edge-quantizer`,
`onnxruntime`, `tokenizers`, `pillow` (Python 3.13 here); the model folder is only read:

```
python -I make_prompts.py  <model_dir> <this folder> app/src/main/assets/smart/prompts_v2.bin
python -I convert.py       <model_dir>/onnx/vision_model.onnx <out>
python -I validate_tflite.py <model_dir>/onnx/vision_model.onnx prompt_embeddings_v2.json <out>/validation.json <out>/mobileclip_s0_image_*.tflite
python -I golden.py        <model_dir>/onnx/vision_model.onnx prompt_embeddings_v2.json app/src/test/resources/smart/golden_embeddings.json
cp <out>/mobileclip_s0_image_w8.tflite dist/mobileclip_s0_image.tflite   # then update ModelStore.SHA256/SIZE_BYTES
```

Conversion notes: onnx2tf 2.x's default `flatbuffer_direct` backend fails on this graph (Softmax axis), so
`tflite_backend="tf_converter"`; shapes must be made static first (onnxsim with `[1,3,256,256]`), or the
attention blocks come out as `[?,512,?,?]`; GELU's `Erf` would be a Flex op, so it is replaced with onnx2tf's
builtin polynomial erf (|error| < 1.5e-7). TF's own `Optimize.DEFAULT` on the SavedModel fails in TF 2.21,
so quantisation uses ai-edge-quantizer (now "LiteRT Quantizer") on the float TFLite.

## Not verified here

- That Play services' LiteRT on the S26 Ultra accepts every op version in the file (all builtins, low versions;
  per-channel `DEQUANTIZE` is the newest feature used). If it refuses, Smart selection falls back to random
  and Debug shows "failed: …".
- Phone latency, memory of the loaded interpreter, and agreement with fp32 on real photos.
