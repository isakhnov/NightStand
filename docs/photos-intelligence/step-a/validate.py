"""Nightstand Step A: validate MobileCLIP-S0 (Xenova ONNX export) for zero-shot photo scoring.

Usage:
  python -I validate.py <model_dir> <out_dir>

  <model_dir>  folder holding onnx/{vision_model,vision_model_quantized,text_model}.onnx and tokenizer.json
  <out_dir>    where prompt_embeddings.json, synthetic images, the contact sheet and results.json go

Does:
  1. text embeddings for the prompt set in prompts.py (template "a photo of {}."), L2-normalised -> JSON
  2. zero-shot on synthetic Pillow images (no real photos), fp32 and INT8 vision models
  3. INT8 vs fp32 image-embedding cosine; near-duplicate vs different-scene cosines
  4. CPU latency, INT8 vs fp32, 1 and 4 threads
Nothing here needs network.
"""
import sys, os, json, time, hashlib, io, math, random
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # -I drops the script dir; prompts.py lives here
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageEnhance
from prompts import PROMPTS, CONTROLS, TEMPLATE

MODEL_DIR, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
SYN = os.path.join(OUT, "synthetic"); os.makedirs(SYN, exist_ok=True)
LOGIT_SCALE = 100.0


def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    return h.hexdigest()


def l2n(x):
    return x / np.linalg.norm(x, axis=-1, keepdims=True)


def session(name, threads=4):
    o = ort.SessionOptions(); o.intra_op_num_threads = threads; o.inter_op_num_threads = 1
    o.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    o.log_severity_level = 3
    return ort.InferenceSession(os.path.join(MODEL_DIR, "onnx", name), o, providers=["CPUExecutionProvider"])


# ---------------------------------------------------------------- 1. text embeddings
tok = Tokenizer.from_file(os.path.join(MODEL_DIR, "tokenizer.json"))
PAD = tok.token_to_id("!")  # tokenizer_config pad_token = "!" (id 0); CLIP pools at EOS (argmax id)


def encode_text(texts):
    ids = np.full((len(texts), 77), PAD, dtype=np.int64)
    for i, t in enumerate(texts):
        e = tok.encode(t).ids[:77]
        ids[i, :len(e)] = e
    s = session("text_model.onnx")
    return l2n(s.run(["text_embeds"], {"input_ids": ids})[0].astype(np.float64))


all_prompts = PROMPTS + CONTROLS
texts = [TEMPLATE.format(p) for _, _, p in all_prompts]
T = encode_text(texts)
# padding sanity: same text padded vs unpadded must agree
s_txt = session("text_model.onnx")
# The export has a fixed 77-token positional table (shorter inputs fail), so check instead that
# the pad id does not matter: pad with "!" (0) vs pad with <|endoftext|> must give the same vector.
e0 = tok.encode(texts[0]).ids
alt = np.full((1, 77), tok.token_to_id("<|endoftext|>"), dtype=np.int64); alt[0, :len(e0)] = e0
unpadded = l2n(s_txt.run(["text_embeds"], {"input_ids": alt})[0].astype(np.float64))[0]
pad_cos = float(unpadded @ T[0])

nship = len(PROMPTS)
Tship = T[:nship]
kinds = np.array([k for _, k, _ in PROMPTS])
keep_dir = l2n(Tship[kinds == "keep"].mean(0)); junk_dir = l2n(Tship[kinds == "junk"].mean(0))
out_json = {
    "format": "nightstand.prompt_embeddings/v1",
    "model": "Xenova/mobileclip_s0 (apple/ml-mobileclip MobileCLIP-S0), ONNX",
    "vision_model_sha256": {
        "vision_model_quantized.onnx": sha256(os.path.join(MODEL_DIR, "onnx", "vision_model_quantized.onnx")),
        "vision_model.onnx": sha256(os.path.join(MODEL_DIR, "onnx", "vision_model.onnx")),
    },
    "text_model_sha256": sha256(os.path.join(MODEL_DIR, "onnx", "text_model.onnx")),
    "preprocess": "RGB; resize shortest side to 256 (bilinear); center-crop 256x256; x/255; NCHW float32; no mean/std",
    "template": TEMPLATE,
    "logit_scale": LOGIT_SCALE,
    "dim": int(T.shape[1]),
    "normalised": True,
    "prompts": [
        {"id": i, "kind": k, "text": TEMPLATE.format(p), "embedding": [round(float(v), 6) for v in Tship[j]]}
        for j, (i, k, p) in enumerate(PROMPTS)
    ],
    "keep_direction_minus_junk": [round(float(v), 6) for v in l2n(keep_dir - junk_dir)],
}
with open(os.path.join(OUT, "prompt_embeddings.json"), "w") as f:
    json.dump(out_json, f, separators=(",", ":"))

