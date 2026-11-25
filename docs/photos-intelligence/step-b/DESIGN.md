# Photos intelligence, Step B: a trainable "show vs discard" model

Status: **design for discussion, nothing built.** Prepared 2026-11-25. It builds on
[`../STRATEGY.md`](../STRATEGY.md) and on **Step A**, which is being built now:
- a frozen MobileCLIP-S0 image encoder (ONNX, ~12 MB, ONNX Runtime Android) gives a
  512-d embedding per photo, scored lazily just before display and kept in SQLite;
- zero-shot keep/junk prompts handle the cold start;
- an online logistic head learns from swipe-outs, and cosine neighbours of a swiped
  photo are suppressed at once;
- successive near-duplicates are collapsed.

Mockups: [`mockup.html`](mockup.html) → `overview.png`, `a-train-round.png`,
`b-round-results.png`, `c-home-card.png`, `d-model-history.png`, `e-photos-options.png`.

Legend: **[V]** checked against a cited source · **[M]** measured here (sandbox JVM) ·
**[A]** assumption or estimate, to be measured on the S26 Ultra.

---

## TL;DR

- **"BERT-like" = the BERT recipe, not a BERT model.** BERT's lesson is: take a big
  pretrained encoder and train a small task head on a few hundred of *your* labels.
  MobileCLIP-S0 is our pretrained encoder. Step B adds the head and a proper labelling
  workflow.
- **Recommendation (B1):** keep the encoder **frozen**, shared with A. Train a **small
  MLP head** (512 → 64 → 1, plus a few metadata features) as a **5-member bootstrap
  ensemble**, **on the phone in plain Kotlin**. It takes a few seconds per round, needs
  no ML framework or network, and any version can be rolled back by switching rows in
  SQLite. Labels come from rounds of 40 photos chosen by **active learning**
  (uncertain + diverse + a blind random check set), then **monthly top-ups** and every
  swipe-out from A.
- **Path to more power, only if measured precision plateaus below target:**
  - **B2:** export your label list (IDs plus thumbnails via `adb`) and fine-tune the
    encoder's last stage or LoRA on a PC or Colab. Re-import the `.onnx` file, then
    re-embed the library overnight.
  - **B3:** fine-tune the encoder on the phone itself. Not recommended now: the
    ONNX Runtime training AAR stopped at 1.19.2 (2024), and LiteRT training needs a
    TF-authored model.
- **Duplicates:** one **burst-group** table is shared by A and B. A photo joins a group
  on time gap plus embedding cosine. Only the best photo of a group is shown. A label
  applies to the whole group, but the group counts **once** in training and evaluation.
- **Trust metric:** **precision on "show"**, measured on a **blind random check set**
  that is never trained on. Target ≥ 95 %, as in STRATEGY §4. Expect ~120 labels
  (3 rounds) to beat zero-shot clearly, and ~300–500 to approach the plateau.

---

## 1. What "BERT-like" should mean here, and which architecture

### 1.1 The three options

