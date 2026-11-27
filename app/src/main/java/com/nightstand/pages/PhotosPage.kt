package com.nightstand.pages

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import com.nightstand.Fonts
import com.nightstand.photos.KenBurns
import com.nightstand.photos.PhotoLibrary
import com.nightstand.photos.smart.SmartPhotos
import java.util.Calendar

/**
 * Clock left, photo right. Each photo starts as the whole original fitted in
 * the panel, zooms to fill it, then pans across the cropped part
 * ([KenBurns]); then crossfades to the next one.
 *
 * Which photo comes next is [SmartPhotos]' job: random when Smart selection is
 * off (or its model is missing), otherwise scored, learned and one per burst.
 *
 * By day, swiping DOWN on the photo hides it ("junk, never again"; similar ones
 * too) with an Undo toast; brightness is then dragged on the clock half. At
 * night there is no swipe-to-hide (a sleepy hand shouldn't teach it anything).
 *
 * Night: Nightstand switches to Clock + weather when night begins; if the
 * user swipes back here, photos show normally in full colour, dimmed with the
 * clock by the window's night brightness (red monochrome didn't work).
 */
class PhotosPage(context: Context, data: DisplayData) : ClockSplitPage(context, data) {
    companion object {
        const val ID = "clock_photos"
        private const val ZOOM_MS = 12_000L
        private const val DAY_MS = 45_000L
        private const val FADE_MS = 1_200L
        private const val QUICK_FADE_MS = 300L
        private const val FALL_MS = 180L
        const val TOAST_MS = 6_000L
        /** A photo can't be swiped away in its first moments (you must have seen it). */
        const val GUARD_MS = 600L
        const val COMMIT_FRACTION = 0.35f
        const val FLING_DP_S = 1_200f
    }
    override val pageId = ID

    private val engine get() = SmartPhotos.get(context)
    private var cur: Bitmap? = null
    private var curId = -1L
    private var prev: Bitmap? = null
    private var shownAt = 0L
    private var fadeMs = FADE_MS
    private var panDir = 1
    private var loading = false
    private var active = false
    private var message: String? = null

    // swipe-out
    private var dragDy = 0f
    private var settle: ValueAnimator? = null
    private var falling: Bitmap? = null
    private var fallFrom = 0f
    private var fallStart = 0L
    private var fallPan = ZOOM_MS

