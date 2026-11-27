package com.nightstand.photos.smart

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import com.nightstand.Prefs
import com.nightstand.WatcherService
import java.util.Calendar
import kotlin.random.Random

/** A photo the Photos page can show. */
data class Candidate(val id: Long, val uri: Uri, val takenMs: Long, val bucket: String?)

/** Where photos come from (MediaStore in the app; a fake in tests). */
interface PhotoSource {
    /** Photos for the current source setting (all / favourites / album). */
    fun candidates(): List<Candidate>
    /** Small (≥ 256 px short side) bitmap to embed. */
    fun thumbnail(c: Candidate): Bitmap?
    fun decode(c: Candidate, maxSide: Int): Bitmap?
    fun isGranted(): Boolean = true
}

/**
 * Smart selection for the Photos page (Step A, docs/photos-intelligence/step-a).
 *
 * Everything heavy runs on ONE background thread ([Worker]): thumbnails, the
 * image encoder, SQLite writes, head refits, the look-ahead queue and the
 * background trickle. Callbacks arrive on the UI thread.
 *
 *  - Smart off, model missing or the LiteRT runtime unavailable → the old
 *    behaviour (random order), minus photos the user hid. Never crashes.
 *  - Smart on → each candidate is scored once (embedding + zero-shot margin in
 *    the DB), judged by prior + personal head − look-alike penalty, one per
 *    burst, and ranked with a little recency/diversity.
 *  - A swipe = "junk, never again": label, hide the burst, hide look-alikes
 *    within the radius, refit the head. Undo/restore reverse it.
 */
