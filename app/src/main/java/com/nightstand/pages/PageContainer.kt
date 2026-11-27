package com.nightstand.pages

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.nightstand.Fonts
import com.nightstand.Palette
import kotlin.math.abs

/**
 * Hosts the pages and owns all touch on the Nightstand screen:
 *
 *  - swipe left/right  → next/previous page, wrapping around (cycle)
 *  - swipe up/down     → [onBrightnessDrag] (night brightness), with a label
 *  - tap / double-tap  → [onTap] / [onDoubleTap]
 *
 * Hand-rolled instead of ViewPager2 to stay dependency-free and to keep the
 * gesture arbitration (horizontal page vs vertical brightness vs taps) in one
 * place. Only the current page — and, mid-swipe, one neighbour — is visible.
 */
class PageContainer(context: Context) : FrameLayout(context) {

    var onTap: () -> Unit = {}
    var onDoubleTap: () -> Unit = {}
    /** [delta] = fraction of screen height dragged upward since last call. Returns label to flash, or null. */
    var onBrightnessDrag: (delta: Float) -> String? = { null }
    var onPageChanged: (Page) -> Unit = {}

    private val pages = mutableListOf<Page>()
    var index = 0
        private set
    val current: Page? get() = pages.getOrNull(index)

    var palette: Palette = Palette.DAY
        set(v) { field = v; pages.forEach { it.palette = v }; invalidate() }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private var downX = 0f; private var downY = 0f; private var lastY = 0f
    private var mode = 0 // 0 undecided, 1 horizontal, 2 vertical
    private var velocity: VelocityTracker? = null
    private var anim: ValueAnimator? = null

