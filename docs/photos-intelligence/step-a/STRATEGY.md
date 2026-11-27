# Photos intelligence, Step A: lazy scoring, swipe-to-teach, one per burst

Status: **built in v0.8.0** (branch `photos-step-a`) with the user's decisions of 2026-11-26: LiteRT from
Play services instead of ONNX Runtime, model downloaded on demand (not in the APK), look-alike radius as a
setting, no swipe-to-hide at night, one swipe = junk, prompt table v2 (firearms keep; food, memes,
drawings junk), background scoring ≤ 300/night. Model build, validation, size and SHA-256:
[`tflite/README.md`](tflite/README.md). The rest of this document is the original strategy.
Builds on [`../STRATEGY.md`](../STRATEGY.md) (research) and feeds
[`../step-b/DESIGN.md`](../step-b/DESIGN.md) (the trained classifier, later).

Mockups: [`mockup.html`](mockup.html) → [`overview.png`](overview.png),
[`a-photo-swipe-day.png`](a-photo-swipe-day.png), [`a-photo-swipe-night.png`](a-photo-swipe-night.png),
[`b-photos-options.png`](b-photos-options.png), [`c-hidden-photos.png`](c-hidden-photos.png),
[`d-why-shown.png`](d-why-shown.png). Model check: [`validate.py`](validate.py) and the other scripts,
[`validation/`](validation/), [`validation-synthetic.png`](validation-synthetic.png),
[`prompt_embeddings.json`](prompt_embeddings.json).

Legend: **[M]** measured in this sandbox (4-vCPU Xeon, Python, ONNX Runtime 1.32 CPU) ·
**[V]** checked against a file or registry · **[A]** assumption or estimate, to measure on the S26 Ultra.

---

## TL;DR

- **One step, three behaviours.** A frozen **MobileCLIP-S0 image encoder** turns each photo into a
  512-d vector. That vector gives:
  1. a **zero-shot keep/junk score**, from 32 prompt vectors computed once on a PC and bundled
     (the 170 MB text model never ships);
  2. **swipe-to-teach**: swipe down on the photo and its look-alikes are hidden at once, and a
     tiny per-user logistic head is refitted in about 0.1 s;
  3. **one per burst**: photos seconds apart that look alike are folded, and only the best is
     shown.
- **Lazy, not a batch job.** A photo is scored just before it could be shown: a 3-deep look-ahead
  queue, on one background thread, only while the Photos page is visible and the phone is
  charging. Embedding and score go into SQLite, so each photo is scored **once**. Step B gets
  everything it needs from the same tables: embeddings, labels and burst groups.
- **Validated here [M]:**
  - **fp32 zero-shot** picked the right prompt for **10 of 10** generated test images: target,
    receipt, landscape, breaker panel, screenshot, document, parking pillar, car, group,
    dark frame.
  - **The INT8 file that was downloaded (`vision_model_quantized.onnx`, 11.8 MB) is broken.**
    Its embeddings have cosine **0.04–0.18** with fp32 and **0 of 20** top-1 matches, and it
    is 2× *slower* on CPU. Do not ship it.
  - A **weight-only INT8** re-export made here (12.9 MB) agrees with fp32 at cosine
    **0.985–0.998** (20/20 top-1). An **fp16** export (22.9 MB) agrees at **0.9999**.
  - Speed: **~47 ms per photo on one Xeon core** in fp32 (**22 ms** on 4 threads with a quiet
    CPU).
- **ONNX Runtime Android [V]:** latest is **1.32.0** (2026-11-13). **Since 1.29.0 the AAR
  auto-starts a telemetry ContentProvider** that posts to `mobile.events.data.microsoft.com`.
  - Recommendation: **pin 1.28.0**, arm64-only. That adds 28.7 MB uncompressed, or ~10.6 MB
    compressed.
  - Expected APK: **~1.9 MB → ~26 MB** (compressed native libs) or **~44 MB** (the default
    uncompressed packaging).

---

## 1. What the user asked for (the brief, kept intact)

