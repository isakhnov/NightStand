package com.nightstand.smart

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.nightstand.Palette
import com.nightstand.Prefs
import com.nightstand.pages.DisplayData
import com.nightstand.pages.PageContainer
import com.nightstand.pages.PhotosPage
import com.nightstand.photos.smart.Candidate
import com.nightstand.photos.smart.Embedder
import com.nightstand.photos.smart.EmbedderHost
import com.nightstand.photos.smart.ModelStore
import com.nightstand.photos.smart.PhotoIndexDb
import com.nightstand.photos.smart.PhotoSource
import com.nightstand.photos.smart.PromptTable
import com.nightstand.photos.smart.SmartPhotos
import com.nightstand.photos.smart.Vec
import com.nightstand.photos.smart.Worker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlin.random.Random

/** Runs posted work at once; delayed work when [advance]d past it. */
class TestWorker : Worker {
    var now = 0L
    private val delayed = mutableListOf<Pair<Long, Runnable>>()
    override fun post(r: Runnable) = r.run()
    override fun postDelayed(r: Runnable, ms: Long) { delayed += (now + ms) to r }
    override fun remove(r: Runnable) { delayed.removeAll { it.second === r } }
    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val due = delayed.filter { it.first <= end }.minByOrNull { it.first } ?: break
            delayed.remove(due); now = due.first; due.second.run()
        }
        now = end
    }
}

/** A fake photo library: id → (time, embedding). Thumbnails carry the id in pixel (0,0). */
class FakeLibrary(val photos: MutableMap<Long, Pair<Long, FloatArray>>) : PhotoSource {
    var granted = true
    override fun candidates() = photos.map { (id, v) -> Candidate(id, Uri.parse("content://test/$id"), v.first, "b") }
    override fun thumbnail(c: Candidate): Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { setPixel(0, 0, 0xFF000000.toInt() or c.id.toInt()) }
    override fun decode(c: Candidate, maxSide: Int): Bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
    override fun isGranted() = granted
}

class FakeEmbedder(private val lib: FakeLibrary) : Embedder {
    var calls = 0; var closed = false
    override fun embed(thumb: Bitmap): FloatArray { calls++; return lib.photos[(thumb.getPixel(0, 0) and 0xFFFFFF).toLong()]!!.second }
    override fun close() { closed = true }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w915dp-h412dp-land-xhdpi")
class SmartAndroidTest {
    private val c: Context get() = ApplicationProvider.getApplicationContext()
    private val keepDir = V.rand(1000); private val junkDir = V.near(keepDir, 0f, 1001)
    private val prompts by lazy { PromptTable(listOf("memory", "utility"), booleanArrayOf(true, false), arrayOf(keepDir, junkDir)) }
    private lateinit var worker: TestWorker
    private lateinit var lib: FakeLibrary
    private val embedders = mutableListOf<FakeEmbedder>()
    private var modelPresent = true

    /** A "keep" photo: leans to the keep prompt, with its own direction ([seed]). */
    private fun keepVec(seed: Int) = Vec.normalize(FloatArray(V.DIM) { 0.5f * keepDir[it] + V.rand(seed)[it] })
    private fun junkVec(seed: Int) = Vec.normalize(FloatArray(V.DIM) { 0.5f * junkDir[it] + V.rand(seed)[it] })

    private fun engine(photos: Map<Long, Pair<Long, FloatArray>>): SmartPhotos {
        worker = TestWorker(); lib = FakeLibrary(photos.toMutableMap())
        return SmartPhotos(c, PhotoIndexDb(c, null), lib, worker,
            embedderFactory = { FakeEmbedder(lib).also { embedders += it } },
            promptsLoader = { prompts }, ui = { it.run() }, modelAvailable = { modelPresent },
            random = Random(7))
    }

    private fun SmartPhotos.pick(): Long? { var id: Long? = null; next(800) { id = it?.id }; return id }
    private val calls get() = embedders.sumOf { it.calls }

    @Before fun setUp() { Prefs.setPhotoSmart(c, true); Prefs.setPhotoLookAlike(c, Prefs.LookAlike.NORMAL); Prefs.setPhotoOnePerBurst(c, true) }
    @After fun tearDown() { SmartPhotos.setForTest(null); Prefs.setPhotoSmart(c, false) }