    /** Event time of the last single tap (touch clock, not wall clock); none yet = far past. */
    private var lastTapAt = Long.MIN_VALUE / 2
    private val doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val pendingTap = Runnable { onTap() }
    /** Main-thread handler (not View.post*, which defers while detached). */
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    // Overlay: page dots + transient label (brightness %, page name).
    private var overlayText: String? = null
    private var overlayUntil = 0L
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.bold; textAlign = Paint.Align.CENTER
    }

    init { setWillNotDraw(false) }

    fun setPages(list: List<Page>, startId: String?) {
        removeAllViews(); pages.clear(); pages += list
        list.forEach {
            it.palette = palette; it.visibility = View.INVISIBLE; addView(it, LayoutParams(-1, -1))
            // Power: a clock needs ≤ 1 redraw/s; let variable-refresh displays (S26 LTPO) idle low.
            if (android.os.Build.VERSION.SDK_INT >= 35) it.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_LOW)
        }
        index = list.indexOfFirst { it.pageId == startId }.coerceAtLeast(0)
        current?.let { it.visibility = View.VISIBLE; it.translationX = 0f }
    }

    fun refreshAll() = pages.forEach { it.onDataChanged() }

    // --- touch ----------------------------------------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity!!.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.cancel(); settleInstantly()
                downX = e.x; downY = e.y; lastY = e.y; mode = 0
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX; val dy = e.y - downY
                if (mode == 0) {
                    if (abs(dx) > slop && abs(dx) > abs(dy) && pages.size > 1) mode = 1
                    else if (abs(dy) > slop && abs(dy) > abs(dx)) mode = 2
                }
                if (mode == 1) layoutDrag(dx)
                if (mode == 2) {
                    onBrightnessDrag((lastY - e.y) / height)?.let { flash(it) }
                    lastY = e.y
                }
            }
            MotionEvent.ACTION_UP -> {
                if (mode == 1) {
                    velocity!!.computeCurrentVelocity(1000)
                    val vx = velocity!!.xVelocity
                    val dx = e.x - downX
                    val dir = when {
                        abs(vx) > 800 * density -> if (vx < 0) 1 else -1
                        abs(dx) > width / 4f -> if (dx < 0) 1 else -1
                        else -> 0
                    }
                    animateTo(dx, dir)
                } else if (mode == 0) {
                    // Controls on the page (e.g. play/pause) win over tap/double-tap
                    // and fire immediately, without the double-tap delay.
                    if (current?.onTapAt(e.x, e.y) != true) tap(e.eventTime)
                }
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == 1) animateTo(e.x - downX, 0)
                velocity?.recycle(); velocity = null
            }
        }
        return true
    }

    private fun tap(now: Long) {
        if (now - lastTapAt < doubleTapMs) {
            ui.removeCallbacks(pendingTap); lastTapAt = Long.MIN_VALUE / 2; onDoubleTap()
        } else {
            lastTapAt = now; ui.postDelayed(pendingTap, doubleTapMs)
        }
    }

    private fun neighbour(dir: Int) = pages[(index + dir + pages.size) % pages.size]

    private fun layoutDrag(dx: Float) {
        val cur = current ?: return
        cur.translationX = dx
        val next = neighbour(1); val prev = neighbour(-1)
        pages.forEach { if (it !== cur) it.visibility = View.INVISIBLE }
        if (dx < 0) { next.visibility = View.VISIBLE; next.translationX = width + dx }
        else if (dx > 0) { prev.visibility = View.VISIBLE; prev.translationX = -width + dx }
    }

    private fun animateTo(fromDx: Float, dir: Int) {
        val target = -dir * width.toFloat()
        anim = ValueAnimator.ofFloat(fromDx, target).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener { layoutDrag(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (dir != 0) switchTo(dir) else settleInstantly() }
            })
            start()
        }
    }

    private fun switchTo(dir: Int) {
        val old = current ?: return
        index = (index + dir + pages.size) % pages.size
        old.onHidden()
        settleInstantly()
        current?.let { it.onShown(); onPageChanged(it) }
    }

    /**
     * Jump straight to the page [id] (no animation). Doesn't report through
     * [onPageChanged], so an automatic switch isn't saved as the user's
     * "last used" view. Returns false if that page isn't in the container.
     */
    fun showPage(id: String): Boolean {
        val target = pages.indexOfFirst { it.pageId == id }
        if (target < 0 || target == index) return target == index
        anim?.cancel()
        current?.onHidden()
        index = target
        settleInstantly()
        current?.onShown()
        return true
    }

    val pageIds: List<String> get() = pages.map { it.pageId }

    private fun settleInstantly() {
        pages.forEachIndexed { i, p ->
            p.translationX = 0f
            p.visibility = if (i == index) View.VISIBLE else View.INVISIBLE
        }
    }

    // --- overlay --------------------------------------------------------------

    fun flash(text: String, ms: Long = 1200) {
        overlayText = text
        overlayUntil = System.currentTimeMillis() + ms
        invalidate()
        postDelayed({ invalidate() }, ms + 20)
    }

    /** Corner charging ring; null hides it. */
    var battery: com.nightstand.BatteryState? = null
        set(v) { field = v; invalidate() }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        battery?.takeIf {
            com.nightstand.Prefs.showBatteryRing(context) &&
                com.nightstand.BatteryRing.visible(it, com.nightstand.Prefs.hideRingAbove95(context))
        }?.let { b ->
            // Top-left corner, outside the analog clock's circle; drifts with burn-in shift.
            val (sx, sy) = com.nightstand.BurnIn.shiftFor(java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE), 8 * density)
            val rr = 11 * density
            com.nightstand.BatteryRing.draw(canvas, 26 * density + sx, 26 * density + sy, rr, b, palette)
        }
        if (System.currentTimeMillis() > overlayUntil) return
        overlayPaint.color = palette.primary
        overlayText?.let {
            overlayPaint.textSize = 20 * density
            canvas.drawText(it, width / 2f, height * 0.12f, overlayPaint)
        }
        if (pages.size > 1) {
            val r = 3.5f * density; val gap = 14 * density
            val start = width / 2f - gap * (pages.size - 1) / 2
            for (i in pages.indices) {
                overlayPaint.alpha = if (i == index) 255 else 90
                canvas.drawCircle(start + i * gap, height - 12 * density, r, overlayPaint)
            }
            overlayPaint.alpha = 255
        }
    }
}