| Direction | How Step A does it |
|---|---|
| Embeddings + personal learning in one step | MobileCLIP-S0 image encoder → 512-d; zero-shot prior from bundled prompt vectors + a residual logistic head on the same vector (§5) |
| "Scored in line of execution and persisted" | Look-ahead queue fills just ahead of display; embedding (fp16, 1 KB) + zero-shot margin stored per photo, never recomputed unless the encoder changes (§4, §6) |
| "Swipe out what I don't like → lesson learned, similar ones no longer shown" | Swipe **down on the photo pane** → label + hide burst + hard-suppress cos ≥ 0.92 neighbours + soft penalty 0.80–0.92 + head refit; Undo toast (§7) |
| "Skip duplicates made in succession, show only one" | Burst chains by capture-time gap + cosine; best of group shown (§8) |
| Step B later; A stores what B needs | `photo.embedding`, `label` (B's exact columns), `dup_group`, `online_head`, `exposure` (§4) |

---

## 2. Validation done here

Scripts are in this folder: [`inspect_models.py`](inspect_models.py), [`prompts.py`](prompts.py),
[`validate.py`](validate.py), [`requantize.py`](requantize.py), [`bench.py`](bench.py) and
[`render.js`](render.js) (mockups).
- They ran with `python -I` from a separate scripts folder and a venv
  (`onnxruntime 1.32.0, tokenizers 0.23.3, numpy, pillow`, plus `onnx` and
  `onnxconverter-common` for re-export).
- The downloaded model folder was only read; nothing from it was executed.
- Raw outputs are in [`validation/`](validation/).

### 2.1 The model files [M]

The model is `Xenova/mobileclip_s0`, the ONNX export of Apple's **MobileCLIP-S0 (v1)**. It is not
MobileCLIP**2**-S0 as named in `../STRATEGY.md`. The pipeline is identical, and S2/v2 is a later
drop-in that needs new prompt vectors.

| File | Size | Input | Output |
|---|---|---|---|
| `vision_model.onnx` (fp32, opset 12) | 45.5 MB | `pixel_values` float `[batch, 3, 256, 256]` | `image_embeds` float `[batch, 512]` |
| `vision_model_quantized.onnx` (dynamic INT8: 95 `ConvInteger`, 5 `MatMulInteger`) | 11.8 MB | same | same |
| `text_model.onnx` (fp32) | 169.8 MB | `input_ids` int64 `[batch, seq]`, **seq must be 77** (fixed positional table; 11 tokens fails) | `text_embeds` float `[batch, 512]` |

Preprocessing matches `preprocessor_config.json`:
- convert to RGB;
- resize the shortest side to 256 (bilinear);
- centre-crop 256×256;
- scale by 1/255, with **no mean/std normalisation**;
- lay out as NCHW. Outputs are not normalised, so the app must L2-normalise them.

**Tokenizer gotcha [M].** Prompts must be padded to 77 tokens with id **0** (`"!"`), as OpenCLIP
does. Padding with `<|endoftext|>` instead changes the vector (cos 0.52), because the S0 text
encoder is not purely causal. This only matters for regenerating `prompt_embeddings.json`. The
app never tokenises.

### 2.2 Prompt table (what the app bundles) [M]

[`prompt_embeddings.json`](prompt_embeddings.json) (160 KB):
- **32 prompts**: 16 keep and 16 junk, each embedded as `"a photo of {…}."`;
- L2-normalised, 512-d;
- `logit_scale` 100;
- SHA-256 of the model files it belongs to;
- a precomputed keep−junk direction.

The app should ship it as a 32 KB fp16 binary generated at build time, or parse the JSON once.

| Keep | Junk |
|---|---|
| family group posing together · group of friends smiling · smiling person · child playing · pet dog · pet cat · famous landmark · city skyline · scenic mountain landscape · beach at sunset · cool sports car · classic car at a car show · motorcycle · firearm collection · rifle on display · wedding or birthday party | paper shooting target with bullet holes · electrical breaker panel · exposed electrical wiring · parking garage pillar with a level number sign · receipt · screenshot of a phone screen · document with printed text · barcode or serial number label · whiteboard with writing · product in its cardboard box · price tag in a store · computer monitor screen · car dashboard with warning lights · close-up of a broken or damaged item · blurry accidental shot · almost black dark photo |

The closest prompt pairs (text–text cosine) are dog/cat 0.94, family/friends 0.91,
sports car/motorcycle 0.86, screenshot/monitor 0.85 and receipt/document 0.82. These pairs sit
on the same side, so they don't hurt keep-vs-junk.

### 2.3 Zero-shot sanity check on generated images [M]

Ten images were drawn with Pillow; no real photos were used. Score: softmax(100·cos) over the 32
prompts. Contact sheet: [`validation-synthetic.png`](validation-synthetic.png).

| Image | fp32 top prompt (p) | keep−junk | Downloaded INT8 top prompt | cos(INT8, fp32) |
|---|---|---|---|---|
| bullseye target | shooting target (0.998) | −1.00 | almost black photo | 0.17 |
| receipt with text | receipt (1.00) | −1.00 | almost black photo | 0.04 |
| gradient landscape | mountain landscape (0.97) | +0.98 | shooting target | 0.16 |
| breaker panel + wires | breaker panel (0.998) | −1.00 | almost black photo | 0.18 |
| phone screenshot | screenshot (1.00) | −1.00 | motorcycle | 0.05 |
| lease document | document (0.99) | −1.00 | almost black photo | 0.12 |
| "B2 / 14" pillar | parking pillar (1.00) | −1.00 | almost black photo | 0.04 |
| red car (vector) | sports car (0.98) | +0.97 | shooting target | 0.16 |
| 5 cartoon people | group of friends (0.82) | +1.00 | breaker panel | 0.04 |
| dark noisy frame | almost black photo (1.00) | −1.00 | family group | 0.06 |

Honest reading:
- The generated images are clean, iconic and easy, so 10/10 shows that the **pipeline is
  correct**, not that real photos will be ≥ 95 % right.
- The softmax at scale 100 is overconfident (margins saturate at ±1). §5 therefore uses the
  log-odds, damped, not the raw probability.
- Raw top cosines are only **0.22–0.31**, which is normal for CLIP. Thresholds must use relative
  scores, never absolute cosine to a prompt.

### 2.4 Quantisation: the downloaded INT8 is unusable; re-exports that work [M]

[`requantize.py`](requantize.py) on the 10 images plus 10 held-out crops/rotations (20 inputs):

| Variant | File | cos vs fp32 (min / mean) | Top-1 agrees | CPU 1 thread, median |
|---|---|---|---|---|
| fp32 (reference) | 45.5 MB | 1 / 1 | — | **47 ms** (22 ms on 4 threads when the CPU was quiet) |
| Downloaded dynamic INT8 (`ConvInteger`) | 11.8 MB | **−0.07 / 0.11** | **0/20** | 113 ms |
| Static QDQ INT8, calibrated on the generated images | 12.4 MB | 0.00 / 0.16 | 2/20 | — |
| Weight-only INT8, all Conv/MatMul | 11.9 MB | 0.91 / 0.95 | 19/20 | — |
| **Weight-only INT8, large non-depthwise tensors only** | **12.9 MB** | **0.985 / 0.993** | **20/20** | **52 ms** |
| **fp16 weights, fp32 I/O** | **22.9 MB** | **0.9999 / 1.0000** | **20/20** | 61 ms |

- **Why INT8 activations fail** [A, consistent with the numbers]: MobileCLIP's re-parameterised
  FastViT convolutions have wide, per-channel activation ranges. Per-tensor activation
  quantisation (dynamic or static) destroys them, while weight-only quantisation keeps the
  maths in fp32.
- **Recommendation:** ship **weight-only INT8 (12.9 MB)**. Gate it on device with a golden test,
  using the same 10 PNGs and fp32 vectors (§15). Fall back to fp16 if cos < 0.98 on real
  thumbnails.
- `requantize.py` re-creates the file from `vision_model.onnx`. It upgrades opset 12 → 17
  (needed for per-axis `DequantizeLinear`), then stores per-output-channel int8 for the
  50 Conv/MatMul weights with ≥ 16 k elements, except depthwise.
- **Never mix embeddings from two variants.** Each photo row stores the encoder's SHA-256, and a
  change of variant re-embeds lazily.
- Timings are noisy: another job was loading this 4-vCPU box (load average ~4). One-thread
  medians from the interleaved benchmark are the reliable numbers. On the phone, expect
  ~10–30 ms on the Oryon CPU, 2 threads [A]. Thumbnail and preprocessing add ~5–15 ms [A]
  (5 ms in Python here for a 1024×768 decode-free resize [M]).

### 2.5 Near-duplicate cosines (inform burst and suppression thresholds) [M]

fp32 cosine between a generated landscape and its variants:

| Variant | cos |
|---|---|
| shift 3 % | 0.998 |
| brightness −15 % | 0.999 |
| JPEG q50 | 0.997 |
| rotate 2° | 0.995 |
| zoom 1.15 | 0.997 |
| blur | 0.984 |
| same layout, other colours | 0.975 |
| **different generated scenes** | **0.12–0.48** (mean 0.30) |

Real bursts will be lower (people move) and real "same kind of thing" pairs higher than these
generated scenes. This supports the Step B thresholds (burst ≥ 0.88/0.93, hard suppression
≥ 0.92, soft from 0.80) as **starting points to tune on your photos** [A].

### 2.6 ONNX Runtime Android on Maven Central [V]

| Version | Date | AAR | arm64 `libonnxruntime.so` (gz) | Telemetry provider |
|---|---|---|---|---|
| 1.27.0 | 2026-06-15 | 44.5 MB | 28.0 MB (10.3 MB) | no |
| **1.28.0** | 2026-07-25 | 45.6 MB | **28.6 MB (10.6 MB)** | **no** |
| 1.29.0 | 2026-08-12 | 51.9 MB | 32.1 MB | **yes** |
| 1.31.0 | 2026-10-08 | 55.1 MB | 34.2 MB | yes |
| 1.32.0 (latest) | 2026-11-13 | 59.3 MB | 36.3 MB (13.7 MB) | yes |

- **The AAR holds 4 ABIs.** In 1.32 they total 150 MB uncompressed: arm64 36.3, armeabi-v7a 25.8,
  x86 43.3 and x86_64 45.0 MB. **`abiFilters += "arm64-v8a"` is mandatory.** Without it the APK
  grows by ~60 MB compressed / ~150 MB installed. The app has no other native code, so the filter
  costs nothing.
- **1.29.0+ telemetry.** The AAR manifest adds `ai.onnxruntime.TelemetryInitializer` (a
  ContentProvider that runs at app start), plus `INTERNET` and `ACCESS_NETWORK_STATE`. The native
  library contains `https://mobile.events.data.microsoft.com/OneCollector/1.0`,
  `ORT_DISABLE_TELEMETRY` and `DisableTelemetryEvents`, and `OrtEnvironment` has
  `setTelemetry`/`sendTelemetry`.
  - That conflicts with "everything private".
  - **Pin 1.28.0.** If a later version is ever needed, remove the provider with
    `tools:node="remove"`, call the disable API at start-up [A: exact API to confirm], and verify
    with a packet capture (PCAPdroid) that nothing leaves the phone.
- **The AAR ships no consumer ProGuard rules.** With R8 on, add
  `-keep class ai.onnxruntime.** { *; }` (JNI looks classes up by name).
- The Step B doc says `onnxruntime-android` is at 1.27. It is now 1.32.0, so update that line
  when B is revisited.

---

## 3. Architecture

### 3.1 Components (new package `com.nightstand.photos.smart`)

| Class | Kind | Role |
|---|---|---|
| `Preprocess` | pure | Bitmap pixels (`IntArray`, w, h) → `FloatArray` NCHW 3×256×256: shortest-side 256 bilinear, centre crop, /255. Also `dHash64`, `sharpness` (variance of Laplacian on grey 256²), `luma`. |
| `ImageEncoder` | ORT wrapper | Loads the model from assets (stored uncompressed), one `OrtSession` with 2 intra-op threads, `embed(FloatArray): FloatArray` (L2-normalised). Opened lazily, closed 2 min after the page hides or on `onTrimMemory`. |
| `PromptTable` | pure | Loads the fp16 prompt table; `zeroShot(e)` → margin (log-odds keep:junk), top prompt id, per-prompt p for the "why" overlay. |
| `OnlineHead` | pure | L2-regularised logistic regression on 512-d (+bias), weighted, trained by Newton/IRLS or 50 GD steps; `predict(e)`; (de)serialise to BLOB. |
| `Scorer` | pure | Combines prior + head + suppression + thresholds (§5); returns `Verdict(p, eligible, reasons)`. |
| `BurstGrouper` | pure | Chain grouping on (time, embedding, filename) and best-of-group choice (§8). |
| `CandidateSampler` | pure | Draws the next MediaStore ids to try: strata (on this day / last 30 days / uniform), skip sets (§6). |
| `PhotoIndexDb` | SQLite | `SQLiteOpenHelper`, WAL; schema §4; small DAO. No Room, to stay dependency-light like the rest of the app. |
| `SmartPicker` | orchestrator | Lives on one `HandlerThread("photo-smart", THREAD_PRIORITY_BACKGROUND)`. Owns encoder, DB writes, look-ahead queue, trickle and feedback (`hide`, `undo`, `restore`). Emits `Ready(mediaId, bitmap, verdict)` to the UI thread. |
| `PhotoFeedback` | facade | Used by `PhotosPage` (swipe/undo) and settings (Hidden screen, Reset). Thread-safe: posts to the picker's thread. |

```
 PageContainer ──touch──► PhotosPage ──hide(id)/undo()──► PhotoFeedback ─┐
       ▲                      │ nextReady()                               │ (posts)
       │ invalidate            ▼                                          ▼
       └──────────── UI thread ◄── Ready(bitmap, verdict) ── SmartPicker [photo-smart thread]
                                                             │  CandidateSampler ◄─ PhotoLibrary.candidates()
                                                             │  thumbnail 256 ─► Preprocess ─► ImageEncoder (ORT)
                                                             │  PromptTable · OnlineHead · Scorer · BurstGrouper
                                                             └─► PhotoIndexDb (SQLite, WAL) ◄── Settings (read)
```

### 3.2 Where it hooks into the existing code

| File | Change |
|---|---|
| `photos/PhotoLibrary.kt` | Keep `query()` and `decode()` as they are (used when Smart is Off, and for display). Add `candidates(c)`, which reads `_ID, DATE_TAKEN, DATE_MODIFIED, GENERATION_MODIFIED, BUCKET_ID, DISPLAY_NAME, MIME_TYPE, WIDTH, HEIGHT, IS_FAVORITE` with the same source filter, and `thumbnail(c, id)` = `loadThumbnail(uri, 256×256)`. |
| `pages/PhotosPage.kt` | Replace its own `Executors.newSingleThreadExecutor()` + `queue` with `SmartPicker` when Smart is on (old path kept for Off). `next()` takes `picker.nextReady()`. Draws the swipe animation and the Undo toast inside `drawRight`; `onTapAt` handles the toast's Undo. `onShown`/`onHidden` start and stop the picker. |
| `pages/Page.kt` | Two small additions: `open fun dragRegion(): RectF? = null` and `open fun onPageDrag(phase, dy, vy)` (§7). Pages still don't own touch; the container routes it. |
| `pages/PageContainer.kt` | Gesture arbitration: a vertical drag that **starts inside `current.dragRegion()`** goes to the page (mode 3) instead of brightness (§7). The arbitration logic moves into a pure `GestureArbiter` so it can be unit-tested. |
| `NightstandActivity.kt` | Nothing structural. It already passes `DisplayData.charging`. Add `thermalStatus` to `DisplayData` (from `PowerManager.addThermalStatusListener`), and pre-warm the encoder in `onCreate` when the start page is Photos. |
| `Prefs.kt` | `photoSmart` (OFF/SMART, default OFF until rollout step 5), `photoOnePerBurst` (true), `photoHideRadius` (if Q3 says so), `smartHintsShown`. |
| `ui/ViewsTab.kt` | Under Photos ⚙: "Smart selection" section (mockup b). New `ui/HiddenPhotos.kt` screen (mockup c). |
| `ui/DebugTab.kt` | Counters (scored, cached, skipped, folded, swipes per 100 shown), "Show reasons on the stand" (mockup d), burst strips, "Run encoder self-test" (§15), "Clear index". |

### 3.3 Threading

- **UI thread:** touch, animation, drawing, toast. It never touches ORT or SQLite.
- **`photo-smart` thread** (one, background priority) does all of the following, serially:
  - thumbnail load, preprocessing and ORT inference (2 intra-op threads inside ORT);
  - SQLite writes and head refits;
  - neighbour scans after a swipe (≤ 20 k × 512 multiply-adds ≈ 10 M, ~5–10 ms [A]);
  - full-size decode of the next photo (`PhotoLibrary.decode`, as today).
  - Serial execution means no locking around the session or the head.
- **Settings (Compose)** read the DB on `Dispatchers.IO` through a separate read-only connection
  (WAL allows concurrent reads). Writes from settings (restore, reset) go through
  `PhotoFeedback`, so all writes are on one thread.

---

## 4. SQLite schema (`photos_smart.db`, app-private, excluded from backup)

Table and column names match `../step-b/DESIGN.md` §5.1, so B only *adds* tables (`review_batch`,
`review_item`, `model_version`, `training_run`) and B's `photo` columns (`p_b`, `p_b_sd`,
`scored_ver`).