    // --- DB ---------------------------------------------------------------------------
    @Test fun db_insertQueryHideRestoreReset() {
        val db = PhotoIndexDb(c, null)
        val e = V.rand(1)
        db.saveScore(5, "content://x/5", 1000, "b", "v1", e, 3.5f, "dog", 0f, 10)
        val r = db.get(5)!!
        assertEquals(3.5f, r.zsMargin, 0f); assertEquals("dog", r.zsTop); assertTrue(Vec.cosine(e, r.embedding!!) > 0.9999f)
        assertEquals(1, db.embedded("v1").size); assertEquals(0, db.embedded("v2").size)
        val l = db.addLabel(5, 5, PhotoIndexDb.V_DISCARD, "swipe_out", 1f, 20)
        db.setState(listOf(5L), PhotoIndexDb.HIDDEN, l, 20)
        assertEquals(setOf(5L), db.hiddenIds()); assertEquals(1, db.counts().swipes); assertEquals(1, db.counts().hiddenByYou)
        db.setPinned(listOf(5L))
        assertEquals(PhotoIndexDb.OK, db.get(5)!!.state); assertTrue(db.get(5)!!.pinned)
        db.setState(listOf(5L), PhotoIndexDb.LOOKALIKE, l, 30)
        db.saveHead(ByteArray(8), 1, 30)
        db.resetLearning(40)
        assertEquals(PhotoIndexDb.OK, db.get(5)!!.state); assertEquals(0, db.counts().swipes); assertNull(db.loadHead())
        assertEquals(1, db.liveLabels().size) // only the reset marker
    }

    // --- engine: swipe → look-alikes, undo, restore, reset ------------------------------
    @Test fun hide_suppressesLookAlikesWithinRadius_undoAndRestore() {
        val base = keepVec(1)
        val photos = mapOf(
            1L to (1_000_000L to base),
            2L to (5_000_000L to V.near(base, 0.95f, 2)),   // look-alike (≥ 0.92)
            3L to (9_000_000L to V.near(base, 0.90f, 3)),   // inside Wide only
            4L to (13_000_000L to keepVec(4)),
        )
        val s = engine(photos)
        repeat(6) { s.pick() } // scores everything
        assertEquals(4, s.db.counts().scored)
        var label = -1L
        s.hide(1) { label = it }
        assertTrue(label > 0)
        assertEquals(PhotoIndexDb.HIDDEN, s.db.get(1)!!.state)
        assertEquals(PhotoIndexDb.LOOKALIKE, s.db.get(2)!!.state)
        assertEquals(PhotoIndexDb.OK, s.db.get(3)!!.state)
        repeat(10) { val id = s.pick(); assertTrue("$id", id == 3L || id == 4L) }
        // Wide catches #3 too; back to Normal releases it
        Prefs.setPhotoLookAlike(c, Prefs.LookAlike.WIDE); s.radiusChanged()
        assertEquals(PhotoIndexDb.LOOKALIKE, s.db.get(3)!!.state)
        Prefs.setPhotoLookAlike(c, Prefs.LookAlike.NORMAL); s.radiusChanged()
        assertEquals(PhotoIndexDb.OK, s.db.get(3)!!.state)
        // Undo (toast) brings back the photo and its look-alikes
        s.undo(label)
        assertEquals(PhotoIndexDb.OK, s.db.get(1)!!.state); assertEquals(PhotoIndexDb.OK, s.db.get(2)!!.state)
        assertEquals(0, s.db.counts().swipes)
        // Restore from the Hidden screen: pinned, a positive label, never auto-hidden again
        s.hide(1)
        s.restore(listOf(2L))
        assertTrue(s.db.get(2)!!.pinned)
        assertEquals(1, s.db.liveLabels(PhotoIndexDb.V_SHOW).size)
        s.restore(listOf(1L))
        assertEquals(PhotoIndexDb.OK, s.db.get(1)!!.state)
        // Reset learning
        s.hide(4); s.resetLearning()
        assertEquals(0, s.db.counts().hiddenByYou); assertEquals(0, s.db.counts().swipes)
    }