# text-text structure, for the record
tt = Tship @ Tship.T
np.fill_diagonal(tt, -1)
closest = sorted(((tt[i, j], PROMPTS[i][0], PROMPTS[j][0]) for i in range(nship) for j in range(i + 1, nship)), reverse=True)[:6]

# ---------------------------------------------------------------- 2. synthetic images
W, H = 1024, 768


def font(sz):
    try:
        return ImageFont.load_default(size=sz)
    except TypeError:
        return ImageFont.load_default()


def img_target():
    im = Image.new("RGB", (W, H), (139, 106, 70)); d = ImageDraw.Draw(im)
    d.rectangle([292, 40, 732, 728], fill=(240, 234, 218))
    cx, cy = 512, 360
    for r, c in [(200, (20, 20, 20)), (165, (240, 234, 218)), (130, (20, 20, 20)), (95, (240, 234, 218)), (60, (20, 20, 20)), (28, (200, 30, 30))]:
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=c)
    rnd = random.Random(1)
    for _ in range(14):
        x, y = cx + rnd.randint(-120, 120), cy + rnd.randint(-120, 120)
        d.ellipse([x - 6, y - 6, x + 6, y + 6], fill=(250, 250, 250) if rnd.random() < .5 else (60, 40, 30))
    d.text((330, 640), "NRA B-8  25 YD", fill=(30, 30, 30), font=font(36))
    return im


def img_receipt():
    im = Image.new("RGB", (W, H), (96, 78, 62)); d = ImageDraw.Draw(im)
    r = Image.new("RGB", (330, 700), (246, 244, 238)); rd = ImageDraw.Draw(r)
    lines = ["SUPERMART #0412", "123 MAIN ST", "", "MILK 2%        3.49", "BREAD          2.99", "EGGS 12CT      4.19",
             "COFFEE        11.99", "BANANAS        1.27", "", "SUBTOTAL      23.93", "TAX            1.97", "TOTAL         25.90",
             "", "VISA ****4421", "THANK YOU"]
    for i, ln in enumerate(lines):
        rd.text((24, 24 + i * 40), ln, fill=(40, 40, 40), font=font(24))
    r = r.rotate(-6, expand=True, fillcolor=(96, 78, 62))
    im.paste(r, (330, 20))
    return im


def img_landscape(hue=0):
    im = Image.new("RGB", (W, H)); d = ImageDraw.Draw(im)
    top = np.array([60, 90, 170]) if hue == 0 else np.array([120, 60, 140])
    bot = np.array([250, 170, 110]) if hue == 0 else np.array([255, 120, 80])
    for y in range(H):
        t = y / H; d.line([(0, y), (W, y)], fill=tuple(int(v) for v in top * (1 - t) + bot * t))
    d.ellipse([650, 250, 790, 390], fill=(255, 235, 170))
    d.polygon([(0, 520), (200, 300), (380, 470), (560, 260), (820, 480), (1024, 380), (1024, 768), (0, 768)], fill=(60, 70, 100))
    d.polygon([(560, 260), (610, 320), (575, 312), (540, 335), (520, 300)], fill=(245, 245, 250))
    d.rectangle([0, 600, W, H], fill=(40, 90, 130))
    for y in range(605, H, 14):
        d.line([(0, y), (W, y)], fill=(70, 120, 160), width=2)
    return im.filter(ImageFilter.GaussianBlur(1.2))


