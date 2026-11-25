package com.nightstand.pages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.text.TextPaint
import com.nightstand.Fonts
import com.nightstand.photos.KenBurns
import com.nightstand.photos.PhotoLibrary
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Clock left, photo right. Each photo starts as the whole original fitted in
 * the panel, zooms to fill it, then pans across the cropped part
 * ([KenBurns]); then crossfades to the next one.
 *
 * Night: no motion (less light, less change), red monochrome at reduced
 * alpha, next photo only every 10 minutes.
 */
class PhotosPage(context: Context, data: DisplayData) : ClockSplitPage(context, data) {
    companion object {
        const val ID = "clock_photos"
        private const val ZOOM_MS = 12_000L
        private const val DAY_MS = 45_000L
        private const val NIGHT_MS = 10 * 60_000L
        private const val FADE_MS = 1_200L
    }
    override val pageId = ID

    private val io = Executors.newSingleThreadExecutor()
    private var queue = ArrayDeque<Uri>()
    private var cur: Bitmap? = null
    private var prev: Bitmap? = null
    private var shownAt = 0L
    private var panDir = 1
    private var loading = false
    private var active = false
    private var message: String? = null

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val msg = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold }
    private val clip = Path()


    /** Preview/test: show [b] as if loaded [ageMs] ago. */
    internal fun showForPreview(b: Bitmap, ageMs: Long) {
        cur = b; prev = null; shownAt = SystemClock.uptimeMillis() - ageMs
    }

    override fun onShown() {
        active = true
        if (cur == null) next() else invalidate()
    }

    override fun onHidden() {
        active = false
    }

    override fun onTick() {
        val period = if (palette.night) NIGHT_MS else DAY_MS
        if (cur != null && SystemClock.uptimeMillis() - shownAt > period) next()
        invalidate()
    }

    private fun next() {
        if (loading) return
        loading = true
        val panelMax = (maxOf(width, height) * 1.2f).toInt().coerceIn(1200, 2600)
        val app = context.applicationContext
        io.execute {
            if (queue.isEmpty()) queue = ArrayDeque(PhotoLibrary.query(app))
            var bmp: Bitmap? = null
            while (bmp == null && queue.isNotEmpty()) bmp = PhotoLibrary.decode(app, queue.removeFirst(), panelMax)
            post {
                loading = false
                message = when {
                    !PhotoLibrary.isGranted(context) -> "Allow photos in Nightstand setup"
                    bmp == null && cur == null -> "No photos found for this source"
                    else -> null
                }
                if (bmp != null) {
                    prev = cur; cur = bmp
                    shownAt = SystemClock.uptimeMillis(); panDir = -panDir
                }
                invalidate()
            }
        }
    }

    override fun drawRight(c: Canvas, r: RectF, now: Calendar) {
        val radius = dp(22f)
        val bmp = cur
        if (bmp == null) {
            msg.color = palette.secondary; msg.textSize = dp(18f)
            c.drawText(message ?: "Loading photos…", r.left + dp(16f), r.centerY(), msg)
            return
        }
        val t = SystemClock.uptimeMillis() - shownAt
        val night = palette.night
        clip.reset(); clip.addRoundRect(r, radius, radius, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        bmpPaint.colorFilter = if (night) palette.nightImageFilter(0.8f) else null

        val fade = (t.toFloat() / FADE_MS).coerceIn(0f, 1f)
        prev?.let { if (fade < 1f) drawPhoto(c, it, r, Long.MAX_VALUE / 4, -panDir, 255) }
        // Night: straight to the filled, still frame.
        drawPhoto(c, bmp, r, if (night) ZOOM_MS else t, panDir, (fade * 255).toInt())
        c.restore()
        if (fade >= 1f) prev = null

        val animating = active && !night && t < DAY_MS
        if (animating) postInvalidateDelayed(33)
    }

    private fun drawPhoto(c: Canvas, b: Bitmap, r: RectF, t: Long, dir: Int, alpha: Int) {
        val f = KenBurns.frame(b.width, b.height, r.width(), r.height(), t, ZOOM_MS, DAY_MS, dir)
        bmpPaint.alpha = alpha
        c.save()
        c.translate(r.left + f.left, r.top + f.top)
        c.scale(f.scale, f.scale)
        c.drawBitmap(b, 0f, 0f, bmpPaint)
        c.restore()
    }
}