    @Test fun junkIsSkipped_andHeadLearnsFromSwipes() {
        val photos = (1L..12L).associateWith { (it * 100_000L) to (if (it <= 3) junkVec(it.toInt()) else keepVec(it.toInt())) }
        val s = engine(photos)
        repeat(30) { val id = s.pick(); assertTrue("junk $id shown", id!! > 3) }
        // the user swipes three photos that lean one way → the head learns it
        val cat = V.rand(77)
        val more = (20L..25L).associateWith { (it * 100_000L) to Vec.normalize(FloatArray(V.DIM) { 0.5f * keepDir[it] + 0.9f * cat[it] + 0.4f * V.rand(it.toInt())[it] }) }
        lib.photos.putAll(more)
        val s2 = SmartPhotos(c, s.db, lib, worker, { FakeEmbedder(lib) }, { prompts }, { it.run() }, { true }, random = Random(3))
        listOf(20L, 21L, 22L).forEach { s2.hide(it) }
        val head = com.nightstand.photos.smart.OnlineHead.fromBlob(V.DIM, s2.db.loadHead())
        assertEquals(3, head.negatives)
        assertTrue("similar photo pushed down", head.logit(more[24L]!!.second) < head.logit(photos[5L]!!.second))
    }

    @Test fun burst_onePerBurstShowsTheBest() {
        val a = keepVec(30)
        val best = Vec.normalize(FloatArray(V.DIM) { a[it] + 0.15f * keepDir[it] }) // same scene, clearer "keep"
        val photos = mapOf(
            1L to (60_000L to a), 2L to (61_000L to best), 3L to (62_500L to V.near(a, 0.97f, 31)),
            9L to (900_000L to keepVec(90)),
        )
        val s = engine(photos)
        repeat(8) { s.pick() }
        val states = (1L..3L).map { s.db.get(it)!!.state }
        assertEquals(1, states.count { it == PhotoIndexDb.OK })
        assertEquals(2, states.count { it == PhotoIndexDb.BURST })
        val shown = (1..20).mapNotNull { s.pick() }.toSet()
        assertEquals(2, shown.size) // the burst's best + photo 9
        Prefs.setPhotoOnePerBurst(c, false)
        assertEquals(4, (1..40).mapNotNull { s.pick() }.toSet().size)
    }

    // --- memory: the interpreter only when something unscored needs embedding --------
    @Test fun model_loadsOnlyForUnscored_closesWhenIdleOrHidden() {
        val photos = (1L..5L).associateWith { (it * 100_000L) to keepVec(it.toInt()) }
        val s = engine(photos)
        repeat(8) { s.pick() }
        assertEquals(5, calls)
        assertEquals(EmbedderHost.State.LOADED, s.host.state)
        worker.advance(31_000)
        assertEquals(EmbedderHost.State.UNLOADED, s.host.state); assertTrue(embedders.last().closed)
        val loads = s.host.loads
        repeat(20) { assertNotNull(s.pick()) }
        assertEquals("scored photos never load the model", loads, s.host.loads); assertEquals(5, calls)
        // a new photo appears: the model loads for it alone; onTrimMemory drops it at once
        lib.photos[6L] = 600_000L to keepVec(6)
        val s2 = SmartPhotos(c, s.db, lib, worker, { FakeEmbedder(lib).also { embedders += it } }, { prompts }, { it.run() }, { true }, random = Random(9))
        repeat(12) { s2.pick() }
        assertEquals(6, calls); assertEquals(EmbedderHost.State.LOADED, s2.host.state)
        s2.trimMemory()
        assertEquals(EmbedderHost.State.UNLOADED, s2.host.state)
        repeat(5) { s2.pick() }
        assertEquals(EmbedderHost.State.UNLOADED, s2.host.state)
    }

    @Test fun trickle_scoresInBackgroundWhilePageUp_capped() {
        val photos = (1L..20L).associateWith { (it * 100_000L) to keepVec(it.toInt()) }
        val s = engine(photos)
        s.pageShown(false)
        worker.advance(5_000 + 3_000L * 10)
        assertTrue(s.db.counts().scored >= 10)
        s.pageHidden()
        val n = s.db.counts().scored
        worker.advance(60_000)
        assertEquals("stops when the page hides", n, s.db.counts().scored)
        assertEquals(EmbedderHost.State.UNLOADED, s.host.state)
        s.db.setMeta("trickle_count", "${SmartPhotos.TRICKLE_CAP}")
        s.pageShown(false); worker.advance(60_000)
        assertEquals("cap reached", n, s.db.counts().scored)
    }