    private class Toast(val labelId: Long, val mediaId: Long, val bitmap: Bitmap, val until: Long, val smart: Boolean)
    private var toast: Toast? = null
    private val toastRect = RectF()
    private val undoRect = RectF()

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val msg = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold }
    private val toastBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val toastText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.semibold }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val clip = Path()

    /** Preview/test: show [b] as if loaded [ageMs] ago ([mediaId] makes it swipeable). */
    internal fun showForPreview(b: Bitmap, ageMs: Long, mediaId: Long = -1) {
        cur = b; curId = mediaId; prev = null; shownAt = SystemClock.uptimeMillis() - ageMs
    }

    internal val currentId get() = curId
    internal val toastLabel get() = toast?.labelId

    override fun onShown() {
        active = true
        engine.pageShown(palette.night)
        if (cur == null) next() else invalidate()
    }

    override fun onHidden() {
        active = false
        engine.pageHidden()
    }

    override fun onTick() {
        engine.night = palette.night
        val period = DAY_MS
        if (cur != null && SystemClock.uptimeMillis() - shownAt > period) next()
        if (toast != null && SystemClock.uptimeMillis() > toast!!.until) toast = null
        invalidate()
    }

    private fun next(quick: Boolean = false) {
        if (loading) return
        loading = true
        val panelMax = (maxOf(width, height) * 1.2f).toInt().coerceIn(1200, 2600)
        engine.night = palette.night
        engine.next(panelMax) { pick ->
            loading = false
            message = when {
                !PhotoLibrary.isGranted(context) -> "Allow photos in Nightstand setup"
                pick == null && cur == null -> "No photos found for this source"
                else -> null
            }
            if (pick != null) {
                prev = cur; cur = pick.bitmap; curId = pick.id
                fadeMs = if (quick) QUICK_FADE_MS else FADE_MS
                shownAt = SystemClock.uptimeMillis(); panDir = -panDir
            }
            invalidate()
        }
    }

    // --- swipe down to hide -------------------------------------------------------

    private fun inPhotoPane(x: Float, y: Float) = rightRect.contains(x - shiftX, y - shiftY)

    override fun verticalDragTarget(x: Float, y: Float): DragTarget {
        if (!inPhotoPane(x, y)) return DragTarget.BRIGHTNESS // the clock half
        return when {
            palette.night -> DragTarget.NONE // decision: no swipe-to-hide at night
            cur == null || curId < 0 -> DragTarget.NONE
            y < dp(32f) -> DragTarget.NONE // system-bar reveal zone
            SystemClock.uptimeMillis() - shownAt < GUARD_MS -> DragTarget.NONE
            else -> DragTarget.PAGE
        }
    }

    override fun verticalDragHint() = "Brightness: drag on the clock"

    override fun onVerticalDrag(dy: Float) {
        settle?.cancel()
        // Down follows the finger; up only rubber-bands a little.
        dragDy = if (dy >= 0) dy else maxOf(dy * 0.25f, -dp(24f))
        invalidate()
    }

    override fun onVerticalDragEnd(dy: Float, vyDp: Float, cancelled: Boolean) {
        val f = dy / rightRect.height().coerceAtLeast(1f)
        if (!cancelled && (f >= COMMIT_FRACTION || (vyDp >= FLING_DP_S && f >= 0.1f))) hideCurrent()
        else springBack()
    }

    private fun springBack() {
        settle?.cancel()
        settle = ValueAnimator.ofFloat(dragDy, 0f).apply {
            duration = 200
            addUpdateListener { dragDy = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun hideCurrent() {
        val id = curId; val bmp = cur ?: return
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        falling = bmp; fallFrom = dragDy; fallStart = SystemClock.uptimeMillis()
        fallPan = SystemClock.uptimeMillis() - shownAt
        dragDy = 0f
        cur = null; prev = null; curId = -1
        val smart = engine.smartActive()
        engine.hide(id) { label ->
            toast = Toast(label, id, bmp, SystemClock.uptimeMillis() + TOAST_MS, smart)
            invalidate()
            postInvalidateDelayed(TOAST_MS + 50)
        }
        next(quick = true)
        invalidate()
    }

    override fun onTapAt(x: Float, y: Float): Boolean {
        val t = toast ?: return false
        if (SystemClock.uptimeMillis() > t.until) { toast = null; return false }
        if (!undoRect.contains(x - shiftX, y - shiftY)) return toastRect.contains(x - shiftX, y - shiftY)
        engine.undo(t.labelId)
        toast = null
        // Put the same photo back on screen.
        prev = cur; cur = t.bitmap; curId = t.mediaId
        fadeMs = QUICK_FADE_MS; shownAt = SystemClock.uptimeMillis()
        invalidate()
        return true
    }

    // --- drawing --------------------------------------------------------------------

    override fun drawRight(c: Canvas, r: RectF, now: Calendar) {
        val radius = dp(22f)
        val bmp = cur
        val upNow = SystemClock.uptimeMillis()
        clip.reset(); clip.addRoundRect(r, radius, radius, Path.Direction.CW)
        // Photos stay in full colour at night (red monochrome didn't work); the
        // window's night brightness dims them together with the clock.
        bmpPaint.colorFilter = null

        if (bmp == null && falling == null) {
            msg.color = palette.secondary; msg.textSize = dp(18f)
            c.drawText(message ?: "Loading photos…", r.left + dp(16f), r.centerY(), msg)
            drawToast(c, r, upNow)
            return
        }
        c.save(); c.clipPath(clip)
        if (bmp != null) {
            val t = upNow - shownAt
            val fade = (t.toFloat() / fadeMs).coerceIn(0f, 1f)
            prev?.let { if (fade < 1f) drawPhoto(c, it, r, Long.MAX_VALUE / 4, -panDir, 255) }
            // Swipe feedback: the photo follows the finger, shrinks, tilts and dims.
            val f = (dragDy / r.height()).coerceIn(-0.1f, 1f)
            c.save()
            if (dragDy != 0f) {
                c.translate(0f, dragDy)
                val s = 1f - 0.2f * f.coerceAtLeast(0f)
                c.rotate(5f * f, r.centerX(), r.centerY())
                c.scale(s, s, r.centerX(), r.centerY())
            }
            val alpha = (fade * 255 * (1f - 0.3f * f.coerceAtLeast(0f))).toInt()
            drawPhoto(c, bmp, r, t, panDir, alpha)
            c.restore()
            if (fade >= 1f) prev = null
        }
        falling?.let { fb ->
            val p = ((upNow - fallStart).toFloat() / FALL_MS).coerceIn(0f, 1f)
            if (p >= 1f) falling = null
            else {
                c.save()
                c.translate(0f, fallFrom + (r.height() - fallFrom) * p)
                c.rotate(5f * (0.35f + 0.65f * p), r.centerX(), r.centerY())
                c.scale(0.8f, 0.8f, r.centerX(), r.centerY())
                drawPhoto(c, fb, r, fallPan, -panDir, ((1f - p) * 200).toInt())
                c.restore()
            }
        }
        c.restore()
        if (bmp == null) {
            msg.color = palette.secondary; msg.textSize = dp(18f)
            c.drawText("Next photo…", r.left + dp(16f), r.centerY(), msg)
        }
        drawToast(c, r, upNow)

        val animating = active && (upNow - shownAt < DAY_MS || falling != null)
        if (animating) postInvalidateDelayed(if (falling != null || upNow - shownAt < fadeMs) 16 else 66) // 15 fps pan (power)
    }

    private fun drawToast(c: Canvas, r: RectF, upNow: Long) {
        val t = toast ?: return
        if (upNow > t.until) { toast = null; return }
        val night = palette.night
        val text = if (t.smart) "Hidden · similar photos won't show" else "Hidden · won't show again"
        val h = dp(48f)
        toastText.textSize = dp(15f)
        val undoW = toastText.measureText("Undo") + dp(24f)
        val iconW = if (night) 0f else dp(30f)
        val w = minOf(r.width() - dp(24f), dp(20f) + iconW + toastText.measureText(text) + dp(14f) + undoW + dp(8f))
        toastRect.set(r.centerX() - w / 2, r.top + dp(14f), r.centerX() + w / 2, r.top + dp(14f) + h)
        undoRect.set(toastRect.right - undoW - dp(8f), toastRect.top + dp(7f), toastRect.right - dp(8f), toastRect.bottom - dp(7f))
        if (night) {
            toastBg.style = Paint.Style.FILL; toastBg.color = 0xFF000000.toInt()
            c.drawRoundRect(toastRect, h / 2, h / 2, toastBg)
            toastBg.style = Paint.Style.STROKE; toastBg.strokeWidth = dp(1.5f); toastBg.color = palette.redAlpha(160)
            c.drawRoundRect(toastRect, h / 2, h / 2, toastBg)
            toastBg.style = Paint.Style.FILL
        } else {
            toastBg.style = Paint.Style.FILL; toastBg.color = 0xEB1C1C1F.toInt()
            c.drawRoundRect(toastRect, h / 2, h / 2, toastBg)
            // "eye-off" icon
            icon.color = palette.accent; icon.strokeWidth = dp(2f)
            val cx = toastRect.left + dp(26f); val cy = toastRect.centerY()
            c.drawOval(cx - dp(9f), cy - dp(5.5f), cx + dp(9f), cy + dp(5.5f), icon)
            c.drawCircle(cx, cy, dp(2.5f), icon)
            c.drawLine(cx - dp(9f), cy - dp(8f), cx + dp(9f), cy + dp(8f), icon)
        }
        toastText.color = if (night) palette.primary else 0xFFF2F2F5.toInt()
        val baseline = toastRect.centerY() - (toastText.ascent() + toastText.descent()) / 2
        val textLeft = toastRect.left + dp(20f) + iconW
        val maxText = undoRect.left - dp(10f) - textLeft
        val shown = if (toastText.measureText(text) <= maxText) text
            else android.text.TextUtils.ellipsize(text, toastText, maxText, android.text.TextUtils.TruncateAt.END).toString()
        c.drawText(shown, textLeft, baseline, toastText)
        toastBg.color = if (night) palette.redAlpha(50) else 0xFF3A2A10.toInt()
        c.drawRoundRect(undoRect, undoRect.height() / 2, undoRect.height() / 2, toastBg)
        toastText.color = palette.accent; toastText.typeface = Fonts.bold
        c.drawText("Undo", undoRect.left + dp(12f), baseline, toastText)
        toastText.typeface = Fonts.semibold
    }

    private fun drawPhoto(c: Canvas, b: Bitmap, r: RectF, t: Long, dir: Int, alpha: Int) {
        val f = KenBurns.frame(b.width, b.height, r.width(), r.height(), t, ZOOM_MS, DAY_MS, dir)
        bmpPaint.alpha = alpha.coerceIn(0, 255)
        c.save()
        c.translate(r.left + f.left, r.top + f.top)
        c.scale(f.scale, f.scale)
        c.drawBitmap(b, 0f, 0f, bmpPaint)
        c.restore()
    }
}