```sql
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
-- schema_version, prompt_set_sha, mediastore_version, active_encoder_id, head_version

CREATE TABLE encoder (
  id INTEGER PRIMARY KEY, name TEXT NOT NULL,          -- "mobileclip_s0_w8"
  sha256 TEXT NOT NULL UNIQUE, dim INTEGER NOT NULL, created_at INTEGER NOT NULL);

CREATE TABLE photo (
  media_id      INTEGER PRIMARY KEY,                   -- MediaStore _ID
  content_hash  INTEGER,                               -- dHash64 of the 256 thumbnail (survives id changes)
  date_taken    INTEGER, date_modified INTEGER, generation INTEGER,
  bucket_id     TEXT, display_name TEXT, mime TEXT, width INTEGER, height INTEGER,
  encoder_id    INTEGER REFERENCES encoder,
  embedding     BLOB,                                  -- 512 × fp16 LE = 1,024 B, L2-normalised
  zs_margin     REAL,                                  -- log P(keep)/P(junk) at scale 100, prompt set in meta
  zs_top        TEXT,                                  -- best prompt id, for "why"
  sharpness     REAL, luma REAL,
  group_id      INTEGER REFERENCES dup_group,
  state         INTEGER NOT NULL DEFAULT 0,            -- 0 ok · 1 hidden by you · 2 look-alike of a hidden one
                                                       -- 3 burst member (not best) · 4 unreadable
  suppressed_by INTEGER REFERENCES label,              -- which swipe hid it (state 1/2), for Undo and the Hidden screen
  scored_at     INTEGER,
  last_shown_at INTEGER, show_count INTEGER NOT NULL DEFAULT 0);
CREATE INDEX photo_taken ON photo(date_taken);
CREATE INDEX photo_state ON photo(state);
CREATE INDEX photo_hash  ON photo(content_hash);

CREATE TABLE dup_group (                               -- identical to Step B
  group_id INTEGER PRIMARY KEY, rep_media_id INTEGER NOT NULL,
  rep_locked INTEGER NOT NULL DEFAULT 0, size INTEGER NOT NULL,
  first_taken INTEGER, last_taken INTEGER, updated_at INTEGER NOT NULL);

CREATE TABLE label (                                   -- identical columns to Step B; A writes split='train'
  id INTEGER PRIMARY KEY, group_id INTEGER NOT NULL, media_id INTEGER NOT NULL,
  content_hash INTEGER, value INTEGER NOT NULL,        -- 0 discard (swipe) · 1 show (restore) · -2 undo marker
  source TEXT NOT NULL,                                -- swipe_out | swipe_out_night | restore | undo | reset
  weight REAL NOT NULL, split TEXT NOT NULL DEFAULT 'train',
  batch_id INTEGER, created_at INTEGER NOT NULL,
  superseded_by INTEGER REFERENCES label);             -- undo/restore supersede, never delete
CREATE INDEX label_live ON label(group_id) WHERE superseded_by IS NULL;

CREATE TABLE online_head (                             -- identical to Step B; base_version_id 0 = zero-shot prior
  base_version_id INTEGER PRIMARY KEY, weights BLOB, n_updates INTEGER, updated_at INTEGER);

CREATE TABLE exposure (                                -- what was on screen: recency, swipe-rate metric, B's explore
  id INTEGER PRIMARY KEY, media_id INTEGER NOT NULL, shown_at INTEGER NOT NULL,
  visible_ms INTEGER, night INTEGER NOT NULL, outcome INTEGER NOT NULL);  -- 0 timed out · 1 swiped · 2 page left
CREATE INDEX exposure_time ON exposure(shown_at);
```