def img_panel():
    im = Image.new("RGB", (W, H), (150, 152, 150)); d = ImageDraw.Draw(im)
    d.rectangle([262, 30, 762, 740], fill=(205, 207, 210), outline=(90, 90, 90), width=6)
    for i in range(12):
        y = 70 + i * 54
        for x in (300, 540):
            d.rectangle([x, y, x + 180, y + 38], fill=(35, 35, 38))
            d.rectangle([x + 70, y + 10, x + 110, y + 28], fill=(80, 80, 85))
            d.text((x - 30 if x == 300 else x + 190, y + 8), str(i * 2 + (1 if x == 300 else 2)), fill=(20, 20, 20), font=font(20))
    for c, off in [((200, 40, 30), 0), ((20, 20, 20), 30), ((240, 240, 240), 60), ((40, 120, 40), 90)]:
        d.line([(100 + off, 768), (180 + off, 500), (300, 300 + off)], fill=c, width=10)
    return im


def img_screenshot():
    im = Image.new("RGB", (720, 1480), (250, 250, 252)); d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 720, 60], fill=(240, 240, 244)); d.text((30, 14), "9:41", fill=(0, 0, 0), font=font(30))
    d.text((560, 14), "5G  87%", fill=(0, 0, 0), font=font(30))
    d.rectangle([0, 60, 720, 170], fill=(255, 255, 255)); d.text((30, 90), "Messages", fill=(0, 0, 0), font=font(48))
    for i in range(10):
        y = 200 + i * 125
        d.ellipse([30, y, 120, y + 90], fill=(120 + i * 10, 140, 200))
        d.text((150, y + 8), f"Contact {i + 1}", fill=(0, 0, 0), font=font(32))
        d.text((150, y + 50), "See you tomorrow at 7, bring the...", fill=(120, 120, 120), font=font(26))
        d.line([(150, y + 110), (720, y + 110)], fill=(225, 225, 230), width=2)
    return im


def img_document():
    im = Image.new("RGB", (W, H), (70, 64, 60)); d = ImageDraw.Draw(im)
    d.rectangle([212, 10, 812, 768], fill=(252, 252, 250))
    d.text((260, 50), "LEASE AGREEMENT", fill=(10, 10, 10), font=font(40))
    rnd = random.Random(3)
    words = "the tenant shall pay rent monthly on or before the first day of each month landlord premises term".split()
    for i in range(22):
        ln = " ".join(rnd.choice(words) for _ in range(9))
        d.text((260, 120 + i * 28), ln, fill=(40, 40, 40), font=font(20))
    return im


def img_parking():
    im = Image.new("RGB", (W, H), (110, 112, 116)); d = ImageDraw.Draw(im)
    d.rectangle([0, 580, W, H], fill=(70, 72, 76))
    d.rectangle([380, 20, 660, 640], fill=(180, 182, 185))
    d.rectangle([400, 120, 640, 360], fill=(242, 194, 0))
    d.text((440, 160), "B2", fill=(20, 20, 20), font=font(150))
    d.text((450, 420), "14", fill=(40, 40, 40), font=font(110))
    for x in (0, 760):
        d.line([(x, 760), (x + 260, 620)], fill=(235, 235, 235), width=10)
    return im


def img_car():
    im = Image.new("RGB", (W, H), (40, 60, 90)); d = ImageDraw.Draw(im)
    d.rectangle([0, 470, W, H], fill=(45, 45, 48))
    d.polygon([(140, 520), (190, 420), (330, 400), (440, 310), (640, 300), (760, 400), (900, 420), (920, 520)], fill=(210, 40, 35))
    d.polygon([(460, 325), (630, 318), (720, 400), (430, 405)], fill=(160, 200, 230))
    for x in (300, 760):
        d.ellipse([x - 70, 450, x + 70, 590], fill=(15, 15, 15)); d.ellipse([x - 30, 490, x + 30, 550], fill=(170, 170, 170))
    return im


