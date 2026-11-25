package com.nightstand.pages

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import com.nightstand.Fonts
import com.nightstand.media.NowPlaying
import java.util.Calendar

/**
 * Clock left; right: album art + title/artist, progress, and previous /
 * play-pause / next. Buttons are handled via [onTapAt] so they respond
 * immediately and don't trigger tap-to-brighten.
 *
 * Night: art becomes dim red monochrome; buttons are outlines (less lit area).
 */
class NowPlayingPage(context: Context, data: DisplayData) : ClockSplitPage(context, data) {
    companion object { const val ID = "clock_now_playing" }
    override val pageId = ID
    override val needsSeconds get() = true // progress bar

    internal val player = NowPlaying(context) { invalidate() }

    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.heavy }
    private val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.semibold }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()

    // Hit areas in view coordinates (burn-in shift added).
    private val prevHit = RectF(); private val playHit = RectF(); private val nextHit = RectF()
    private val openHit = RectF()

    private val nightArt = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        0.299f, 0.587f, 0.114f, 0f, 0f,
        0f, 0f, 0f, 0f, 0f,
        0f, 0f, 0f, 0f, 0f,
        0f, 0f, 0f, 0.75f, 0f,
    )))

    override fun onShown() = player.start()
    override fun onHidden() = player.stop()

    override fun onTapAt(x: Float, y: Float): Boolean {
        when {
            player.hasSession && prevHit.contains(x, y) -> player.previous()
            player.hasSession && playHit.contains(x, y) -> player.playPause()
            player.hasSession && nextHit.contains(x, y) -> player.next()
            !player.hasSession && openHit.contains(x, y) -> openSpotify()
            else -> return false
        }
        invalidate()
        return true
    }

    /** No live session: unlock, then open Spotify (playback can't start headless). */
    private fun openSpotify() {
        val act = context as? Activity ?: return
        val launch = context.packageManager.getLaunchIntentForPackage(NowPlaying.SPOTIFY) ?: return
        val km = context.getSystemService(KeyguardManager::class.java)
        val go = { act.startActivity(launch) }
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(act, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = go()
            })
        } else go()
    }

    override fun drawRight(c: Canvas, r: RectF, now: Calendar) {
        val p = palette
        val t = player.track
        prevHit.setEmpty(); playHit.setEmpty(); nextHit.setEmpty(); openHit.setEmpty()

        if (t == null) {
            sub.color = p.secondary; sub.textSize = dp(20f)
            c.drawText("Nothing playing", r.left + dp(16f), r.centerY() - dp(10f), sub)
            title.color = p.primary; title.textSize = dp(22f)
            val label = "Open Spotify ›"
            val y = r.centerY() + dp(30f)
            c.drawText(label, r.left + dp(16f), y, title)
            openHit.set(r.left, y - dp(40f), r.left + dp(32f) + title.measureText(label), y + dp(16f))
            openHit.offset(shiftX, shiftY)
            return
        }

        // Art: square, left of the text, top of the panel.
        val art = minOf(r.height() * 0.52f, r.width() * 0.42f)
        val ar = RectF(r.left, r.top, r.left + art, r.top + art)
        clip.reset(); clip.addRoundRect(ar, dp(16f), dp(16f), Path.Direction.CW)
        c.save(); c.clipPath(clip)
        if (t.art != null) {
            artPaint.colorFilter = if (p.night) nightArt else null
            c.drawBitmap(t.art, null, ar, artPaint)
        } else {
            fill.color = if (p.night) 0x33FF0000 else 0xFF2C2C2E.toInt(); c.drawRect(ar, fill)
        }
        c.restore()

        // Text block beside the art.
        val tx = ar.right + dp(16f)
        val tw = r.right - tx
        small.textSize = dp(13f); small.color = p.secondary
        c.drawText(t.appLabel.uppercase(), tx, ar.top + dp(22f), small)
        title.textSize = minOf(dp(30f), art * 0.2f); title.color = p.primary
        val tt = TextUtils.ellipsize(t.title, title, tw, TextUtils.TruncateAt.END)
        c.drawText(tt, 0, tt.length, tx, ar.top + dp(22f) + title.textSize * 1.4f, title)
        sub.textSize = title.textSize * 0.7f; sub.color = p.secondary
        val at = TextUtils.ellipsize(t.artist, sub, tw, TextUtils.TruncateAt.END)
        c.drawText(at, 0, at.length, tx, ar.top + dp(22f) + title.textSize * 1.4f + sub.textSize * 1.5f, sub)

        // Progress
        val py = ar.bottom + dp(26f)
        val pos = player.positionMs()
        val frac = if (t.durationMs > 0) (pos.toFloat() / t.durationMs).coerceIn(0f, 1f) else 0f
        fill.color = if (p.night) 0x40FF0000 else 0xFF3A3A3C.toInt()
        c.drawRoundRect(r.left, py, r.right, py + dp(6f), dp(3f), dp(3f), fill)
        fill.color = p.primary
        c.drawRoundRect(r.left, py, r.left + r.width() * frac, py + dp(6f), dp(3f), dp(3f), fill)
        small.color = p.secondary
        c.drawText(NowPlaying.format(pos), r.left, py + dp(24f), small)
        if (t.durationMs > 0) {
            val rem = "-" + NowPlaying.format(t.durationMs - pos)
            c.drawText(rem, r.right - small.measureText(rem), py + dp(24f), small)
        }

        // Controls
        val cy = (py + dp(30f) + r.bottom) / 2
        val big = minOf(dp(34f), (r.bottom - py - dp(30f)) / 2 - dp(2f))
        val cx = r.centerX(); val gap = big * 3.2f
        drawPlayPause(c, cx, cy, big, player.isPlaying)
        drawSkip(c, cx - gap, cy, big * 0.62f, forward = false)
        drawSkip(c, cx + gap, cy, big * 0.62f, forward = true)
        val hit = big * 1.6f
        prevHit.set(cx - gap - hit, cy - hit, cx - gap + hit, cy + hit)
        playHit.set(cx - hit, cy - hit, cx + hit, cy + hit)
        nextHit.set(cx + gap - hit, cy - hit, cx + gap + hit, cy + hit)
        listOf(prevHit, playHit, nextHit).forEach { it.offset(shiftX, shiftY) }
    }

    private fun drawPlayPause(c: Canvas, cx: Float, cy: Float, r: Float, playing: Boolean) {
        val p = palette
        fill.color = p.primary
        fill.style = if (p.night) Paint.Style.STROKE else Paint.Style.FILL
        fill.strokeWidth = dp(2.5f)
        c.drawCircle(cx, cy, r, fill)
        fill.style = Paint.Style.FILL
        fill.color = if (p.night) p.primary else p.background
        if (playing) {
            val bw = r * 0.2f; val bh = r * 0.9f
            c.drawRoundRect(cx - bw * 1.6f, cy - bh / 2, cx - bw * 0.6f, cy + bh / 2, bw / 3, bw / 3, fill)
            c.drawRoundRect(cx + bw * 0.6f, cy - bh / 2, cx + bw * 1.6f, cy + bh / 2, bw / 3, bw / 3, fill)
        } else {
            val s = r * 0.5f
            c.drawPath(Path().apply {
                moveTo(cx - s * 0.7f, cy - s); lineTo(cx + s, cy); lineTo(cx - s * 0.7f, cy + s); close()
            }, fill)
        }
    }

    private fun drawSkip(c: Canvas, cx: Float, cy: Float, s: Float, forward: Boolean) {
        fill.color = palette.primary; fill.style = Paint.Style.FILL
        val d = if (forward) 1 else -1
        for (i in 0..1) {
            val x0 = cx + d * (i * s - s)
            c.drawPath(Path().apply {
                moveTo(x0, cy - s * 0.75f); lineTo(x0 + d * s, cy); lineTo(x0, cy + s * 0.75f); close()
            }, fill)
        }
        c.drawRect(
            if (forward) cx + s - s * 0.1f else cx - s - s * 0.12f, cy - s * 0.75f,
            if (forward) cx + s + s * 0.12f else cx - s + s * 0.1f, cy + s * 0.75f, fill
        )
    }
}