- **Size:** ~1.2 KB per scored photo. Lazily that is a few MB in the first weeks, and ≤ 25 MB if
  all 20 k photos are ever scored. `exposure` is pruned to 90 days.
- **Re-scoring is free when only prompts or the head change:** the embedding is kept, and
  `zs_margin` is recomputed in bulk in ~ms. A new encoder SHA marks rows stale, and they
  re-embed lazily as they come up.
- **MediaStore ids can change** (restore, migration). On a `MediaStore.getVersion()` change, rows
  are re-linked by `content_hash` + `date_taken`. Labels keep `content_hash` for the same reason.

---

## 5. Scoring

For photo embedding `e` (unit vector) and prompt vectors `t_i`:

```
c_i   = e · t_i                                        (cosine)
zs    = LSE_keep(100·c) − LSE_junk(100·c)              (= log P(keep)/P(junk) under the 32-way softmax; stored)
ℓ0    = a · clamp(zs, −12, 12) + b                     prior logit;  start a = 0.35, b = 0   [A, tune]
h(e)  = w · e + w0                                      personal head (residual), |h| clamped to 4
s     = max_j e · x_j  over live swipe-outs x_j          nearest hidden photo
hard  = s ≥ 0.92  and not (closer to a restored photo)  → state 2, never shown
soft  = 0.80 ≤ s < 0.92  → pen = 2·(s − 0.80)/0.12      (0…2 logits; same rule as Step B §3)
ℓ     = ℓ0 + h(e) − pen
p     = σ(ℓ)
eligible = ¬hard ∧ state = 0 ∧ (burst rep or one-per-burst off) ∧ p ≥ τ      τ_day 0.5 · τ_night 0.7
```