| | **B1 Frozen encoder + small head on the phone** ✅ | **B2 Encoder fine-tune off the phone (PC/Colab)** | **B3 Encoder fine-tune on the phone** |
|---|---|---|---|
| What trains | MLP head (~33 k params) on stored 512-d embeddings | last stage of MobileCLIP-S0 image tower, or LoRA adapters, plus the head | same as B2, on the S26U |
| Data that must move | none | label list + 256 px thumbnails to your PC (`adb pull`), `.onnx` back (`adb push`) | none |
| Training time | ~1–5 s per round [M, see §5] | minutes on a GPU [A] | ~5–15 min on charge for 500 photos × 10 epochs [A] |
| After training | rescore 20 k embeddings: < 1 s | **re-embed the whole library** (~15–30 min on charge, STRATEGY §5), and A's head, the dup thresholds and the prompt table all move | same as B2 |
| Tooling | plain Kotlin, no new dependency | PyTorch + open_clip / ml-mobileclip, ONNX export (existing exports: STRATEGY §1b) | ONNX Runtime on-device training ([tutorial](https://onnxruntime.ai/docs/tutorials/on-device-training/android-app.html), artifacts from `generate_artifacts` [V]). But `onnxruntime-training-android` was last published as **1.19.2** (Maven Central, Sept 2024 [V]), while `onnxruntime-android` is at 1.27 [V], so it is a separate and stale runtime. LiteRT [training signatures](https://developers.google.com/edge/litert/conversion/tensorflow/build/ondevice_training) (`train`/`infer`/`save`/`restore` [V]) need a TF model, but MobileCLIP is PyTorch. |
| Ceiling | as good as the embedding space separates *your* keep vs junk | higher on genuinely visual distinctions the frozen space blurs (e.g. "your" car vs any car) | same as B2 |
| Risk | low | moderate: overfitting on a few hundred labels, licence (Apple TOU on weights; fine for personal use, STRATEGY §7) | high engineering cost for an uncertain gain; thermal on a Qi2 pad |

Why B1 is the right "BERT":
- With a few hundred labels, a linear or shallow probe on a frozen foundation encoder
  is the standard strong baseline.
- In the CLIP paper, zero-shot CLIP roughly matches a **4-shot** linear probe on the
  same features across 27 datasets, and probes keep improving with more shots
  ([Radford et al. 2021](https://arxiv.org/abs/2103.00020), §3.1.5) [V, recalled from
  the paper].
- Later few-shot work uses 1–16 shots per class with linear probes or adapters as the
  baseline ([Cherti et al.](https://arxiv.org/pdf/2212.07143),
  [Silva-Rodriguez et al., CVPR 2024](https://openaccess.thecvf.com/content/CVPR2024/supplemental/Silva-Rodriguez_A_Closer_Look_CVPR_2024_supplemental.pdf)) [V].
- Full fine-tuning with ~500 labels tends to overfit unless it is heavily regularised
  (LoRA, a frozen backbone, early stopping).

### 1.2 The B1 head, concretely

```
x = [ e (512, L2-normalised MobileCLIP embedding),
      zs_margin (zero-shot keep−junk),          // A's cold-start score as a feature
      faces, max_face_frac, sharpness_z, luma_z, // if A computes them; else omitted
      is_camera_exif, log_megapixels ]           // ~8 extra features, standardised
h = ReLU(W1·x + b1)   W1: 64×520, dropout 0.2 on x
p = σ(w2·h + b2)
```

- **Ensemble:** 5 members, each trained on a bootstrap resample with its own seed
  (5 × ~33 k params ≈ 670 KB fp32, or ~335 KB stored as fp16). The mean gives `p`; the spread gives `σ_p`, which drives
  active learning (§2.2) and exploration (§3).
- **Loss:** class-balanced weighted binary cross-entropy with L2 1e-3. Each sample is
  weighted by its source (§2.5) and by 1/|group| (§4).
- **Training:** Adam (lr 1e-3), full batch (N ≤ a few thousand), at most 200 epochs,
  early stopping on a 15 % internal validation slice. The slice is drawn from training
  labels, never from the check set.
- **Initialisation:** the zero-shot direction (mean KEEP prompt − mean JUNK prompt) is
  copied into one hidden unit, with `zs_margin` as an input. So round 1 can't be much
  worse than zero-shot.
- **Calibration:** after training, fit Platt scaling (one scale and one bias) on the
  internal validation slice, so `p` means a probability and thresholds carry over
  between versions.
- **Fallback when there are too few labels:** below ~60 labels, B trains a **logistic
  regression** instead of the MLP (A's model, but batch-trained). The MLP only wins
  once there are enough labels; the model card says which one is in use.

---

## 2. Training workflow and UX

Entry point: **Views → Photos ⚙ → "Smart selection"** (mockup e). It shows:
- a status line, e.g. *"Trained · v5 · 412 labels · shows ≈ 95 % keepers"*;
- the mode chips: **Off** (old shuffle), **Basic** (A only: zero-shot + swipes),
  **Trained** (B);
- buttons **Train** and **History**.

### 2.1 Initial training: rounds

- **Before round 1, the pool must be indexed.** A embeds lazily, just ahead of display,
  but active learning needs embeddings for a *representative pool*. Step B therefore
  adds a charger-only `WorkManager` backfill job. It embeds a sample stratified by
  month first (~2,000 photos, ~2–5 min [A]), then the rest of the library.
  - The Train button shows *"Preparing · 1,240 / 2,000 photos indexed"* until the
    sample is ready.
- **Round structure:** *Round 1 of 3 · 40 photos* (mockup a). There is one card at a
  time: the photo (cropped to the display's aspect ratio, so you judge what will
  actually be shown) plus its date, album and a burst badge (*"Burst of 6 · best
  shown"*; tapping it shows the strip).
  - **Show** (right swipe, or the orange button), **Discard** (left swipe), **Skip**
    (up swipe, or "Not sure"; Skip is stored, not trained on).
  - **Undo** in the top bar steps back one card, with no limit inside a round.
  - A progress bar and *"14 / 40"* show how far you are.
  - **The model's guess is not shown during a round.** Seeing it would anchor your
    answer and give away which cards are check photos. It is revealed on the results
    screen.
- **After each round** (mockup b) the screen shows:
  1. **"Before training, the model agreed with you on 26 of 40."** This is free and
     honest: model *k−1* predicts round *k* before seeing it. The picks are
     deliberately hard, so the label says *"on hard photos"*.
  2. **Check-set precision and recall** with a range, e.g. *"Of photos it would show,
     ≈ 93 % are keepers (85–97 %)"* and *"Keeps ≈ 76 % of your good photos"*, plus a
     tiny learning curve: zero-shot → R1 → R2 → R3, with a dashed 95 % target line.
  3. **What changed:** *"+214 now shown · −388 now hidden"* across the indexed
     library, with thumbnail strips of the biggest movers in each direction. If a
     category moved a lot (cluster medoids), it says so: *"Now hiding: things like
     these"*.
  4. Buttons: **Next round (2 of 3)** · **Done for now** · *Review this round's
     answers* (an editable list for fixing mistakes).
- **Stopping rule (suggested in the UI):** after round 3, or when check precision
  improves by < 1 point over two rounds, the primary button becomes **Done**. Extra
  rounds stay available ("Train more").
- **Time per round:** 40 photos at ~2–3 s each is about 2 minutes, plus ~5 s training.
  Three rounds take under 10 minutes.

### 2.2 Which photos are asked (active learning)

Each round of 40 is made of:
- **10 blind check photos:** a uniform random sample of unlabelled burst-group
  representatives, stratified by year. They go to `split = check` and are never
  trained on. They are shuffled among the others, so you can't tell them apart.
- **30 training photos:**
  - **Round 1 (cold start):** diversity first. Run k-means (k = 30) on the pool's
    embeddings and take the most "typical" photo of each cluster (TypiClust-style).
    In the low-budget regime diversity beats uncertainty, because a model with no
    labels can't tell which photos are hard
    ([Gupte et al. 2024](https://arxiv.org/abs/2401.14555),
    [SUPClust](https://arxiv.org/pdf/2403.03741)) [V].
  - **Round 2 and later:** a hybrid. Pick greedily to maximise
    `u(x) · min_dist(x, picked ∪ labelled)^0.5`, where
    `u = 1 − |2·p̄ − 1| + 2·σ_p` (closeness to 0.5 plus ensemble disagreement). This
    is uncertainty-scaled core-set selection, which avoids spending the whole budget
    on one ambiguous cluster
    ([uncertainty-scaled core-set](https://arxiv.org/pdf/2202.04251)) [V].
  - **Always:** only burst-group representatives are asked; nothing already labelled
    or "never show" is asked; at most 3 photos per calendar day per round, so one
    event can't take over a round.

### 2.3 Monthly top-up

- **When:** on the 1st of the month, a charger-only job counts new photo groups. If
  it finds ≥ 10 that are uncertain or *novel* (cosine distance > 0.25 from every
  labelled embedding), it prepares a batch of **≤ 24 photos**:
  - 18 chosen by the §2.2 hybrid, restricted to last month's photos;
  - 6 check photos (5 from last month + 1 older).
- **How it's surfaced:**
  - a low-priority notification: *"24 new photos from November to review · ~1 min"*;
  - a **Home card** (mockup c) with 4 thumbnails, **Review** and **Later**. The card
    disappears after review, or after 14 days of "Later", and comes back next month.
  - No nagging on the display itself.
- **What review does:** one round in the same card UI, then retraining produces a
  **candidate** version, which is then gated (§2.4).

### 2.4 Versioning, promotion and rollback

- Every training produces a new `model_version` row: weights, Platt parameters,
  threshold, metrics and the label count. Versions are numbered **vN** for a round or
  a monthly review, and **vN.k** for an automatic nightly retrain from swipes.
- **Promotion gate:** the candidate is scored on the **current** check set, which
  grows over time, so a fair comparison re-scores the active model on the same set.
  - The candidate becomes active automatically if its precision@show is not lower by
    more than 2 points **and** its recall is not lower by more than 5 points.
  - Otherwise it stays as *"Candidate · worse on your check photos (−3 pts)"*, with
    **Use anyway** / **Discard** (mockup d).
- **History screen** (mockup d):
  - one row per version: date, trigger, labels, precision/recall, and **Roll back**;
  - the "Zero-shot (no training)" baseline is always at the bottom and always
    available;
  - the last 12 versions are kept, plus every version that was ever active for more
    than 7 days (≤ 0.7 MB each, so < 15 MB in total);
  - **rollback** = set `active = 1` on that row and rescore. The labels are untouched,
    so the next training includes everything again.
- **Labels screen**, linked from History: *"Your labels (412)"* with filters
  (Show / Discard / Skip / Swipe-outs / Check set). Tap to flip a label. A flip marks
  the active model "stale", and the next nightly job retrains it.
  - **Export labels** writes a JSON file (MediaStore id, content hash, label, source,
    split, embedding) for path B2.

### 2.5 How A's swipe-outs feed B automatically

| Source | Label | Weight in B's loss | Notes |
|---|---|---|---|
| Train round / monthly review | Show / Discard | 1.0 | primary signal |
| A swipe-out on the display | Discard | **0.7** | Half-asleep swipes are noisier, and "seen it enough" ≠ "junk" (open question 3). |
| Long-press "Never show" (if built) | Discard + hard rule | 1.0 | also a permanent filter |
| Long-press "More like this" (if built) | Show | 1.0 | |
| Shown all night, no swipe | — | 0 | not a label: the user is usually asleep |
| Check set | Show / Discard | 0 (eval only) | |

- Swipe-outs are **one-sided** (only Discard), so the loss is class-balanced
  (§1.2). Without that, many swipes would drag the whole model towards "hide".
- **Nightly mini-retrain:** if ≥ 10 new swipe-outs or label edits have arrived, a
  charger-only job retrains B as **vN.k**, applies the gate and promotes it. A's
  online head is then **reset to zero** (§3), because B has absorbed those labels.

---

## 3. Combining A and B at display time

```
base  = Platt( mean_ensemble_B(x) )        if a trained B version is active
      = zero_shot(x)                        otherwise (A's cold-start)
logit = logit(base) + a·x                   // A's online head, trained as a RESIDUAL
                                            // on swipes since B's version, small L2
                                            // towards 0, so it corrects but can't run away
p     = σ(logit)
hard  = never_show ∨ cos(x, any swipe-out since vN) > 0.92   // A's "lesson learned", instant
soft  = cos(x, nearest swipe-out) in 0.80–0.92 → logit −= 2·(cos−0.80)/0.12
```

Sorting photos for display:
- **SHOW pool:** `p ≥ τ_show`. `τ_show` is chosen per version on the check set to
  reach 95 % precision, and is never lower than 0.5.
- **EXPLORE pool:** `τ_explore ≤ p < τ_show` (default `τ_explore` = 0.35), unlabelled,
  never shown before.
- **Hidden:** everything else.

The sampler:
- draws **90 % from SHOW and 10 % from EXPLORE**. The explore share is set in Photos ⚙
  ("Try uncertain photos: Off · A few · More"), and is **0 % in night mode**, so you
  never wake up to a junk photo;
- picks within EXPLORE by Thompson-style sampling: draw `p̃ ~ N(p̄, σ_p)` and pick the
  highest. This favours photos the model is unsure about *and* that might be good;
- uses STRATEGY's novelty/"on this day" weights within SHOW.

What exploration buys:
- **New photos still appear:** a photo taken today has no labels nearby, so its
  ensemble spread is high and it lands in EXPLORE if `p` is moderate.
- **Every explore display is an unspoken question.** A swipe-out answers it (Discard,
  weight 0.7). Not swiping is *not* taken as "Show" (§2.5); such photos are queued
  first for the next monthly review instead.

Cold start: before the first trained version, B is absent and A works exactly as it
is being built today.

---

## 4. Duplicates, shared by A and B

**Grouping job.** It runs with the backfill job and in A's lazy path; groups are
incremental and keyed by `date_taken` order.
1. Sort photos by `date_taken`. Photo *i* joins the group of *i−1* when
   **Δt ≤ 10 s and cos ≥ 0.88**, or **Δt ≤ 120 s and cos ≥ 0.93**. Bursts and "five
   tries at the group shot" chain naturally.
2. Exact or edited copies anywhere in time are linked to the original's group:
   same dHash64 (Hamming ≤ 4) **and** cos ≥ 0.97. This covers "Edited" copies and
   WhatsApp re-saves.
3. A group's representative (**best of group**) is chosen by
   `score = p_B (or zero-shot) + 0.5·sharpness_z + 0.3·[faces>0] + 0.2·eyes_open_frac + 0.1·log_megapixels`.
   - Sharpness is the variance of the Laplacian on the 256 px thumbnail; faces come
     from ML Kit if A uses it.
   - A user pick overrides everything: in the burst strip, **"Show this one
     instead"** sets `rep_locked = 1`.
4. Thresholds are starting points [A]. Tune them on ~20 of your real bursts in the
   Debug tab, which can show the group strips.

**Labelling one labels the group:**
- A card shows only the representative. Show/Discard writes **one `label` row per
  group** (`group_id` set; `media_id` = the representative).
- At training time the group is **one sample**: the representative's embedding with
  weight 1. Optionally all members can be used, each weighted 1/|group|, as cheap
  augmentation.
- Evaluation is **per group**. The check set and train/validation splits are made
  **by group and by day**, so a burst can never sit on both sides of a split. This is
  the most common way such a system would fool itself.
- A new photo that later joins an already-labelled group inherits the label for
  filtering, but adds nothing to training.

**Display:** only representatives enter the SHOW/EXPLORE pools, so A's
"successive duplicates" rule is the same table, seen from the slideshow.

---

## 5. Data model and on-device cost

### 5.1 SQLite additions

Added to A's schema; A's `photo` table already holds `media_id, embedding BLOB,
date_taken, zs_margin, …`.

```sql
-- burst / duplicate groups (shared A+B)
CREATE TABLE dup_group (
  group_id     INTEGER PRIMARY KEY,
  rep_media_id INTEGER NOT NULL,
  rep_locked   INTEGER NOT NULL DEFAULT 0,      -- user chose "show this one"
  size         INTEGER NOT NULL,
  first_taken  INTEGER, last_taken INTEGER,
  updated_at   INTEGER NOT NULL);
ALTER TABLE photo ADD COLUMN group_id   INTEGER REFERENCES dup_group;
ALTER TABLE photo ADD COLUMN sharpness  REAL;
ALTER TABLE photo ADD COLUMN p_b        REAL;   -- calibrated B prob, active version
ALTER TABLE photo ADD COLUMN p_b_sd     REAL;   -- ensemble spread
ALTER TABLE photo ADD COLUMN scored_ver INTEGER; -- model_version.id that wrote p_b

-- every human judgement, append-only (edits supersede, never delete)
CREATE TABLE label (
  id          INTEGER PRIMARY KEY,
  group_id    INTEGER NOT NULL,
  media_id    INTEGER NOT NULL,                 -- the photo actually shown
  content_hash INTEGER,                         -- survives MediaStore id changes
  value       INTEGER NOT NULL,                 -- 1 show, 0 discard, -1 skip
  source      TEXT NOT NULL,                    -- round|monthly|swipe_out|never_show|more_like|edit
  weight      REAL NOT NULL,
  split       TEXT NOT NULL,                    -- train|check
  batch_id    INTEGER REFERENCES review_batch,
  created_at  INTEGER NOT NULL,
  superseded_by INTEGER REFERENCES label);
CREATE INDEX label_live ON label(group_id) WHERE superseded_by IS NULL;

-- a round or monthly review: which photos were asked and why
CREATE TABLE review_batch (
  id INTEGER PRIMARY KEY, kind TEXT NOT NULL,   -- initial|monthly|extra
  round_no INTEGER, created_at INTEGER, completed_at INTEGER,
  status TEXT NOT NULL);                        -- ready|in_progress|done|expired
CREATE TABLE review_item (
  batch_id INTEGER, position INTEGER, group_id INTEGER,
  reason TEXT,                                  -- typical|uncertain|novel|check
  p_before REAL,                                -- model's guess before (for "agreed 26/40")
  label_id INTEGER REFERENCES label,
  PRIMARY KEY (batch_id, position));

-- trained heads
CREATE TABLE model_version (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,                           -- "v5", "v5.2"
  parent_id INTEGER REFERENCES model_version,
  kind TEXT NOT NULL,                           -- zero_shot|logreg|mlp_ens5
  encoder TEXT NOT NULL,                        -- "mobileclip_s0_int8@sha256:…"
  feature_spec TEXT NOT NULL,                   -- JSON: feature list + standardisation
  weights BLOB,                                 -- fp32 little-endian, all members
  platt_a REAL, platt_b REAL, tau_show REAL,
  n_labels INTEGER, n_check INTEGER,
  metrics TEXT,                                 -- JSON: prec, prec_lo, prec_hi, recall, auc, brier
  status TEXT NOT NULL,                         -- active|candidate|retired|rejected
  created_at INTEGER NOT NULL);

-- every training attempt, including rejected ones
CREATE TABLE training_run (
  id INTEGER PRIMARY KEY,
  trigger TEXT NOT NULL,                        -- round|monthly|nightly_swipes|manual
  batch_id INTEGER, started_at INTEGER, finished_at INTEGER,
  duration_ms INTEGER, epochs INTEGER,
  model_version_id INTEGER REFERENCES model_version,
  outcome TEXT,                                 -- promoted|kept_candidate|failed
  summary TEXT);                                -- JSON: +shown, −hidden, top movers, agreed k/n

-- A's residual head, tied to the B version it corrects
CREATE TABLE online_head (base_version_id INTEGER PRIMARY KEY, weights BLOB, n_updates INTEGER, updated_at INTEGER);
```

- **Size:** ≤ 0.7 MB per MLP-ensemble version (fp32; halve it with fp16), ~200 bytes
  per label. Small next to A's ~1 KB of embedding per photo (20–40 MB for the library).
- `encoder` and `feature_spec` make B2 safe: a version can only be activated when its
  encoder hash matches the one that produced the stored embeddings.

### 5.2 Training cost

Measured on this sandbox: plain JVM, single thread, scalar loops, full-batch Adam,
200 epochs, no early stopping [M]. The S26 Ultra's ART JIT on Oryon cores is
probably in the same range or ~2× faster [A].

| Model | N labels | Time per member [M] |
|---|---|---|
| Logistic regression 512-d | 500 | **70–90 ms** |
| MLP 512→64→1 | 500 | **~3.3 s** |
| MLP 512→128→1 | 500 | ~6.6–9.5 s |
| MLP 512→64→1 | 3,000 | ~20–28 s |

The "500 labels → MLP < 1 s" figure holds **for logistic regression, not for an MLP
trained naively**. To keep a round's training under ~5 s:
- train the 5 ensemble members **in parallel** (one thread each; the S26U has 8 cores);
- use **early stopping**, which typically stops at 40–80 epochs, i.e. 2.5–5× less work;
- if that's still slow, **project to 128-d with PCA** fitted once on the library's
  embeddings (64× fewer multiply-adds in the first layer: 512×64 → 128×32), which
  costs very little accuracy for a binary task [A];
- the nightly retrains with thousands of labels run on the charger, where 30 s is
  irrelevant.

Rescoring 20 k photos after training: 20 k × 5 members × ~33 k MACs ≈ 3.3 G MACs,
about **1–3 s** off the UI thread [A from M]. With PCA-128 it is ~0.1 s.

Memory: embeddings for 3 k labels × 512 × 4 B ≈ 6 MB. Negligible.

---

## 6. Evaluation

- **Check set (held out):**
  - 10 per initial round and 6 per monthly review, drawn blind and uniformly from
    unlabelled group representatives (§2.2);
  - never trained on; split by group and day;
  - it is the **only** source of the numbers on the results and History screens.
  - Active-learning picks are deliberately unrepresentative, so they are never used to
    estimate accuracy; they only feed the "agreed with you on hard photos" line.
- **Metrics:**
  - **precision@show** on the check photos above `τ_show`, with a **Wilson 95 %
    interval**, shown as a range in the UI;
  - **recall@show**;
  - AUC and Brier score in the Debug tab;
  - per-version confusion thumbnails in Debug ("check photos it got wrong").
- **Honesty about small n:**
  - after 3 rounds there are only 30 check photos, so the interval is wide
    (e.g. 93 % [80–98 %]);
  - the UI shows the range, and the promotion gate (§2.4) uses differences on the same
    photos (paired), not the raw values;
  - for a solid number, the History screen offers **"Check accuracy: 60 random photos,
    ~2 min"** (all check, nothing trained). With ~100 check photos (after the initial
    rounds + 1 accuracy check + ~4 months) the interval narrows to about ±5 points;
  - this matches STRATEGY §4, which asks for 300 for ±4 points at the "show" decision.
- **Learning-curve guidance:**

| Labels (groups) | Expected [A] | What to do |
|---|---|---|
| 0 | zero-shot (A) | |
| ~40 (R1) | ≥ zero-shot; fixes your most frequent junk clusters (targets, panels, garage) | |
| ~120 (R1–R3) | clearly above zero-shot; logistic ≈ MLP | default stop point |
| 300–500 | near the plateau of the frozen encoder; the MLP starts to beat logistic | monthly top-ups + swipes get you here in ~3–6 months |
| > 500 with precision still < 95 % | the frozen embedding can't separate your classes | try B2 (encoder fine-tune off the phone) with the exported labels |

- Plot it: the History screen's curve (check precision against cumulative labels) is
  the learning curve. If two consecutive versions add > 50 labels and gain < 1 point,
  more labelling isn't helping, and that is the trigger to consider B2.
- **Live check** (from STRATEGY): count swipe-outs per 100 photos shown. More than 3
  means a regression, and it is visible in the Debug tab.

---

## 7. Implementation order (estimate)

1. Backfill embedding job + `dup_group` + representative choice: ~1 day (A's encoder
   is reused).
2. Tables, label writing from A's swipes, Labels list: ~0.5 day.
3. Kotlin trainer (logistic + MLP ensemble, Adam, early stopping, Platt, PCA option)
   plus unit tests on synthetic data: ~1 day.
4. Active-learning sampler + check-set logic: ~0.5 day.
5. Train screen, results, History, Home card, notification: ~2 days.
6. A/B combination in the sampler, explore pool, night-mode rule: ~0.5 day.

Total ~5–6 days. B2 (PC fine-tune script + encoder-hash checks + re-embed flow) would
be +2–3 days later, if needed.

---

## 8. Open questions for you

1. **Round size and count:** are 3 rounds × 40 photos (~6–8 min) up front OK, or
   would you prefer 2 × 50, or a grid mode (12 at a time, tap the ones to discard)?
2. **Monthly batch:** is ≤ 24 photos and a notification fine, or should it be only the
   Home card (no notification)?
3. **What does a swipe-out on the display mean?** "Junk, never again" (the current
   plan, weight 0.7) or sometimes "seen enough for now"? If it's both, we could add
   two gestures (swipe = skip for a while, long-press = never), so B isn't taught that
   good photos are junk.
4. **Explore at night:** 0 % (the default) or a few? Explore is where new photos get
   their first chance.
5. **Background indexing:** OK to embed the whole library on the charger (~15–30 min
   once, then incremental)? B's active learning needs it; A alone does not.
6. **Path B2:** would you ever run a Python script on your PC (photos stay on your
   machines) if the trained head plateaus, or should everything stay on the phone
   forever?
7. **Burst pick:** auto-pick the best photo of a burst, or let you choose once per
   burst during training rounds (slower, but exact)?

---

## Sources

- ONNX Runtime on-device training: [Android app tutorial](https://onnxruntime.ai/docs/tutorials/on-device-training/android-app.html) · [overview](https://onnxruntime.ai/docs/get-started/training-on-device.html) · [`generate_artifacts`](https://onnxruntime.ai/docs/api/python/on_device_training/training_artifacts.html) · [Microsoft deep-dive (2023)](https://opensource.microsoft.com/blog/2023/07/05/on-device-training-with-onnx-runtime-a-deep-dive)
- `onnxruntime-training-android` latest 1.19.2: [Maven Central](https://central.sonatype.com/artifact/com.microsoft.onnxruntime/onnxruntime-training-android) · [mvnrepository](https://mvnrepository.com/artifact/com.microsoft.onnxruntime/onnxruntime-training-android/versions); `onnxruntime-android` 1.27.0: [Maven Central](https://central.sonatype.com/artifact/com.microsoft.onnxruntime/onnxruntime-android/1.27.0)
- LiteRT on-device training (train/infer/save/restore signatures): [LiteRT guide](https://developers.google.com/edge/litert/conversion/tensorflow/build/ondevice_training) · [TF blog 2021](https://blog.tensorflow.org/2021/11/on-device-training-in-tensorflow-lite.html)
- CLIP zero-shot vs few-shot linear probes: [Radford et al. 2021](https://arxiv.org/abs/2103.00020) · [Cherti et al., scaling laws](https://arxiv.org/pdf/2212.07143) · [Silva-Rodriguez et al., CVPR 2024 supp.](https://openaccess.thecvf.com/content/CVPR2024/supplemental/Silva-Rodriguez_A_Closer_Look_CVPR_2024_supplemental.pdf)
- Active learning with foundation-model features: [Gupte et al. 2024](https://arxiv.org/abs/2401.14555) · [SUPClust](https://arxiv.org/pdf/2403.03741) · [Uncertainty-scaled core-set](https://arxiv.org/pdf/2202.04251)
- Earlier research and model/runtime choices: [`../STRATEGY.md`](../STRATEGY.md)

**Caveat:** source pages were read through search-result extracts, not opened in full.
The training timings are from a 4-vCPU sandbox JVM. All S26 Ultra figures are
estimates to measure in A's benchmark Activity.
