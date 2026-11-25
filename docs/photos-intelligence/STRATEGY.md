# Photos intelligence — strategy for discussion

**Goal.** The Photos view should show photos worth showing (family, groups of people,
pets, landmarks, travel scenery, cool cars, firearms as collectibles/hobby) and skip
"utility" photos: shooting targets, electrical panels and wiring, garage parking-spot
reminders, receipts, screenshots, documents, serial-number labels, whiteboards, product
shots for returns, blurry or accidental shots.

**Constraints.** Sideloaded personal app. No backend. Every image stays on the phone.
Galaxy S26 Ultra (Snapdragon 8 Elite-class NPU, 12–16 GB RAM, One UI 9 / Android 17).
The library holds thousands to tens of thousands of photos. Today
`PhotoLibrary.query()` already excludes the `Screenshots` and `Screen recordings` buckets
and shuffles everything else.

Status: **research and options, nothing built.** Prepared 2026-11-25. Diagram:
[`pipeline.png`](pipeline.png) (source `pipeline.html`).

---

## TL;DR — recommendation

The core of this is a **CLIP-style image embedding, not an LLM**. One embedding per
photo, computed once while the phone is charging, does four jobs: zero-shot keep/junk
scoring, near-duplicate removal, diversity when sampling, and later a tiny personal
classifier trained from your feedback. A small VLM is only worth adding as an optional
**judge for the uncertain 5–10 %**.

| Tier | What | Cost per photo (S26U, estimate) | Role |
|---|---|---|---|
| 0 | **Metadata heuristics**: bucket/folder, MIME, size, EXIF camera make, burst/time gaps | ~0 (one MediaStore query) | Drops screenshots, downloads, messaging images, tiny files before any ML |
| 1 | **Image embedding**: MobileCLIP2-S0 (or S2) image encoder only, INT8, on a 256 px MediaStore thumbnail | ~5–30 ms | Zero-shot score against ~30 text prompts whose vectors are **precomputed on a PC and shipped as a small table** |
| 1b | **ML Kit Face Detection** (+ optional Text Recognition only for "document-ish" candidates) | ~10–40 ms | People count and face size; OCR text density confirms receipts, labels and documents |
| 2 | **Optional SLM judge** for the uncertain band: Gemini Nano via ML Kit Prompt API (foreground only), or Gemma 4 E2B / Gemma 3n via LiteRT-LM | ~1–5 s (assumed) | One-word verdict, plus an optional short caption |
| 3 | **Personal head**: logistic regression on embeddings, trained from your labels ("never show", "more like this", review grid) | µs | After ~100–300 labels it replaces most hand-tuned prompts and thresholds |

Rough budgets (details in §5). **APK +12–25 MB** for tiers 0–1b (the SLM is a separate
optional 1.5–3 GB file). Indexing 20 k photos takes **~15–45 min on charge**.
Index database ~25–40 MB. Battery is effectively zero, because indexing only runs on the
charger.

Target before you trust it: **≥ 95 % precision on "show"** (≤ 1 junk photo in 20 shown),
with **≥ 70 % recall** of good photos, measured on 200–300 of your own photos that you
label (§4).

---

## 1. Candidate on-device approaches

Legend: **[V]** verified from a cited source · **[A]** assumption or estimate, to be
measured on the S26 Ultra.

### 1a. ML Kit Image Labeling + Face Detection (cheap signals)