    // --- Smart off / model missing: old random behaviour, no model ---------------------
    @Test fun smartOff_andModelMissing_areRandomAndNeverLoadTheModel() {
        val photos = (1L..6L).associateWith { (it * 100_000L) to (if (it <= 3) junkVec(it.toInt()) else keepVec(it.toInt())) }
        Prefs.setPhotoSmart(c, false)
        val s = engine(photos)
        val seen = (1..30).mapNotNull { s.pick() }.toSet()
        assertEquals("random shows everything, junk too", 6, seen.size)
        assertEquals(0, calls); assertTrue(s.lastMode.startsWith("random"))
        Prefs.setPhotoSmart(c, true); modelPresent = false
        assertEquals(6, (1..30).mapNotNull { s.pick() }.toSet().size)
        assertEquals(0, calls); assertEquals("random (model missing)", s.lastMode)
        // hiding still works without the model (that photo only)
        s.hide(2)
        assertFalse((1..30).mapNotNull { s.pick() }.contains(2L))
        // runtime failure → random, no crash
        modelPresent = true
        val broken = SmartPhotos(c, PhotoIndexDb(c, null), lib, worker, { throw IllegalStateException("no LiteRT") }, { prompts }, { it.run() }, { true })
        assertNotNull(broken.pick()); assertEquals(EmbedderHost.State.FAILED, broken.host.state); assertFalse(broken.smartActive())
    }

    @Test fun page_showsPhotosWithSmartOffAndModelMissing() {
        for ((smart, model) in listOf(false to true, true to false)) {
            Prefs.setPhotoSmart(c, smart); modelPresent = model; embedders.clear()
            val s = engine((1L..4L).associateWith { (it * 1000L) to keepVec(it.toInt()) })
            SmartPhotos.setForTest(s)
            val page = PhotosPage(c, DisplayData())
            layout(page)
            page.onShown()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("smart=$smart model=$model shows a photo", page.currentId in 1L..4L)
            assertEquals(0, calls)
            page.onHidden()
        }
    }