**Personal head (`OnlineHead`).** Swipe-outs are one-sided: they are only ever "discard". Trained
on negatives alone, a classifier slides towards "hide everything". A anchors it:
- **Negatives:** live swipe-outs. Weight 1.0 by day and 0.5 at night (half-asleep swipes are
  noisier). Each burst group counts once.
- **Positives:**
  - restores from the Hidden screen, weight 1.0;
  - **anchors**: up to 200 random scored photos with `ℓ0 ≥ 1` that were shown and not swiped,
    re-drawn at each refit, with total weight = ½ × total negative weight.
  - The head therefore learns "what separates *your* swipes from typical good photos" rather than
    "everything is bad".
- **Fit:** L2 λ = 1.0 towards zero (it is a residual on the prior), with Newton/IRLS on ≤ a few
  hundred rows: **< 100 ms** (Step B measured 70–90 ms for 500 rows on a JVM). It is refitted
  after every swipe, undo or restore on the `photo-smart` thread, and `meta.head_version` is
  bumped so queued verdicts are recomputed.
- **Not before 3 swipes:** with fewer than 3 negatives `h ≡ 0`. Until then only instant
  suppression acts.
- **Step B handover:** when B's first trained version is active, B absorbs these labels and the
  residual head resets to zero (Step B §2.5).