- **Labeling:** the base model has **400+ labels** [V]. Bundled it adds **~5.7 MB**; the
  Play-services (unbundled) variant adds ~200 KB [V] ([ML Kit Image Labeling, Android](https://developers.google.com/ml-kit/vision/image-labeling/android)).
  There are no official latency figures [V]; ~10–30 ms on a 2026 flagship [A].
- **Fit for this task:** weak as the main classifier. Its fixed vocabulary has generic
  labels such as "Vehicle", "Pet", "Sky", "Building", "Paper" and "Receipt"-ish
  concepts, but no labels for "electrical panel", "shooting target", "parking-pillar
  number" or "serial-number sticker" [A, from the label list's nature]. It overlaps
  heavily with what an embedding gives for free, so I'd **skip labeling**, or use it only
  as a debug and explanation signal.
- **Face Detection:** cheap and precise for "contains people / how many / how big". The
  bundled model is a few MB [A]. It is the best single feature for "family / group of
  people". Use it as a feature (face count, largest face area), not as a gate: pets,
  cars and landscapes have no faces.
- **Text Recognition (Latin):** bundled (~4 MB [A]). Text area per image area is an
  excellent signal for receipts, documents, labels, whiteboards and screenshots. Run it
  **only on candidates** the embedding already finds document-like, to save time.
- **Licence:** ML Kit terms (free, closed models) [V-general]. No network is needed with
  the bundled variants.

### 1b. Zero-shot image–text embedding models (the core)

How it works: encode each photo to a vector. Encode text prompts such as *"a photo of an
electrical breaker panel"*, *"a family photo"* and *"a paper shooting target with bullet
holes"* with the text encoder, then compare by cosine similarity. **Key simplification:**
the prompts are fixed, so their vectors can be computed **once on a PC** and shipped as
a ~30 × 512 float table. **The text encoder never ships**, and it is the larger half of
every model below. Ship it only if you later want free-text "search my photos".

| Model | Image encoder params | Quantised image-encoder file | Zero-shot IN-1k | Published latency | Licence |
|---|---|---|---|---|---|
| **MobileCLIP2-S0** | 11.4 M [V] | ~11 MB INT8 ONNX (3rd-party export) [V] | 71.5 % [V] | 1.5 ms image enc. on iPhone 12 Pro Max class [V] | Code MIT; **weights under Apple ML Research Model TOU** [V] |
| MobileCLIP2-S2 | 35.7 M [V] | ~35–40 MB INT8 [A] | 77.2 % [V] | 3.6 ms [V, Apple device] | same |
| MobileCLIP2-B | 86.3 M [V] | ~90 MB INT8 [A] | 79.4 % [V] | 10.4 ms [V, Apple device] | same |
| **SigLIP 2 B/16-224** | ~86 M [A] | **92.8 MB w8a16** (Qualcomm AI Hub) [V] | ~78 % [A] | **2.9 ms w8a16 on Snapdragon 8 Elite NPU** (image encoder) [V] | **Apache-2.0** [V] |
| OpenAI CLIP ViT-B/16 | ~86 M | ~90 MB [A] | 68 % | ~7–9 ms whole model, 8 Elite (for Galaxy) NPU TFLite [V] | MIT |

Sources: [apple/ml-mobileclip](https://github.com/apple/ml-mobileclip) (variants, licence),
[Xenova/mobileclip_s0 ONNX](https://huggingface.co/Xenova/mobileclip_s0/discussions/1/files),
[felixhrdyn/mobileclip2-s0-onnx](https://huggingface.co/felixhrdyn/mobileclip2-s0-onnx),
[qualcomm/SigLIP2](https://huggingface.co/qualcomm/SigLIP2) and [AI Hub SigLIP2](https://aihub.qualcomm.com/mobile/models/siglip2)
(note: the AI Hub page contradicts itself on mobile support),
[qualcomm/OpenAI-Clip](https://huggingface.co/qualcomm/OpenAI-Clip).

Notes:
- **Runtime.** Three options:
  - ONNX Runtime Mobile (CPU/XNNPACK, or the QNN EP for the NPU). Easiest, because ONNX
    exports of MobileCLIP already exist.
  - LiteRT `CompiledModel` with GPU/NPU (Qualcomm and MediaTek listed) [V] ([LiteRT Android](https://developers.google.com/edge/litert/android)).
    This needs a PyTorch → `.tflite` conversion (ai-edge-torch).
  - MNN, which the PocketSearch demo uses [V] ([Zvec on Mobile](https://zvec.org/en/blog/2026-06-22-zvec-mobile/)).

  For a batch job on the charger, **CPU INT8 at ~20–40 ms per image is good enough**
  [A], so the NPU is an optimisation, not a requirement.
- **Precedent:** open-source Android apps already do offline CLIP photo indexing with
  ONNX Runtime: [TIDY](https://github.com/slavabarkov/tidy) and
  [PicQuery](https://github.com/greyovo/PicQuery) (8 k+ photos, search in under 1 s) [V].
- **Accuracy for this task [A]:** CLIP-family models are strong on "document / receipt /
  screenshot / whiteboard / text-heavy", "pet", "car", "landscape", "group of people"
  and "firearm". Expect trouble with:
  - fine-grained **utility vs memory** cases: a car in a parking garage could be the
    "cool car" or the "where did I park" photo; a rifle on a bench could be the hobby
    shot or the "serial number for the form" shot;
  - **shooting targets** versus target-themed art;
  - **wiring** inside a nice old house.

  That is exactly where the personal head (§3) and the optional judge help.
- **Choice:** start with **MobileCLIP2-S0** (tiny; fast on CPU; S2 is an easy upgrade).
  If the Apple weights TOU worries you, use **SigLIP 2 B/16 (Apache-2.0)** at ~93 MB.
  For a personal, non-distributed app the Apple terms are very likely a non-issue [A],
  but read them yourself.
- **MediaPipe Image Embedder** with its default MobileNet-V3 is fast (~4–10 ms) but is
  **image-only** (no text side), so it cannot do zero-shot. It is useful only for
  duplicates [V] ([MediaPipe Image Embedder](https://developers.google.com/edge/mediapipe/solutions/vision/image_embedder)).

### 1c. Small multimodal LMs (the "SLM judge")

**Gemini Nano via ML Kit GenAI Prompt API**
- Image + text prompts are supported; Google's own example is categorising an image into
  a fixed set and *"identifying tags such as pets, food, travel"* [V]
  ([Prompt API alpha blog, Oct 2025](https://developer.android.com/blog/posts/ml-kit-s-prompt-api-unlock-custom-on-device-gemini-nano-experiences.md.txt)).
- **Galaxy S26 / S26+ / S26 Ultra appear in the Prompt API device list.** Cached copies
  disagree on nano-v2 vs nano-v3 [V-partial] ([ML Kit GenAI overview](https://developers.google.com/ml-kit/genai#device-support)).
  Check on the phone with `getBaseModelName()` / `checkStatus()`.
- Gemma 4 E2B/E4B are in the **AICore Developer Preview** and are the base for
  "Gemini Nano 4" [V]
  ([Android blog, Apr 2026](https://developer.android.com/blog/posts/announcing-gemma-4-in-the-ai-core-developer-preview.md.txt)).
- **Hard limits [V]** ([ML Kit GenAI overview](https://developers.google.com/ml-kit/genai)):
  - inference only when the app is the **top foreground app**; a foreground service gets
    `BACKGROUND_USE_BLOCKED`;
  - a per-app inference quota (`BUSY`) and a long-duration **battery quota**
    (`PER_APP_BATTERY_USE_QUOTA_EXCEEDED`), with no published numbers.
- **Nightstand-specific opportunity [A]:** the bedside Activity *is* the top foreground
  app while it shows on the charger all night. So Nano could judge a few hundred
  uncertain photos per night while the display runs. Unknowns: whether an Activity shown
  over the lock screen counts as "top" for AICore, and how big the daily quota is.
  Thermal limits apply too (phone on a charger, screen on).
- Licence: no model to ship (0 MB APK). The trade-off is that you depend on AICore and
  Samsung's rollout.

**Self-hosted VLM (LiteRT-LM / MediaPipe LLM Inference / llama.cpp / MLC)**
- **Gemma 4 E2B**: Apache-2.0, multimodal, **~2.6 GB** LiteRT-LM file; decode **~52
  tok/s on the GPU of a Galaxy S26 Ultra** (LiteRT-LM figure, as quoted in search
  results) [V-secondary] ([Gemma 4 announcement](https://blog.google/innovation-and-ai/technology/developers-tools/gemma-4/),
  [LiteRT-LM Gemma 4](https://ai.google.dev/edge/litert-lm/models/gemma-4)).
- **Gemma 3n E2B**: MobileNet-V5 vision encoder, **256 tokens per image** [V]
  ([Gemma 3n developer guide](https://developers.googleblog.com/en/introducing-gemma-3n-developer-guide/)).
- **SmolVLM2 256M/500M**: tiny, but ~5–7 s per image on phones in one third-party report
  [V-weak], and weak at fine distinctions [A].
- **Qwen2.5-VL-3B / MiniCPM-V**: better OCR, but 2–3 GB+ and slower on llama.cpp; their
  licences vary (Qwen 3B has a research licence [A]).
- **Per-image cost [A]:** image encode plus ~256-token prefill plus ≤5 output tokens is
  about **1–3 s on the GPU** for E2B-class models. That is ~1 h per 2 k uncertain photos.
  Fine for the uncertain band, too slow and heavy for 20 k photos.
- **Advantage over Nano:** a self-hosted model runs **in a background job while
  charging**, without AICore foreground or quota rules. RAM (~3–4 GB) is fine on
  12–16 GB.
- **Sideloading:** a 2.6 GB file should not go inside the APK. Download it on demand
  (needs network once; INTERNET is already declared for weather) or `adb push` it into
  `getExternalFilesDir()`.

**Verdict on SLMs:** they are good at "is this a utility photo or a memory?" and can
explain themselves, but they are 100–1000× more expensive per image than an embedding,
and the output is text you have to parse. Use one as **tier 2 only**, after the
embedding has settled the easy 90 %.

### 1d. Cheap heuristics (tier 0 and features)

| Signal | How | Catches |
|---|---|---|
| Bucket / RELATIVE_PATH | `Screenshots`, `Screen recordings`, `Download`, `WhatsApp/…Images`, `Telegram`, `Messenger`, `Pictures/Instagram` (configurable list) | screenshots, memes, forwarded images (already partly done) |
| MIME / size | PNG from a non-camera bucket, < 0.3 MP, or extreme aspect ratio | screenshots, stickers, icons |
| EXIF | Has `Make`/`Model` = Samsung and an exposure time → camera photo; no EXIF → downloaded or edited | downloads |
| Capture metadata | Samsung "Document scan" / Bixby Vision / Object Eraser outputs, if they land in their own folders [A] | documents |
| Blur / exposure | Variance of Laplacian on the 256 px thumbnail; mean and percentile luminance | pocket shots, dark frames |
| Bursts / near-duplicates | Same 60 s window and embedding cosine > 0.95 (or a 64-bit dHash) → keep the sharpest, mark the rest `dup` | bursts, "5 tries at the same group shot" |
| GPS | Distance from your home cluster > N km → "travel" boost; at home plus a document-like embedding → stronger junk | travel scenery vs home utility |
| Time | Photos taken 30 s apart at the same place over a whole day are an event; single photos at 2 pm on a weekday at home lean utility [A, weak] | diversity |

---

## 2. Pipeline design

```
MediaStore ─► Tier 0 metadata filter ─► thumbnail (loadThumbnail 256 px)
   ─► embedding (MobileCLIP2-S0 INT8) ─► faces (ML Kit) ─► [doc-like? → OCR density]
   ─► score = f(zero-shot, faces, heuristics, personal head) ─► KEEP / UNSURE / JUNK
   UNSURE ─► (optional) SLM judge ─► final
Slideshow: KEEP pool → diversity + recency/"on this day" sampler
```

**Scheduling.**
- Use a `WorkManager` periodic or expedited chain with `setRequiresCharging(true)` and
  `setRequiresBatteryNotLow(true)`, and optionally `setRequiresDeviceIdle(true)` for the
  first big run. Each run does batches of ~200 photos and checkpoints in the DB, so a
  stop costs nothing.
- Charging exempts jobs from standby-bucket limits. Android 16 adds runtime quotas for
  jobs that run alongside a foreground service, which Nightstand has (the watcher) [V]
  ([Android 16 behaviour changes](https://developer.android.com/about/versions/16/behavior-changes-all),
  [power details](https://developer.android.com/topic/performance/power/power-details)).
  So keep each job short (≤ 10 min per batch) and let WorkManager re-enqueue.
- One UI "Put unused apps to sleep" / "Deep sleeping apps" can starve jobs [A]. Nightstand
  should already be on the "Never sleeping" list for its watcher service.
- **Bonus window:** while the Nightstand display is up on the charger, the app is in the
  foreground anyway. Indexing can run in-process at low priority, but pause it if
  `PowerManager.getThermalStatus() ≥ MODERATE`.

**Incremental.**
- Store the MediaStore `_ID`, `DATE_MODIFIED` and `GENERATION_MODIFIED` (API 30+), and
  query `GENERATION_MODIFIED > lastGen` for new or changed photos.
- `MediaStore.getVersion()` changing means a full rescan of IDs (not of embeddings:
  match them by content hash).
- A `ContentObserver` on images enqueues a small job.

**Thumbnails.** `ContentResolver.loadThumbnail(uri, Size(256,256))` uses MediaProvider's
cached thumbnail, which is far cheaper than decoding a 50–200 MP original [A]. Then
center-crop or pad to 224/256 for the model.

**Storage (Room/SQLite), one row per photo:**
`media_id, content_hash(dHash64), date_taken, bucket, w, h, mime, has_exif_camera,
lat/lon (rounded), blur, luma, faces, max_face_frac, text_frac, embedding BLOB
(512×fp16 = 1 KB), zs_scores (top classes), verdict, verdict_source
(heuristic|zeroshot|judge|user|head), user_label, judge_caption, model_version,
indexed_at`.
- 20 k photos is ~25–40 MB, mostly embeddings.
- `model_version` lets you re-score from the stored embeddings when only prompts or the
  head change (milliseconds), and re-embed only when the encoder changes.

**Scoring (v1, zero-shot):**
- Two prompt families: KEEP (~12 classes) and JUNK (~15 classes). Each class has 3–5
  prompt templates averaged.
- `p = softmax(100·cos(img, classes))`; `keep = Σp_keep`, `junk = Σp_junk`.
- **KEEP** if `keep − junk > τ_hi` and blur and exposure are OK. **JUNK** if
  `junk − keep > τ_lo` or a tier-0 rule fires. Everything else is **UNSURE**.
- A face boost: faces ≥ 2, or the largest face ≥ 3 % of the frame → keep.
- A text-density penalty: text_frac > 15 % → junk unless faces are present.

Proposed class list (tunable; examples in English, embedded offline):
- KEEP: family portrait · group of friends · child · selfie · pet dog / pet cat ·
  landmark / monument · city skyline · mountain / beach / forest scenery · sunset ·
  sports or classic car · motorcycle · firearm collection / rifle at the range /
  handgun on display · food at a restaurant (?) · wedding / party / celebration
- JUNK: shooting paper target with bullet holes · electrical breaker panel · exposed
  wiring / outlet · parking garage pillar with level/number sign · receipt · document /
  form / letter · screenshot of a phone screen · serial-number / barcode label ·
  whiteboard with writing · product in its box / on a table for a return · price tag in
  a store · computer monitor photo · car dashboard warning lights · broken item / damage
  close-up · blurry accidental photo

**Slideshow sampling** (replaces today's `shuffled()`):
1. Pool = KEEP (plus UNSURE only if the pool is small and you choose so), minus "never
   show", minus duplicates.
2. Weight = `quality × novelty × timeliness`:
   - quality: the score margin plus face and sharpness boosts;
   - novelty: down-weight photos shown recently, and photos whose embedding is close
     (cos > 0.85) to anything in the last ~20 shown, so one event doesn't take over the
     night;
   - timeliness: ×2–3 for "on this day" (±3 days in earlier years), and a mild recency
     boost for the last 30 days.
3. Draw with weighted reservoir sampling; persist the "recently shown" ring (already
   planned in `PhotoPlaylist`).

**Memory and thermal.**
- Peak RAM for tier 1 is ~100–200 MB [A]. A Gemma E2B judge needs ~3–4 GB [A]; load it
  only in its own job, never while the display is running.
- Watch the thermal status and back off. A bedside phone in a case on a Qi2 pad is
  already warm from charging, and wired charging is preferable for long jobs.

---

## 3. Personalisation

- **Cold start:** the zero-shot prompts above. Nothing to train.
- **On the display:** a long-press on the photo panel is currently unused. Show a small
  two-button overlay, **"Hide this photo"** and **"More like this"**, that disappears
  after 4 s. At night it should be red and minimal, or disabled.
  - "Hide" sets `user_label = junk` and skips the photo immediately.
  - "More like this" sets `user_label = keep` and temporarily boosts its embedding
    neighbours (kNN, cos > 0.8).
- **In settings ("Photo review"):** a grid of the **most uncertain** photos (active
  learning: |keep − junk| smallest), with tap for keep, swipe for junk, ~50 at a time.
  Also a "Hidden" tab to undo, and a "Why?" line per photo (top class, faces, text %,
  verdict source).
- **Personal head:** an L2-regularised logistic regression on `[embedding (512),
  faces, text_frac, blur, isCameraExif, …]`.
  - Plain Kotlin gradient descent: < 100 ms for 1 k samples, so no ML framework is
    needed.
  - Initialise the weights from the zero-shot direction (mean KEEP text vector − mean
    JUNK text vector), so it starts no worse than zero-shot.
  - Retrain after every ~20 new labels. Blend it with zero-shot by label count (e.g.
    weight `n/(n+100)`).
- **Hard rules beat the model:** "Never show" is permanent; a folder blocklist and
  allowlist in settings (e.g. an always-show "Family" album).
- **Category toggles** in settings ("show firearms", "show cars", "show food"),
  implemented as per-class keep/junk switches on the zero-shot classes, so "cool
  weapons" is your explicit choice, not the model's.

---

## 4. Evaluation plan (before trusting it)

1. **Build a labelled set from your own library.**
   - Pick a stratified random sample of **300 photos**: 100 that the model calls KEEP,
     100 UNSURE, 100 JUNK, from all years and buckets.
   - Label them in the review grid **blind** (no model verdict shown) as keep / junk /
     don't-care.
   - That takes ~10 min with swipe-to-label. Store the labels as `user_label` with an
     `eval=1` flag, so the head never trains on them.
2. **Metrics on the "show" decision:**
   - **Precision@show:** of the photos it would show, how many you'd want. Target
     **≥ 95 %**. With n ≈ 100 shown, the 95 % CI is roughly ±4 %, so 300 labels is the
     minimum and 500 is better.
   - **Recall@show:** of your good photos, how many survive. Target ≥ 70 %; with
     thousands of good photos, losing some is acceptable.
   - Per-junk-class confusion: which kinds leak? (Targets? Garage?)
   - Re-weight by the stratum sizes to estimate library-wide numbers.
3. **Pick the thresholds** τ_hi/τ_lo on this set to hit the precision target, and report
   what fraction of the library ends up UNSURE. That fraction is the SLM judge's
   workload.
4. **Compare variants on the same set:** heuristics only → + zero-shot S0 → S2 / SigLIP2
   → + faces/OCR → + judge on UNSURE → + personal head (5-fold CV on non-eval labels).
   Ship the cheapest variant that meets the target.
5. **Live check:** a debug counter for "hidden from the display" per 100 shown. If you
   hide more than ~3 per 100, something regressed.

Do this offline first (optional but fast):
- Copy ~500 thumbnails to a PC (`adb pull`) and run the same ONNX model in Python to
  iterate on prompts in minutes.
- Then ship the frozen prompt table to the phone.
- The photos stay on your own machines, and nothing leaves your control.

---

## 5. Rough budgets

| Item | Size / time | Basis |
|---|---|---|
| MobileCLIP2-S0 image encoder INT8 | ~11 MB | [V] 3rd-party ONNX export |
| (alt) SigLIP 2 B/16 image encoder w8a16 | ~93 MB | [V] Qualcomm AI Hub |
| Prompt vector table | < 100 KB | [A] 30–60 × 512 fp16 |
| ONNX Runtime Android (arm64 only) | ~6–8 MB | [A] |
| ML Kit face + text (bundled) | ~6–10 MB | [A] |
| **APK delta tiers 0–1b** | **~25 MB** (S0) / ~110 MB (SigLIP2) | sum; sideloaded, so no Play size limit applies |
| Optional SLM file (Gemma 4 E2B) | ~2.6 GB, downloaded or pushed separately | [V] LiteRT-LM listing |
| Per photo, tier 0–1b | thumbnail 5–15 ms + embed 5–30 ms + faces 10–20 ms (+OCR 50–100 ms on ~15 %) ≈ **40–80 ms** | [A] |
| **First full index, 20 k photos** | **~15–30 min** CPU; ≤ 45 min with throttling | [A] |
| Judge on 5–10 % UNSURE (1–2 k) | ~0.5–2 h with self-hosted E2B; spread over nights with Nano | [A] |
| New photos per day (~20–50) | seconds | [A] |
| DB | ~25–40 MB for 20 k | [A] |
| Battery | ~0 off-charger; on the charger a few % of the charge energy for the first index | [A] |

Bundling vs download: Play Asset Delivery isn't available to a sideloaded APK. **Bundle
tiers 0–1b** (~25 MB is trivial), keeping the "no network for photos" story intact.
Ship the SLM as an **opt-in download** with checksum verification, or as an `adb push`
for the purist path. Build arm64-v8a only.

---

## 6. Options at a glance

| Option | Quality (est.) | Effort | APK | Risk |
|---|---|---|---|---|
| A. Heuristics only (folders, EXIF, blur, dups) | Removes ~40–60 % of junk (screenshots and downloads), misses panels, targets, receipts taken with the camera | 1 day | 0 | low |
| B. **A + embedding zero-shot + faces/OCR** ✅ | Likely ≥ 90 % precision out of the box, ≥ 95 % after threshold tuning [A] | 3–5 days | +25 MB | prompt tuning |
| C. **B + personal head + review UI** ✅ | Best long-term: learns *your* "cool car" vs "parking reminder" | +2–3 days | 0 | needs ~100–300 labels |
| D. B/C + SLM judge for UNSURE | +1–3 pp on the hard band, plus captions | +2–4 days | 0 / +2.6 GB file | AICore limits or file size; thermal |
| E. SLM for every photo | Good, but slow (10+ h for 20 k), heavy, and parsing text output | 3 days | +2.6 GB | battery/thermal, nondeterminism |
| F. ML Kit labeling only | Coarse; can't express the junk classes | 1 day | +6 MB | low ceiling |

**Recommendation:** build **B, then C**, and measure (§4). Add **D** only if the
measured UNSURE band is large or the hard-class confusion stays high. With your earlier
SLM experience, D is cheap to try once the pipeline and DB exist, because the judge is
just another `verdict_source`.

---

## 7. Risks

- **Prompt brittleness:** zero-shot on CLIP-style models is sensitive to wording.
  Mitigate with prompt ensembles, offline iteration on your thumbnails, and the personal
  head.
- **Context-dependent classes:** "car in a garage" and "rifle on a bench" can be memory
  or utility. Expect a residual error that only your feedback fixes.
- **Gemini Nano availability** on the S26U (nano-v2 vs v3 vs Nano 4), the foreground-only
  rule, quotas, and the Activity over the lock screen possibly not counting as "top".
  All have to be tested on the device.
- **One UI background limits** delaying jobs; the thermal state on a Qi2 pad overnight.
- **Licence:** MobileCLIP weights are under the Apple ML Research Model TOU. Fine for
  personal use [A]; SigLIP 2 (Apache-2.0) is the clean alternative.
- **Privacy:** none of this needs network. If you opt in to the SLM download, the only
  outbound call is that one model download.

---

## 8. Questions for you

1. **Your previous SLM setup:** which model and stack (llama.cpp / MLC / MediaPipe /
   Ollama on a PC?), what task, what worked and what didn't? Would you be happy with
   embeddings plus a small head, with an SLM only as judge, or do you want the SLM as the
   main classifier?
2. **APK and storage budget:** is ~25 MB extra fine? Is ~110 MB (SigLIP 2, Apache) fine?
   Would you accept a ~2.6 GB optional model file (download or `adb push`)?
3. **Firearms:** always keep, or keep only "collection / display / range day" while
   "serial-number close-up for paperwork" counts as junk? Should it be a visible toggle?
4. **Cars:** any car, or only "cool" ones (sports, classic, show)? Is your own daily car
   in a parking garage always junk?
5. **Grey zone:** food, pets at the vet, kids' drawings, house/renovation progress,
   memes, scanned old family photos: keep or junk?
6. **Review tolerance:** one 10-minute labelling session (300 photos) up front, then
   occasional "uncertain" batches of ~50? Or only the in-display long-press?
7. **When unsure:** show (risking junk) or hide (risking missing memories)? This sets
   the precision/recall trade-off (default: hide).
8. **Library facts:** roughly how many photos are on the phone, which folders matter
   (DCIM/Camera only, or also WhatsApp etc.), and are many photos cloud-only (Google
   Photos "Free up space", which MediaStore can't see)?
9. **Timeliness:** do you want "on this day" and "recent trip" boosts, or pure random
   among the good ones?
10. **Gemini Nano:** OK to depend on Google AICore (closed model, foreground-only), or
    keep everything in models you control (LiteRT/ONNX files in the APK)?

---

## Sources

- [apple/ml-mobileclip (variants, latency, licences)](https://github.com/apple/ml-mobileclip)
- [Xenova/mobileclip_s0 ONNX quantised weights](https://huggingface.co/Xenova/mobileclip_s0/discussions/1/files) · [felixhrdyn/mobileclip2-s0-onnx](https://huggingface.co/felixhrdyn/mobileclip2-s0-onnx)
- [qualcomm/SigLIP2 (sizes, 8 Elite latency)](https://huggingface.co/qualcomm/SigLIP2) · [Qualcomm AI Hub SigLIP2](https://aihub.qualcomm.com/mobile/models/siglip2) · [google/siglip2-base-patch16-224 (Apache-2.0)](https://huggingface.co/google/siglip2-base-patch16-224)
- [qualcomm/OpenAI-Clip (8 Elite timings)](https://huggingface.co/qualcomm/OpenAI-Clip)
- [ML Kit Image Labeling (400+ labels, 5.7 MB bundled)](https://developers.google.com/ml-kit/vision/image-labeling/android)
- [ML Kit GenAI overview (device list, foreground-only, quotas)](https://developers.google.com/ml-kit/genai)
- [ML Kit Prompt API alpha, image classification use case](https://developer.android.com/blog/posts/ml-kit-s-prompt-api-unlock-custom-on-device-gemini-nano-experiences.md.txt)
- [Gemma 4 in AICore Developer Preview (Apr 2026)](https://developer.android.com/blog/posts/announcing-gemma-4-in-the-ai-core-developer-preview.md.txt) · [Gemma 4 announcement](https://blog.google/innovation-and-ai/technology/developers-tools/gemma-4/) · [LiteRT-LM Gemma 4](https://ai.google.dev/edge/litert-lm/models/gemma-4)
- [Gemma 3n developer guide (MobileNet-V5, 256 tokens/image)](https://developers.googleblog.com/en/introducing-gemma-3n-developer-guide/)
- [LiteRT on Android (CompiledModel, NPU)](https://developers.google.com/edge/litert/android) · [MediaPipe Image Embedder](https://developers.google.com/edge/mediapipe/solutions/vision/image_embedder)
- [Android 16 behaviour changes (job quotas)](https://developer.android.com/about/versions/16/behavior-changes-all) · [Power management details](https://developer.android.com/topic/performance/power/power-details)
- Precedents: [TIDY](https://github.com/slavabarkov/tidy) · [PicQuery](https://github.com/greyovo/PicQuery) · [Zvec PocketSearch (MobileCLIP on MNN)](https://zvec.org/en/blog/2026-06-22-zvec-mobile/)
- SmolVLM phone latency (weak, third-party): [off-grid-mobile-ai benchmarks](https://www.mintlify.com/alichherawalla/off-grid-mobile-ai/technical/performance-benchmarks)

**Research caveat:** the sandbox could not open developers.google.com, ai.google.dev,
huggingface.co or the Google Developers Blog directly. Figures from those pages come from
search-result extracts and are marked [V] only where the extract was explicit. All
S26 Ultra latency figures except the Gemma 4 decode rate are **estimates to measure in a
1-day spike**: one benchmark Activity that times thumbnail, embedding, faces and OCR over
500 photos.