    // --- gestures ----------------------------------------------------------------------
    private fun layout(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(1830, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(824, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1830, 824)
        v.draw(Canvas(Bitmap.createBitmap(1830, 824, Bitmap.Config.ARGB_8888)))
    }
    private fun touch(v: View, action: Int, x: Float, y: Float, t: Long) = v.dispatchTouchEvent(MotionEvent.obtain(0, t, action, x, y, 0))
    private fun drag(v: View, x: Float, y0: Float, y1: Float) {
        touch(v, MotionEvent.ACTION_DOWN, x, y0, 0)
        touch(v, MotionEvent.ACTION_MOVE, x + 2, (y0 + y1) / 2, 40)
        touch(v, MotionEvent.ACTION_MOVE, x + 3, y1, 80)
        touch(v, MotionEvent.ACTION_UP, x + 3, y1, 120)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun gestures_downOnPhotoHidesByDay_notAtNight_brightnessOnlyOnClockHalf() {
        Prefs.setPhotoSmart(c, false)
        val s = engine((1L..4L).associateWith { (it * 1000L) to keepVec(it.toInt()) } + (42L to (99_000L to keepVec(42))))
        SmartPhotos.setForTest(s)
        val container = PageContainer(c)
        val page = PhotosPage(c, DisplayData())
        container.setPages(listOf(page), PhotosPage.ID)
        var bright = 0f; var brightCalls = 0
        container.onBrightnessDrag = { bright += it; brightCalls++; null }
        val sample = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)

        // night: down-swipe on the photo does nothing (and is not brightness either)
        container.palette = Palette.night()
        page.showForPreview(sample, 6_000, mediaId = 42)
        layout(container)
        drag(container, 1300f, 200f, 650f)
        assertNull(s.db.get(42)?.takeIf { it.state != PhotoIndexDb.OK })
        assertEquals(0, brightCalls)
        // night: brightness on the clock half still works
        drag(container, 400f, 700f, 300f)
        assertTrue(brightCalls > 0 && bright > 0)

        // day: up-drag on the photo is not brightness; down-swipe hides it
        container.palette = Palette.DAY
        page.showForPreview(sample, 6_000, mediaId = 42)
        layout(container)
        brightCalls = 0
        drag(container, 1300f, 650f, 250f)
        assertEquals(0, brightCalls); assertNull(s.db.get(42))
        drag(container, 1300f, 200f, 650f)
        assertEquals(PhotoIndexDb.HIDDEN, s.db.get(42)!!.state)
        assertEquals(0, brightCalls)
        assertNotNull("Undo toast up", page.toastLabel)
        assertTrue("next photo shown", page.currentId in 1L..4L)
        // a short drag springs back (no hide)
        val shownNow = page.currentId
        page.showForPreview(sample, 6_000, mediaId = shownNow)
        drag(container, 1300f, 200f, 300f)
        assertEquals(PhotoIndexDb.OK, s.db.get(shownNow)?.state ?: PhotoIndexDb.OK)
        // the clock half by day: brightness handler (day mode just explains itself)
        drag(container, 400f, 700f, 300f)
        assertTrue(brightCalls > 0)
    }

    // --- prompt table (shipped asset) against the Python fp32 golden vectors ---
    @Test fun promptTable_matchesPythonZeroShot() {
        val t = PromptTable.parse(java.io.File("src/main/assets/smart/prompts_v2.bin").readBytes())
        assertEquals(52, t.size); assertEquals(27, t.keep.count { it })
        assertTrue(t.keep[t.ids.indexOf("firearm_coll")]); assertTrue(t.keep[t.ids.indexOf("handgun")])
        assertFalse(t.keep[t.ids.indexOf("food")]); assertFalse(t.keep[t.ids.indexOf("shooting_target")])
        val gold = org.json.JSONArray(java.io.File("src/test/resources/smart/golden_embeddings.json").readText())
        for (i in 0 until gold.length()) {
            val g = gold.getJSONObject(i)
            val arr = g.getJSONArray("embedding"); val e = FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
            val zs = t.zeroShot(e)
            assertEquals(g.getString("name"), g.getString("top"), zs.top)
            assertEquals(g.getString("name"), g.getDouble("margin").toFloat(), zs.margin, 0.05f)
        }
    }

    @Test fun preprocess_shortSideTo256AndCentreCrop() {
        val b = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888)
        Canvas(b).apply { drawColor(0xFF0000FF.toInt()); drawRect(0f, 0f, 512f, 768f, android.graphics.Paint().apply { color = 0xFFFF0000.toInt() }) }
        val px = com.nightstand.photos.smart.Preprocess.pixels(b)
        assertEquals(256 * 256, px.size)
        // 1024×768 → 341×256, crop x 42..298: left half red, right half blue, split near x = 128
        assertEquals(0xFFFF0000.toInt(), px[10]); assertEquals(0xFF0000FF.toInt(), px[250])
        val t = com.nightstand.photos.smart.Preprocess.toTensor(px, nhwc = true)
        assertEquals(1f, t[0], 0f); assertEquals(0f, t[2], 0f)
    }

    // --- model file: SHA-256 gate ------------------------------------------------------
    @Test fun modelStore_installChecksSha256() {
        val bytes = ByteArray(300_000) { (it * 31).toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertFalse(ModelStore.install(c, ByteArrayInputStream(bytes), bytes.size.toLong(), expectedSha = "00$sha".take(64)))
        assertFalse(ModelStore.file(c).exists())
        assertTrue(ModelStore.state.value is ModelStore.State.Failed)
        assertTrue(ModelStore.install(c, ByteArrayInputStream(bytes), bytes.size.toLong(), expectedSha = sha))
        assertEquals(bytes.size.toLong(), ModelStore.file(c).length())
        assertEquals(ModelStore.State.Ready, ModelStore.state.value)
        ModelStore.delete(c)
        assertFalse(ModelStore.isInstalled(c))
    }
}