class SmartPhotos internal constructor(
    private val app: Context,
    val db: PhotoIndexDb,
    private val source: PhotoSource,
    private val worker: Worker,
    embedderFactory: () -> Embedder?,
    private val promptsLoader: () -> PromptTable?,
    private val ui: (Runnable) -> Unit,
    private val modelAvailable: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    val host = EmbedderHost(embedderFactory, worker)
    private var prompts: PromptTable? = null
    private var promptsTried = false
    private var head: OnlineHead? = null
    private val look = LookAhead<Candidate, Scored>(depth = 3, maxTries = 20)
    private var queue = ArrayDeque<Candidate>()
    private var byTime: List<Candidate> = emptyList()
    private var byId: Map<Long, Candidate> = emptyMap()
    private var sourceKey: String? = null
    private var trickleQueue = ArrayDeque<Candidate>()
    private val recentShown = ArrayDeque<FloatArray>()
    /** Live swipe-outs: (label id, embedding), for look-alike checks. */
    private var swiped: List<Pair<Long, FloatArray>>? = null

    @Volatile var night = false
    @Volatile var visible = false; private set
    @Volatile var lastMode = "—"; private set
    @Volatile var trickledTonight = 0; private set

    class Scored(val c: Candidate, val e: FloatArray, val logit: Float, val lastShown: Long, val zsTop: String?)
    class Pick(val id: Long, val bitmap: Bitmap, val smart: Boolean, val note: String?)

    // --- public API (any thread) ----------------------------------------------------

    /** Smart wanted by the user AND model file present AND runtime not failed. */
    fun smartActive(): Boolean =
        Prefs.photoSmart(app) && modelAvailable() && host.state != EmbedderHost.State.FAILED

    /** Next photo to show (decoded, long side ≤ [maxSide]); null if none. */
    fun next(maxSide: Int, cb: (Pick?) -> Unit) = worker.post {
        val p = runCatching { nextBlocking(maxSide) }.onFailure { WatcherService.log("photos: $it") }.getOrNull()
        ui.invoke(Runnable { cb(p) })
        if (p?.smart == true) worker.post { safe { fillLook() } }
    }

    /** Swipe-out: label + hide burst + look-alikes + refit. [cb] gets the label id for Undo. */
    fun hide(mediaId: Long, cb: (Long) -> Unit = {}) = worker.post {
        val id = safe { hideBlocking(mediaId) } ?: return@post
        ui.invoke(Runnable { cb(id) })
    }

    fun undo(labelId: Long, cb: () -> Unit = {}) = worker.post { safe { undoBlocking(labelId) }; ui.invoke(Runnable(cb)) }

    fun restore(ids: Collection<Long>, cb: () -> Unit = {}) = worker.post { safe { restoreBlocking(ids) }; ui.invoke(Runnable(cb)) }

    fun resetLearning(cb: () -> Unit = {}) = worker.post {
        safe { db.resetLearning(clock()); head = null; swiped = null; look.clear() }
        ui.invoke(Runnable(cb))
    }

    /** Look-alike radius changed: re-derive which photos count as look-alikes. */
    fun radiusChanged() = worker.post { safe { resuppressAll(); look.clear() } }

    fun pageShown(isNight: Boolean) {
        night = isNight; visible = true
        // A failed runtime start (e.g. Play services still fetching LiteRT) is retried once per showing.
        worker.post { host.clearFailure() }
        worker.remove(trickle); worker.postDelayed(trickle, TRICKLE_START_MS)
    }

    fun pageHidden() {
        visible = false
        worker.remove(trickle)
        worker.post { host.close("page hidden") }
    }

    /** onTrimMemory / screen off: drop the interpreter now. */
    fun trimMemory() = worker.post { host.close("trim") }

    /** Model just installed: allow loading again and start over. */
    fun modelChanged() = worker.post { host.clearFailure(); host.close("model changed"); look.clear() }

    // --- selection (worker thread) --------------------------------------------------

    private fun nextBlocking(maxSide: Int): Pick? {
        refreshCandidates()
        if (!source.isGranted() || byTime.isEmpty()) return null
        if (smartActive() && loadPrompts() != null) {
            fillLook()
            val now = clock()
            val s = look.take(stillOk = ::stillOk) {
                Scoring.rank(it.logit, it.e, it.c.takenMs, it.lastShown, recentShown, now, random.nextFloat() * 0.3f)
            } ?: bestFromDb()
            if (s != null) {
                val bmp = source.decode(s.c, maxSide)
                if (bmp != null) {
                    lastMode = "smart"
                    db.markShown(s.c.id, now)
                    recentShown.addLast(s.e); while (recentShown.size > 10) recentShown.removeFirst()
                    return Pick(s.c.id, bmp, true, s.zsTop)
                }
            }
            lastMode = if (host.state == EmbedderHost.State.FAILED) "random (model failed)" else "random (nothing eligible)"
        } else lastMode = when {
            !Prefs.photoSmart(app) -> "random (Smart off)"
            !modelAvailable() -> "random (model missing)"
            else -> "random (Smart unavailable)"
        }
        return randomPick(maxSide)
    }

    private fun randomPick(maxSide: Int): Pick? {
        val hidden = db.hiddenIds()
        var guard = byTime.size + 1
        while (guard-- > 0) {
            if (queue.isEmpty()) queue = ArrayDeque(byTime.shuffled(random))
            val c = queue.removeFirstOrNull() ?: return null
            if (c.id in hidden) continue
            val bmp = source.decode(c, maxSide) ?: continue
            db.get(c.id)?.let { db.markShown(c.id, clock()) }
            return Pick(c.id, bmp, false, null)
        }
        return null
    }

    private fun refreshCandidates() {
        val key = "${Prefs.photoSource(app)}:${Prefs.photoAlbum(app)?.first}"
        if (key == sourceKey && byTime.isNotEmpty()) return
        val all = source.candidates()
        sourceKey = key
        byTime = all.sortedWith(compareBy({ it.takenMs }, { it.id }))
        byId = all.associateBy { it.id }
        queue = ArrayDeque(all.shuffled(random))
        trickleQueue = ArrayDeque(all.shuffled(random))
        look.clear()
    }

    private fun nextCandidate(): Candidate? {
        if (queue.isEmpty()) queue = ArrayDeque(byTime.shuffled(random))
        return queue.removeFirstOrNull()
    }

    private fun fillLook() {
        if (!smartActive() || loadPrompts() == null) return
        refreshCandidates()
        look.fill(::nextCandidate) { evaluate(it) }
    }

    /** Score [c] (from the DB if already scored, else with the model) and judge it; null = don't show. */
    internal fun evaluate(c: Candidate): Scored? {
        var row = db.get(c.id)
        if (row != null && hiddenByUser(row)) return null
        if (row?.embedding == null || row.modelVersion != ModelStore.VERSION) {
            embedAndStore(c, withBurst = true) ?: return null
            row = db.get(c.id) ?: return null
            if (hiddenByUser(row)) return null
        }
        return judge(c, row)
    }

    /** Swiped away, or a look-alike of a swiped photo (bursts are judged separately). */
    private fun hiddenByUser(r: PhotoIndexDb.Row) = !r.pinned && (r.state == PhotoIndexDb.HIDDEN || r.state == PhotoIndexDb.LOOKALIKE)

    private fun judge(c: Candidate, row: PhotoIndexDb.Row): Scored? {
        if (hiddenByUser(row)) return null
        val e = row.embedding ?: return null
        if (row.state == PhotoIndexDb.BURST && !row.pinned && Prefs.photoOnePerBurst(app)) return null
        val radius = Prefs.photoLookAlike(app).cos
        val (nearest, by) = nearestSwiped(e)
        if (!row.pinned && Scoring.suppressed(nearest, radius)) {
            db.setState(listOf(c.id), PhotoIndexDb.LOOKALIKE, by, clock()); return null
        }
        val logit = Scoring.logit(row.zsMargin, head().logit(e), if (row.pinned) -1f else nearest, radius)
        if (!row.pinned && !Scoring.eligible(logit, night)) return null
        return Scored(c, e, logit, row.lastShownAt, row.zsTop)
    }

    private fun stillOk(s: Scored): Boolean {
        val row = db.get(s.c.id) ?: return false
        return judge(s.c, row) != null
    }

    /** Fallback when the look-ahead found nothing: the best already-scored eligible photo. */
    private fun bestFromDb(): Scored? {
        val now = clock()
        return db.rows("state IN (0,3) OR pinned=1", order = "RANDOM()", limit = 200)
            .filter { it.modelVersion == ModelStore.VERSION }
            .mapNotNull { r -> byId[r.mediaId]?.let { judge(it, r) } }
            .maxByOrNull { Scoring.rank(it.logit, it.e, it.c.takenMs, it.lastShown, recentShown, now) }
    }

    // --- scoring ------------------------------------------------------------------------

    private fun loadPrompts(): PromptTable? {
        if (!promptsTried) { promptsTried = true; prompts = runCatching { promptsLoader() }.getOrNull() }
        return prompts
    }

    private fun head(): OnlineHead = head ?: OnlineHead.fromBlob(EMBED_DIM, runCatching { db.loadHead() }.getOrNull()).also { head = it }

    /** Embed one photo with the model and save it; null if it can't be read or the model can't run. */
    private fun embedAndStore(c: Candidate, withBurst: Boolean): FloatArray? {
        val p = loadPrompts() ?: return null
        val thumb = runCatching { source.thumbnail(c) }.getOrNull() ?: return null
        val e = host.embed(thumb) ?: return null
        val zs = p.zeroShot(e)
        db.saveScore(c.id, c.uri.toString(), c.takenMs, c.bucket, ModelStore.VERSION, e, zs.margin, zs.top, head().logit(e), clock())
        if (withBurst) runCatching { groupBurst(c) }
        return e
    }

    /** Burst = chain of photos ≤ 3 s apart that look alike (cos ≥ 0.9); best one stays, the rest → state 3. */
    private fun groupBurst(c: Candidate) {
        val i = byTime.indexOfFirst { it.id == c.id }.takeIf { it >= 0 } ?: return
        var lo = i; var hi = i
        while (lo > 0 && byTime[lo].takenMs - byTime[lo - 1].takenMs <= BurstGrouper.GAP_MS && i - lo < 40) lo--
        while (hi < byTime.size - 1 && byTime[hi + 1].takenMs - byTime[hi].takenMs <= BurstGrouper.GAP_MS && hi - i < 40) hi++
        if (lo == hi) return
        val items = (lo..hi).mapNotNull { k ->
            val n = byTime[k]
            val r = db.get(n.id)
            val e = r?.embedding?.takeIf { r.modelVersion == ModelStore.VERSION } ?: embedAndStore(n, withBurst = false)
            e?.let { BurstGrouper.Item(n.id, n.takenMs, it) }
        }
        val now = clock()
        for (g in BurstGrouper.group(items)) {
            if (g.size < 2) continue
            val rows = g.associate { it.id to db.get(it.id)!! }
            val best = BurstGrouper.best(g) { Scoring.prior(rows[it.id]!!.zsMargin) + head().logit(it.embedding) }
            db.saveGroup(g.map { it.id }, best.id, g.first().takenMs, g.last().takenMs, now)
            val others = g.filter { it.id != best.id && rows[it.id]!!.state == PhotoIndexDb.OK && !rows[it.id]!!.pinned }.map { it.id }
            db.setState(others, PhotoIndexDb.BURST, null, now)
            if (rows[best.id]!!.state == PhotoIndexDb.BURST) db.setState(listOf(best.id), PhotoIndexDb.OK, null, now)
        }
    }

    private fun nearestSwiped(e: FloatArray): Pair<Float, Long?> {
        var best = -1f; var by: Long? = null
        for ((label, h) in swipedEmbeddings()) { val c = Vec.dot(e, h); if (c > best) { best = c; by = label } }
        return best to by
    }

    private fun swipedEmbeddings(): List<Pair<Long, FloatArray>> = swiped ?: db.liveLabels(PhotoIndexDb.V_DISCARD).mapNotNull { l ->
        db.get(l.mediaId)?.takeIf { it.modelVersion == ModelStore.VERSION }?.embedding?.let { l.id to it }
    }.also { swiped = it }

    // --- feedback ------------------------------------------------------------------------

    private fun hideBlocking(mediaId: Long): Long {
        val now = clock()
        refreshCandidates()
        val c = byId[mediaId]
        db.ensure(mediaId, c?.uri?.toString(), c?.takenMs ?: 0)
        var row = db.get(mediaId)!!
        // Learn from it even if it was never scored (e.g. shown while Smart was off).
        if ((row.embedding == null || row.modelVersion != ModelStore.VERSION) && c != null && smartActive()) {
            embedAndStore(c, withBurst = true); row = db.get(mediaId)!!
        }
        val gid = row.groupId ?: mediaId
        val label = db.addLabel(mediaId, gid, PhotoIndexDb.V_DISCARD, "swipe_out", 1f, now)
        val members = (if (row.groupId != null) db.groupMembers(gid) else emptyList()) + mediaId
        db.setState(members.toSet(), PhotoIndexDb.HIDDEN, label, now)
        swiped = null
        row.embedding?.takeIf { row.modelVersion == ModelStore.VERSION }?.let { suppress(it, label, now) }
        refitHead()
        look.removeIf { it.c.id in members }
        return label
    }

    /** Hide every scored photo within the radius of [e] (unless the user restored it). */
    private fun suppress(e: FloatArray, label: Long, now: Long) {
        val radius = Prefs.photoLookAlike(app).cos
        val ids = db.embedded(ModelStore.VERSION)
            .filter { it.state == PhotoIndexDb.OK && !it.pinned && it.embedding != null && Vec.dot(e, it.embedding) >= radius }
            .map { it.mediaId }
        db.setState(ids, PhotoIndexDb.LOOKALIKE, label, now)
        look.removeIf { it.c.id in ids }
    }

    private fun resuppressAll() {
        val now = clock()
        db.setState(db.idsInState(PhotoIndexDb.LOOKALIKE), PhotoIndexDb.OK, null, now)
        swiped = null
        for ((label, e) in swipedEmbeddings()) suppress(e, label, now)
    }

    private fun undoBlocking(labelId: Long) {
        val now = clock()
        val l = db.liveLabels(PhotoIndexDb.V_DISCARD).firstOrNull { it.id == labelId } ?: return
        val undo = db.addLabel(l.mediaId, l.groupId, PhotoIndexDb.V_UNDO, "undo", 0f, now)
        db.supersede(listOf(labelId), undo)
        db.setState(db.rows("suppressed_by=?", arrayOf("$labelId")).map { it.mediaId }, PhotoIndexDb.OK, null, now)
        swiped = null
        refitHead()
    }

    private fun restoreBlocking(ids: Collection<Long>) {
        val now = clock()
        for (id in ids) {
            val r = db.get(id) ?: continue
            when (r.state) {
                PhotoIndexDb.HIDDEN -> {
                    val l = r.suppressedBy
                    val pos = db.addLabel(id, r.groupId ?: id, PhotoIndexDb.V_SHOW, "restore", 1f, now)
                    if (l != null) {
                        db.supersede(listOf(l), pos)
                        db.setState(db.rows("suppressed_by=?", arrayOf("$l")).map { it.mediaId }, PhotoIndexDb.OK, null, now)
                    }
                }
                PhotoIndexDb.LOOKALIKE -> db.addLabel(id, r.groupId ?: id, PhotoIndexDb.V_SHOW, "restore", 1f, now)
                else -> {}
            }
            db.setPinned(listOf(id))
        }
        swiped = null
        refitHead()
    }

    /**
     * Refit the personal head on live labels: swipes (y=0), restores (y=1) and
     * anchors — kept photos with a positive prior that were shown and not
     * swiped — weighted to half the swipes, so one-sided swipes can't teach
     * "everything is junk".
     */
    private fun refitHead() {
        val h = head()
        val labels = db.liveLabels()
        fun ex(id: Long, y: Float, w: Float) = db.get(id)?.takeIf { it.modelVersion == ModelStore.VERSION }?.embedding?.let {
            OnlineHead.Example(it, y, w, Scoring.prior(db.get(id)!!.zsMargin))
        }
        val neg = labels.filter { it.value == PhotoIndexDb.V_DISCARD }.distinctBy { it.groupId }.mapNotNull { ex(it.mediaId, 0f, it.weight) }
        val pos = labels.filter { it.value == PhotoIndexDb.V_SHOW }.mapNotNull { ex(it.mediaId, 1f, it.weight) }
        val labelled = labels.map { it.mediaId }.toSet()
        val anchorRows = db.rows("state=0 AND embedding IS NOT NULL AND zs_margin>0 AND model_version=?", arrayOf(ModelStore.VERSION),
            order = "show_count DESC, RANDOM()", limit = 200).filter { it.mediaId !in labelled }
        val anchorW = if (anchorRows.isEmpty()) 0f else 0.5f * neg.sumOf { it.weight.toDouble() }.toFloat() / anchorRows.size
        val anchors = anchorRows.map { OnlineHead.Example(it.embedding!!, 1f, anchorW, Scoring.prior(it.zsMargin)) }
        h.fit(neg + pos + anchors)
        db.saveHead(h.toBlob(), h.updates, clock())
    }

    // --- background trickle ------------------------------------------------------------

    /** While the Photos page is up: score one extra unscored photo every few seconds, ≤ [TRICKLE_CAP] a night. */
    private val trickle: Runnable = object : Runnable {
        override fun run() {
            if (!visible) return
            val more = safe { trickleOne() } ?: false
            if (more && visible) worker.postDelayed(this, if (night) 10_000L else 3_000L)
        }
    }

    private fun trickleOne(): Boolean {
        if (!smartActive() || loadPrompts() == null) return false
        refreshCandidates()
        val key = nightKey(clock())
        if (db.meta("trickle_night") != key) { db.setMeta("trickle_night", key); db.setMeta("trickle_count", "0") }
        trickledTonight = db.meta("trickle_count")?.toIntOrNull() ?: 0
        if (trickledTonight >= TRICKLE_CAP) return false
        var tries = 0
        while (tries++ < 50) {
            val c = trickleQueue.removeFirstOrNull() ?: return false
            val r = db.get(c.id)
            if (r?.embedding != null && r.modelVersion == ModelStore.VERSION) continue
            embedAndStore(c, withBurst = true) ?: return host.state != EmbedderHost.State.FAILED
            db.get(c.id)?.takeIf { it.state == PhotoIndexDb.OK }?.let { judge(c, it) } // applies the look-alike rule now
            trickledTonight++
            db.setMeta("trickle_count", "$trickledTonight")
            return true
        }
        return true
    }

    private fun <T> safe(block: () -> T): T? = runCatching(block).onFailure { WatcherService.log("photos smart: $it") }.getOrNull()

    companion object {
        const val EMBED_DIM = 512
        const val TRICKLE_CAP = 300
        const val TRICKLE_START_MS = 5_000L

        /** Nights run evening → morning: the key changes at noon. */
        fun nightKey(now: Long): String {
            val c = Calendar.getInstance().apply { timeInMillis = now - 12 * 3_600_000L }
            return "${c.get(Calendar.YEAR)}-${c.get(Calendar.DAY_OF_YEAR)}"
        }

        @Volatile private var instance: SmartPhotos? = null
        fun peek(): SmartPhotos? = instance
        fun get(c: Context): SmartPhotos = instance ?: synchronized(this) { instance ?: create(c.applicationContext).also { instance = it } }
        /** Tests: install an engine with a fake source/embedder (null = back to the real one). */
        fun setForTest(s: SmartPhotos?) { instance = s }

        private fun create(app: Context): SmartPhotos {
            val t = HandlerThread("photo-smart", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
            val h = Handler(t.looper)
            val main = Handler(Looper.getMainLooper())
            return SmartPhotos(
                app, PhotoIndexDb(app), com.nightstand.photos.MediaStoreSource(app), HandlerWorker(h),
                embedderFactory = { TfLiteEmbedder.open(app, ModelStore.file(app)) },
                promptsLoader = { PromptTable.load(app) },
                ui = { main.post(it) },
                modelAvailable = { ModelStore.isInstalled(app) },
            )
        }
    }
}

class HandlerWorker(private val h: Handler) : Worker {
    override fun post(r: Runnable) { h.post(r) }
    override fun postDelayed(r: Runnable, ms: Long) { h.postDelayed(r, ms) }
    override fun remove(r: Runnable) = h.removeCallbacks(r)
}