**Sampling order** among eligible photos (inside the queue; §6 decides what gets scored):
`weight = p × novelty × timeliness`
- **novelty:** 0 if shown in the last 3 days (`exposure`); × 0.3 if cos > 0.85 to any of the
  last 20 shown (one event doesn't take over the night);
- **timeliness:** × 2 for "on this day" (±3 days, earlier years); × 1.5 for the last 30 days.

**"Why is this shown?"** (mockup d). `Scorer` returns the terms above (top 3 prompts, ℓ0, h,
nearest hidden photo, burst, novelty, timeliness), so the Debug overlay is a pure render of the
verdict.

---

## 6. Look-ahead queue (lazy scoring)

```
          CandidateSampler                 SmartPicker (photo-smart thread)                        UI
 MediaStore ids ──► next id ──► cached in DB with current encoder? ──yes──► Scorer ──► eligible? ─► ready queue (K) ─► PhotosPage
 (strata: 20 % on-this-day,              │ no                                           │ no
  20 % last 30 days, 60 % uniform;        ▼                                              ▼
  skip: state≠0, shown < 3 days,   thumbnail 256 → Preprocess → ORT → zs ─► save row    skip (stays in DB, never retried)
  already queued)                  + burst window (§8)
```

- **Depth:** K = 3 by day (one photo every 45 s) and K = 2 at night (one every 10 min). The head
  of the queue is decoded at full size ~5 s before it is due. That is the same decode as today,
  just earlier.
- **Refill:** after each dequeue, try up to 20 candidates until the queue is full again. On the
  phone each new candidate costs ~20–60 ms [A]; a cached one costs ~0.1 ms.
- **Re-check on dequeue:** a swipe may have happened since the photo was queued. `Scorer` re-runs
  (µs) with the current suppression list and head version.
- **Trickle warm-up (optional, Q8):** while the page is visible, charging, thermal ≤ LIGHT and no
  touch in the last 5 s, score one extra candidate every 2 s by day or every 10 s at night, with a
  cap of 300 per session.
  - This is still "lazy": nothing runs off-screen.
  - It grows the scored pool, which helps novelty/diversity and makes swipe suppression reach
    photos before they come up.
- **Nothing eligible after 20 tries:**
  1. Use the best already-scored eligible photo from the DB.
  2. Otherwise relax τ by 0.1, down to 0.3.
  3. Otherwise fall back to today's plain shuffle for this slot, and log it to Debug.
- **Smart = Off** → exactly today's code path (`PhotoLibrary.query().shuffled()`).

---

## 7. Swipe-out gesture, undo, suppression

### 7.1 Gesture (no conflict with page swipes or brightness)

Today `PageContainer` owns all touch:
- horizontal drag = page change;
- vertical drag = night brightness;
- tap = brighten;
- double-tap = unlock.

Step A adds one rule: on the Photos page, **a vertical drag that starts inside the photo pane
belongs to the photo.**

| Start point | Horizontal | Vertical ↓ | Vertical ↑ |
|---|---|---|---|
| Photo pane (Photos page, Smart on, photo shown) | page swipe (unchanged) | **swipe-out** | rubber-band; flash "Brightness: drag on the clock" (reserved for "more like this", Q1) |
| Anywhere else (clock half, other pages) | page swipe | brightness | brightness |

- **The direction lock is unchanged:** the first movement past touch slop, `|dx| > |dy|` →
  horizontal. A diagonal down-swipe therefore never changes the page by accident, and a sideways
  swipe on the photo still changes the page.
- **Exclusions:** downs within 32 dp of the top edge (the system-bar reveal); the first 600 ms
  after a photo changes, so you can't hide a photo you didn't see; and while the Undo toast is
  being tapped. The photo under the finger at ACTION_DOWN is the one hidden, by `media_id`.
- **Feedback** (mockup a): the photo follows the finger. With `f = dy / paneHeight`: translate
  dy, scale 1 − 0.2f, rotate 5°·f, alpha 1 − 0.3f.
  - At `f ≥ 0.35` a light haptic tick plays.
  - **Commit** on release when `f ≥ 0.35`, or on a downward fling ≥ 1200 dp/s with `f ≥ 0.1`.
    Otherwise it springs back (200 ms).
- **On commit:** the photo falls out (180 ms), and the next ready photo appears with a short
  300 ms fade instead of the usual 1.2 s.
- **Toast:** *"Hidden · similar photos won't show · Undo"*, for 6 s by day and 10 s at night. At
  night it is red on black, outlined (mockup a-night), and has no icon. Drawn by `PhotosPage`;
  `onTapAt` handles Undo.
- **First-run hint:** the first 3 photos of the first Smart session by day show a small chip,
  "Swipe down to hide", once.

### 7.2 What a swipe does (the "lesson learned"), in order, on the `photo-smart` thread

1. Insert `label(value 0, source swipe_out|swipe_out_night, weight 1.0|0.5, group_id)`.
2. Set `state = 1` on the photo and its whole burst group.
3. **Hard-suppress look-alikes:** scan scored embeddings; every photo with cos ≥ 0.92 gets
   `state = 2, suppressed_by = label.id`. Photos scored later are checked against the live swipe
   list at scoring time, so the lesson also applies to photos not yet seen.
4. **Soft:** 0.80–0.92 is not stored. It is applied in `Scorer` as a penalty, so it disappears
   with an undo.
5. Refit `OnlineHead` (< 100 ms) and bump `head_version`.
6. Drop queued items that are no longer eligible, then refill.

**Undo** (toast, or "Restore" in the Hidden screen):
- write a superseding `undo` (toast) or `restore` (Hidden screen) label; a restore is also a
  **positive** example (weight 1.0);
- set `state = 0` where `suppressed_by = label.id`, and refit;
- the toast's Undo puts the same photo back on screen.

**Reset learning** (settings): supersede all A labels with `reset`, clear `online_head`, and set
states 1/2 → 0. Burst groups are kept, since they are not learning.

### 7.3 Radius

- **0.92 hard / 0.80 soft** are the Step B numbers. §2.5 suggests near-identical shots sit
  ≥ 0.97; "same kind of thing" (two different breaker panels) is expected around 0.75–0.90 for
  CLIP-family models [A].
- So the instant reaction catches near-identical shots, and the **head** generalises to the
  category over a few swipes.
- Tune on device with Debug's "look-alikes of this swipe" strip. Q3 asks whether to expose a
  Strict / Normal / Wide setting (0.95 / 0.92 / 0.88).

---

## 8. Burst grouping (lazy)

When photo *x* (taken at *t*) is scored:
1. **Window:** query MediaStore for photos with `DATE_TAKEN` in [t − 120 s, t + 120 s] in the
   same bucket, at most 24, nearest first.
   - Embed those not yet in the DB. They are persisted, so this is not wasted work.
   - If the chain reaches the window edge, extend by 120 s, up to 5 min or 40 photos.
2. **Chain rule** (same as Step B §4), sorted by time: *i* joins *i−1*'s group if
   **Δt ≤ 10 s ∧ cos ≥ 0.88**, or **Δt ≤ 120 s ∧ cos ≥ 0.93**.
3. **Samsung burst names** [A, to confirm on One UI 9]: files named `YYYYMMDD_HHMMSS_NNN(_COVER).jpg`
   with the same prefix join one group if cos ≥ 0.80.
4. **Exact or edited copies** anywhere in time: dHash Hamming ≤ 4 ∧ cos ≥ 0.97 joins the
   original's group. The in-memory hash list for 20 k photos is 160 KB.
5. **Best of group:** argmax of `ℓ + 0.5·sharpness_z + 0.2·log(megapixels) + 0.3·is_favorite`.
   - Faces and eyes-open are not in A (no ML Kit), so blurry and dark frames lose on sharpness
     and on the "blurry/dark" prompts.
   - Non-representatives get `state = 3`.
   - `rep_locked` (B's "show this one instead") is honoured if set.
6. A photo that later joins a group (e.g. taken today) triggers a regroup of that window only.

Settings toggle **"Show one per burst"** (default on). Off makes all members eligible.

---

## 9. Cold start

- **First Photos page with Smart on:** the DB is empty. The encoder loads (~0.1–0.2 s [M: 132 ms
  session creation here]). Most camera photos are keeps, so the first eligible photo usually
  appears after 1–3 candidates: **~0.3–1 s** after the page shows [A]. The existing
  "Loading photos…" text covers it.
  - No unscored photo is ever shown in Smart mode. First impressions matter more than 0.5 s.
- **Pre-warm:** if the Photos page is the start page, `NightstandActivity.onCreate` starts the
  encoder and fills the queue before the first frame.
- **Prior only:** until the first swipes, only the zero-shot prior and bursts act. τ_day = 0.5 is
  deliberately mild; τ_night = 0.7.
- **Calibration:** a and b in §5 should be set once from ~200 of your photos, as described in
  `../STRATEGY.md` §4 (blind keep/junk labels, Debug export). Without it, the defaults are a
  guess [A].

---

## 10. Night behaviour

- **Unchanged from today:** red monochrome, no motion, one photo per 10 min.
- **Stricter:** τ_night = 0.7, bursts always folded, and no exploration (Step B's explore pool
  is 0 % at night too). You should never wake up to a receipt.
- **Swipe-out works at night:** a glowing junk photo at 3 am is exactly when you want it gone.
  - Red toast, 10 s.
  - Labels from night swipes weigh 0.5.
  - Q4 asks whether to disable it at night instead.
- **Quieter:** trickle at most one candidate per 10 s, only at thermal status NONE. The "why"
  overlay is never shown at night.
- Scoring always uses the original colours (thumbnail), not the red rendering.

---

## 11. Battery, CPU and thermal budget

- **Runs only when** the Photos page is visible (`onShown` → `onHidden`) **and** the phone is
  charging (`DisplayData.charging`) **and** Smart is on. There is no WorkManager and no job; the
  library backfill is Step B's.
- **Cost:**
  - per new photo: ~20–60 ms on ~2 cores [A]; 47 ms on one Xeon core here [M];
  - refill-only by day: ~1–4 candidates per 45 s → **< 0.5 % of one core**;
  - trickle: one per 2 s → ~2.5 % of one core for ≤ 10 min per session.
- **Thermal:** `PowerManager` thermal status ≥ MODERATE (or `getThermalHeadroom(30)` > 0.85)
  pauses trickle. ≥ SEVERE pauses all new scoring, so only cached verdicts are used. A phone in a
  case on a Qi2 pad is already warm.
- **Memory:** ORT session + activations ~60–120 MB [A], released 2 min after the page hides or
  on `onTrimMemory(RUNNING_LOW)`.
- **Battery while unplugged:** zero. The display closes when the charger is pulled, and nothing
  runs off-screen.

---

## 12. APK impact

| Item | Size | Note |
|---|---|---|
| App today (R8 release) | 1.9 MB [V] | `app-release.apk` |
| ORT 1.28.0 arm64 `libonnxruntime.so` + JNI | **28.7 MB** stored, or **~10.6 MB** compressed | Default for minSdk ≥ 23 is uncompressed and page-aligned (no extraction). `packaging.jniLibs.useLegacyPackaging = true` compresses it, but then it is extracted on install. |
| ORT Java classes | ~0.12 MB before R8 | keep rule needed (§2.6) |
| Model, weight-only INT8 | **12.9 MB** | Store uncompressed (`androidResources.noCompress += "onnx"`); gzip saves only 13 % [M]. |
| (alt) fp16 model | 22.9 MB | if the on-device golden test fails for w8 |
| Prompt table | 32 KB | fp16 binary from `prompt_embeddings.json` |
| **APK total, arm64-only** | **~44 MB** default · **~26 MB** compressed libs | Sideloaded, so no store limits. Installed footprint ~44 MB either way. |
| Without `abiFilters` | +~120 MB uncompressed | four ABIs; never do this |

Later option: a custom ORT "minimal build" with only this model's operators (ORT format) could
shrink the runtime to ~3–5 MB [A]. That means building ORT from source (an hour or two), and it is
only worth it if APK size starts to matter.

---

## 13. Privacy

- **No network.** Inference, DB and learning are all on the phone. The prompt vectors were
  computed once here and ship as data. **Pin ORT 1.28.0** to avoid the 1.29+ telemetry provider
  (§2.6). Verify once with a packet capture while the Photos page runs.
- **Embeddings are derived personal data.** Keep `photos_smart.db` in app-private storage and
  **exclude it from Android backup.** The app currently has default `allowBackup`, so add
  `dataExtractionRules`/`fullBackupContent` excludes, or set `allowBackup="false"`.
- **Swipes work on the lock screen.** Anyone at the bedside could hide a photo, but not see
  anything new. Undo and the Hidden screen are in settings, which needs an unlocked phone.
- **The Hidden screen shows thumbnails of hidden photos.** It is only reachable in settings, and
  "Reset learning" and "Clear index" (Debug) delete everything learned. Uninstall removes it all.
- **Model licence:** the MobileCLIP weights come under Apple's terms (the downloaded folder's
  `LICENSE` is Apple's sample-code licence, personal and non-exclusive). That is fine for a
  personal sideloaded app [A]; read it before ever distributing the APK.

---

## 14. Failure modes

| Failure | Detection | Behaviour |
|---|---|---|
| Model asset missing or corrupt, or the SHA doesn't match the prompt table | load-time check | Smart disabled for the session, plain shuffle, Debug log + Home status "Smart selection unavailable" |
| ORT native crash or OOM on session create | `runCatching`, `onTrimMemory` | as above; retry next session; a crash counter in prefs disables Smart after 3 |
| Encoder variant changed (w8 → fp16) | `encoder.sha256` ≠ row's encoder | rows marked stale, re-embedded lazily; labels kept (embeddings re-derived) |
| Photo deleted / unreadable | thumbnail throws | `state = 4`, skipped; pruned on MediaStore change |
| MediaStore ids renumbered | `getVersion()` change | re-link by `content_hash` + `date_taken` |
| Partial photo access (Android 14 "selected photos") | `PhotoLibrary.isGranted` | works on the visible subset; Setup already nags for full access |
| Everything eligible gets hidden (over-swiping) | eligible ratio < 5 % of scored | relax τ (§6); Settings card warns "Few photos left to show · review hidden" |
| A wrong swipe at night | — | Undo toast 10 s; Hidden screen any time; night weight 0.5 |
| Head runs away (one-sided labels) | `|h|` clamp 4; anchors | reset head button in Debug; Reset learning |
| Thermal throttling, slow scoring | thermal listener, queue empty at due time | keep showing the current photo; never show an unscored one |
| DB corruption | `SQLiteDatabaseCorruptException` | delete and rebuild the index; labels are lost unless exported (Debug "Export labels", the B format) |
| Gesture conflict reports (brightness by accident) | swipe-outs undone within 3 s, counted | Debug counter; if > 20 % are undone, revisit the gesture (Q1) |

---

## 15. Test plan

**JVM unit tests** (pure parts; JUnit + Robolectric are already set up):
- `Preprocess`: resize/crop indices and scaling on synthetic `IntArray`s. Golden check against
  Python: one of the 10 generated PNGs plus a checksum of its tensor produced by `validate.py`.
- `PromptTable`: `zs` on stored vectors matches `validation/zero_shot_results.json` within 1e-4
  (the fp32 image vectors can be exported as a fixture).
- `Scorer`: thresholds, the hard and soft suppression edges (0.799 / 0.80 / 0.919 / 0.92),
  "restored wins over swiped", the clamp, and night τ.
- `OnlineHead`:
  - learns a separable synthetic split;
  - with one-sided negatives plus anchors it doesn't collapse (mean p of the anchors stays
    ≥ 0.5);
  - L2 shrinks it to 0 with no labels;
  - BLOB round-trip.
- `BurstGrouper`: Δt/cos edges, chains across 3+ photos, filename rule, copies via dHash, best of
  group, regroup on insert.
- `CandidateSampler`: strata proportions over 10 k draws; skip sets respected.
- `GestureArbiter` (extracted from `PageContainer`):
  - down in the pane + ↓ → photo drag;
  - ↑ in the pane → rubber-band;
  - outside → brightness;
  - horizontal → page;
  - the top-edge exclusion and the 600 ms guard.
- `PhotoIndexDb` (Robolectric, real SQLite): schema creation, migration v1 → v2 stub, undo and
  restore state transitions, prune.
- Optional: run the real model in JVM tests with the desktop artifact
  `com.microsoft.onnxruntime:onnxruntime:1.28.0` (`testImplementation`) to golden-test
  `Preprocess` + model against the Python vectors without a device.

**On device** (Debug tab):
1. **Encoder self-test:** the 10 generated PNGs (bundled in debug builds only) give
   cos ≥ 0.98 vs the fp32 Python vectors and the expected top prompt. This one check covers the
   decode, preprocessing, ORT and model variant.
2. **Benchmark:** 200 photos: thumbnail, preprocess and embed times (median and p90), with 1 vs
   2 threads; fp32 vs w8 agreement on real thumbnails (mean cos ≥ 0.98).
3. **Gesture checklist:** day and night; diagonal swipes; fast page flicks starting on the photo;
   brightness on the clock half; undo within 6/10 s; locked vs unlocked.
4. **Overnight soak:** 8 h on a Qi2 pad with trickle on. Record thermal status, the battery
   charge curve, crashes and DB size.
5. **Privacy:** a PCAPdroid capture for 10 minutes on the Photos page shows no traffic from
   Nightstand except weather.
6. **Quality:** with "why" on, look at 100 shown photos. Count junk shown and good photos hidden
   (in the Hidden screen). The target from `../STRATEGY.md` is ≤ 1 junk in 20.

---

## 16. Rollout

| Step | What | Estimate |
|---|---|---|
| 0. Spike | Add ORT 1.28.0 (arm64, keep rule, no telemetry), w8 model and prompt table; Debug self-test + benchmark. **Decide w8 vs fp16 on real numbers.** | 0.5–1 day |
| 1. Lazy scoring | `PhotoIndexDb`, `ImageEncoder`, `PromptTable`, `Scorer`, `SmartPicker` + queue behind **Smart (default Off)**; "why" overlay in Debug. Use it yourself for 2–3 nights and tune a, b, τ. | 1.5 days |
| 2. Swipe-to-teach | `GestureArbiter`, drag animation, toast, Undo, suppression, `OnlineHead` | 1.5 days |
| 3. One per burst | `BurstGrouper`, window queries, best-of-group, Debug burst strips | 1 day |
| 4. Settings | Smart section (mockup b), Hidden photos (mockup c), Reset, backup exclusion | 1 day |
| 5. Default on | After a week of your own use, if swipes per 100 shown < 3 | — |

Total ~6 days. Step B then adds the backfill job, training rounds and versioning on the same
tables.

---

## 17. Open questions for tomorrow

1. **Gesture.** Swipe **down on the photo** to hide, which means on the Photos page brightness is
   dragged on the clock half only. Is that OK? Or would you prefer long-press then flick? Should
   **swipe up** later mean "more like this"?
2. **Meaning of a swipe.** Is it always "junk, never again", or sometimes "seen it enough"? If
   both, we need two gestures, or Step B learns wrong (Step B Q3).
3. **How wide is "similar"?** Is a fixed 0.92 fine, or do you want a *Strict / Normal / Wide*
   setting? Should hiding a photo also hide its whole burst? (Planned: yes.)
4. **Night swipes.** Allowed at half weight (planned), or disabled at night so a sleepy hand
   can't teach it anything?
5. **Model variant and size.** Weight-only INT8 (12.9 MB, cos 0.99 with fp32), fp16 (22.9 MB,
   exact) or fp32 (45 MB)? Is an APK of ~26 MB (compressed libs) or ~44 MB (default) fine?
6. **ONNX Runtime version.** Pin **1.28.0** (no telemetry), or take 1.32 and strip its telemetry
   provider?
7. **Prompt grey zones.** Firearms are "keep" by default and cars are any car. What about food,
   pets at the vet, kids' drawings, house projects, memes, scanned old prints? Should some of
   these be visible category toggles (e.g. "Show firearms")?
8. **Trickle warm-up.** OK to score up to ~300 extra photos per night while the Photos page is up
   and charging (better diversity, earlier suppression)? Or strictly just-ahead-of-display only?

---

## Files in this folder

| File | What |
|---|---|
| `STRATEGY.md` | this document |
| `prompt_embeddings.json` | 32 normalised 512-d prompt vectors + metadata (bundle source) |
| `prompts.py` | the prompt set (edit, re-run `validate.py`) |
| `inspect_models.py` | step 1: model I/O |
| `validate.py` | steps 2–4: prompt vectors, zero-shot on generated images, INT8 vs fp32, near-dup cosines, timing |
| `requantize.py` | weight-only INT8 / fp16 / static-QDQ re-exports and their agreement with fp32 |
| `bench.py` | interleaved CPU latency of the variants |
| `validation/` | raw outputs: `model_io.txt`, `zero_shot_results.json`, `requantize_results.json`, `bench_results.json` |
| `validation-synthetic.png` | the 10 generated test images with fp32 verdicts |
| `mockup.html`, `render.js` | mockups; `node render.js <this folder>` writes the PNGs |
| `overview.png`, `a-photo-swipe-day.png`, `a-photo-swipe-night.png`, `b-photos-options.png`, `c-hidden-photos.png`, `d-why-shown.png` | rendered mockups (landscape 915×412 and portrait 412×915 CSS px, @2x) |

Re-run (from a scripts folder, with the model downloaded elsewhere):
```
python -I inspect_models.py <model_dir>
python -I validate.py   <model_dir> <out_dir>
python -I requantize.py <model_dir> <out_dir>/requant <out_dir>/synthetic
python -I bench.py fp32=<model_dir>/onnx/vision_model.onnx w8=<out_dir>/requant/vision_w8_big.onnx
```