def img_group():
    im = Image.new("RGB", (W, H), (120, 180, 230)); d = ImageDraw.Draw(im)
    d.rectangle([0, 520, W, H], fill=(90, 160, 80))
    skins = [(236, 188, 150), (198, 134, 90), (241, 200, 170), (160, 110, 75), (230, 180, 140)]
    shirts = [(200, 50, 60), (40, 90, 180), (240, 200, 40), (60, 150, 90), (150, 60, 160)]
    for i in range(5):
        x = 170 + i * 170; y = 330 - (i % 2) * 40
        d.rounded_rectangle([x - 70, y + 70, x + 70, y + 420], 50, fill=shirts[i])
        d.ellipse([x - 55, y - 50, x + 55, y + 70], fill=skins[i])
        d.ellipse([x - 25, y - 5, x - 13, y + 7], fill=(30, 30, 30)); d.ellipse([x + 13, y - 5, x + 25, y + 7], fill=(30, 30, 30))
        d.arc([x - 25, y + 10, x + 25, y + 45], 20, 160, fill=(120, 30, 30), width=5)
    return im


def img_dark():
    rnd = np.random.default_rng(5)
    a = np.clip(rnd.normal(12, 6, (H, W, 3)), 0, 255).astype(np.uint8)
    return Image.fromarray(a).filter(ImageFilter.GaussianBlur(3))


SYNTH = {
    "target": (img_target, "shooting_target"),
    "receipt": (img_receipt, "receipt"),
    "landscape": (img_landscape, "mountains|beach_sunset"),
    "breaker_panel": (img_panel, "breaker_panel|wiring"),
    "screenshot": (img_screenshot, "screenshot"),
    "document": (img_document, "document"),
    "parking_pillar": (img_parking, "parking_spot"),
    "red_car": (img_car, "sports_car|classic_car"),
    "group_cartoon": (img_group, "family_group|friends_group"),
    "dark_frame": (img_dark, "dark_pocket|blurry"),
}


def preprocess(im):
    im = im.convert("RGB")
    w, h = im.size; s = 256 / min(w, h)
    im = im.resize((max(256, round(w * s)), max(256, round(h * s))), Image.BILINEAR)
    w, h = im.size; l, t = (w - 256) // 2, (h - 256) // 2
    im = im.crop((l, t, l + 256, t + 256))
    return (np.asarray(im, dtype=np.float32) / 255.0).transpose(2, 0, 1)[None]


sq = session("vision_model_quantized.onnx"); sf = session("vision_model.onnx")


def embed(s, x):
    return l2n(s.run(["image_embeds"], {"pixel_values": x})[0].astype(np.float64))[0]


ids = [i for i, _, _ in PROMPTS]
results = {}
images = {}
for name, (fn, expect) in SYNTH.items():
    im = fn(); images[name] = im
    im.save(os.path.join(SYN, name + ".png"))
    x = preprocess(im)
    eq, ef = embed(sq, x), embed(sf, x)
    r = {"expected": expect, "int8_vs_fp32_cos": float(eq @ ef)}
    for tag, e in (("int8", eq), ("fp32", ef)):
        cos = Tship @ e
        p = np.exp(LOGIT_SCALE * (cos - cos.max())); p /= p.sum()
        order = np.argsort(-p)
        keep = float(p[kinds == "keep"].sum())
        r[tag] = {
            "top3": [(ids[j], round(float(p[j]), 3), round(float(cos[j]), 4)) for j in order[:3]],
            "p_keep": round(keep, 3),
            "zs_margin": round(keep - (1 - keep), 3),
            "expected_rank": min(int(np.where(order == ids.index(k))[0][0]) + 1 for k in expect.split("|")),
            "cos_drawing_control": round(float((T[nship:] @ e)[0]), 4),
        }
    results[name] = r

# ---------------------------------------------------------------- 3b. near-duplicate cosines
base = img_landscape()
def jpeg(im, q):
    b = io.BytesIO(); im.save(b, "JPEG", quality=q); b.seek(0); return Image.open(b).convert("RGB")
variants = {
    "shift_3pct": base.crop((30, 0, W, H - 22)).resize((W, H)),
    "brightness_0.85": ImageEnhance.Brightness(base).enhance(0.85),
    "jpeg_q50": jpeg(base, 50),
    "rotate_2deg": base.rotate(2, resample=Image.BILINEAR, fillcolor=(0, 0, 0)).crop((20, 20, W - 20, H - 20)),
    "zoom_1.15": base.crop((67, 50, W - 67, H - 50)).resize((W, H)),
    "motion_blur": base.filter(ImageFilter.GaussianBlur(4)),
    "other_sunset_palette": img_landscape(1),
}
# fp32 here: the Xenova INT8 export turned out not to agree with fp32 (see results), so its cosines are meaningless
eb = embed(sf, preprocess(base))
dup = {k: round(float(embed(sf, preprocess(v)) @ eb), 4) for k, v in variants.items()}
names = list(images)
E = np.array([embed(sf, preprocess(images[n])) for n in names])
C = E @ E.T
diff = [float(C[i, j]) for i in range(len(names)) for j in range(i + 1, len(names))]
dup["different_scenes_min_mean_max"] = [round(min(diff), 4), round(float(np.mean(diff)), 4), round(max(diff), 4)]

# ---------------------------------------------------------------- 4. timing
def bench(name, threads, n=30):
    s = session(name, threads); x = preprocess(images["landscape"])
    for _ in range(3): s.run(None, {"pixel_values": x})
    ts = []
    for _ in range(n):
        t0 = time.perf_counter(); s.run(None, {"pixel_values": x}); ts.append((time.perf_counter() - t0) * 1000)
    ts.sort()
    return {"median_ms": round(ts[n // 2], 1), "p90_ms": round(ts[int(n * .9)], 1)}

timing = {f"{m}@{t}thr": bench(m, t) for m in ("vision_model_quantized.onnx", "vision_model.onnx") for t in (1, 4)}
t0 = time.perf_counter()
for _ in range(20): preprocess(images["landscape"])
timing["preprocess_1024x768_ms"] = round((time.perf_counter() - t0) / 20 * 1000, 1)
t0 = time.perf_counter(); session("vision_model_quantized.onnx"); timing["session_create_int8_ms"] = round((time.perf_counter() - t0) * 1000)

summary = {
    "onnxruntime": ort.__version__,
    "padding_check_cos": round(pad_cos, 6),
    "closest_prompt_pairs": [(a, b, round(float(c), 3)) for c, a, b in closest],
    "zero_shot": results,
    "near_duplicates_fp32_cos_to_base": dup,
    "timing_cpu": timing,
}
with open(os.path.join(OUT, "results.json"), "w") as f:
    json.dump(summary, f, indent=1)

# contact sheet with the fp32 verdicts (generated images only)
sheet = Image.new("RGB", (5 * 260, 2 * 300), (18, 18, 20)); d = ImageDraw.Draw(sheet)
for i, n in enumerate(names):
    th = images[n].copy(); th.thumbnail((240, 180))
    x, y = (i % 5) * 260 + 10, (i // 5) * 300 + 10
    sheet.paste(th, (x + (240 - th.width) // 2, y + (180 - th.height) // 2))
    r = results[n]["fp32"]
    d.text((x, y + 188), n, fill=(240, 240, 245), font=font(18))
    d.text((x, y + 212), f"top: {r['top3'][0][0]} {r['top3'][0][1]:.2f}", fill=(255, 159, 10), font=font(15))
    d.text((x, y + 232), f"2nd: {r['top3'][1][0]} {r['top3'][1][1]:.2f}", fill=(154, 154, 162), font=font(15))
    col = (52, 199, 89) if r["zs_margin"] > 0 else (255, 105, 97)
    d.text((x, y + 254), f"keep-junk margin {r['zs_margin']:+.2f}", fill=col, font=font(15))
sheet.save(os.path.join(OUT, "validation-synthetic.png"))

print(json.dumps(summary, indent=1))
