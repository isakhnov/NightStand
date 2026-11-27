package com.nightstand

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.text.TextPaint
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.nightstand.clock.NextAlarm
import com.nightstand.messages.MessageApp
import com.nightstand.messages.MessageRules
import com.nightstand.messages.MessageStrip
import com.nightstand.messages.MessageStyle
import com.nightstand.pages.DisplayData
import com.nightstand.pages.NowPlayingPage
import com.nightstand.pages.PageContainer
import com.nightstand.pages.PageRegistry
import com.nightstand.pages.PhotosPage
import com.nightstand.weather.Weather
import com.nightstand.weather.WeatherNow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w915dp-h412dp-land-xhdpi")
class MessagesTest {
    private fun f(pkg: String, category: String? = null, template: String? = null, conv: Boolean = false,
                  number: Int = 0, msgs: Int = 0, ongoing: Boolean = false, summary: Boolean = false, t: Long = 0) =
        MessageRules.Fields(pkg, ongoing, summary, category, template, conv, number, msgs, t)

    @Test fun detectsMessagesOnly() {
        assertTrue(MessageRules.isMessage(f("a", category = Notification.CATEGORY_MESSAGE)))
        assertTrue(MessageRules.isMessage(f("a", template = "android.app.Notification\$MessagingStyle")))
        assertTrue(MessageRules.isMessage(f("a", conv = true)))
        assertFalse(MessageRules.isMessage(f("a", category = Notification.CATEGORY_EMAIL)))
        assertFalse(MessageRules.isMessage(f("a", category = Notification.CATEGORY_MESSAGE, summary = true)))
        assertFalse(MessageRules.isMessage(f("a", category = Notification.CATEGORY_MESSAGE, ongoing = true)))
    }

    @Test fun countsAndGroups() {
        val out = MessageRules.summarize(listOf(
            f("wa", category = Notification.CATEGORY_MESSAGE, msgs = 4, t = 10),
            f("wa", category = Notification.CATEGORY_MESSAGE, number = 2, msgs = 9, t = 30),
            f("sig", conv = true, t = 20),
            f("me", conv = true, t = 40),
            f("mail", category = Notification.CATEGORY_EMAIL, t = 50),
        ), ownPkg = "me")
        assertEquals(listOf("wa" to 6, "sig" to 1), out.map { it.pkg to it.count })
    }

    @Test fun layoutCentresAndCap() {
        val area = RectF(0f, 0f, 100f, 400f)
        val v = MessageStrip.centres(3, true, area, 100f)
        assertEquals(listOf(50f to 100f, 50f to 200f, 50f to 300f), v)
        val h = MessageStrip.centres(2, false, RectF(0f, 0f, 400f, 60f), 100f)
        assertEquals(listOf(150f to 30f, 250f to 30f), h)
        assertEquals(MessageStrip.MAX_ITEMS, MessageStrip.centres(9, true, area, 10f).size)
        assertTrue(MessageStrip.centres(0, true, area, 10f).isEmpty())
    }

    @Test fun fading() {
        assertEquals(1f, MessageStrip.alpha(false, 100_000, 0), 0f)
        assertEquals(1f, MessageStrip.alpha(true, 4_000, 0), 0f)
        assertEquals(0.725f, MessageStrip.alpha(true, 5_500, 0), 0.001f)
        assertEquals(0.45f, MessageStrip.alpha(true, 60_000, 0), 0.001f)
    }

    @Test fun stripShrinksViewsOnlyWithMessages() {
        val c = ApplicationProvider.getApplicationContext<android.content.Context>()
        val box = PageContainer(c)
        box.messageStyle = MessageStyle(vertical = true, overlay = false)
        assertEquals(0, box.paddingRight)
        box.messages = listOf(MessageApp("wa", 2))
        assertTrue(box.paddingRight > 0)
        box.messageStyle = MessageStyle(vertical = false, overlay = false)
        assertEquals(0, box.paddingRight); assertTrue(box.paddingBottom > 0)
        box.messageStyle = MessageStyle(vertical = false, overlay = true)
        assertEquals(0, box.paddingBottom)
        box.messageStyle = MessageStyle(on = false)
        assertEquals(0, box.paddingRight + box.paddingBottom)
    }

    // --- previews: every view × layout, day and night -----------------------------

    private val sample = listOf(Triple("com.whatsapp", 0xFF25D366.toInt(), "W") to 3,
        Triple("org.thoughtcrime.securesms", 0xFF3A76F0.toInt(), "S") to 1,
        Triple("com.google.android.apps.messaging", 0xFF1A73E8.toInt(), "M") to 12)

    private fun glyph(color: Int, g: String, fg: Int, size: Int = 96): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        if (color != 0) cv.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        cv.drawText(g, size / 2f, size * 0.68f, TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Fonts.bold; textSize = size * 0.55f; this.color = fg; textAlign = Paint.Align.CENTER })
        return b
    }

    @Test fun renderPreviews() {
        val c = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dm = c.resources.displayMetrics; val W = dm.widthPixels; val H = dm.heightPixels
        val now = System.currentTimeMillis()
        Weather.current = WeatherNow("Cancún", 77, 61, true, 80, 77, true, now)
        Prefs.setClockStyle(c, Prefs.ClockStyle.ANALOG)
        val icons = sample.associate { (a, _) -> a.first to BitmapDrawable(c.resources, glyph(a.second, a.third, Color.BLACK)) }
        MessageStrip.iconOverride = { icons[it] }
        val apps = sample.map { (a, n) -> MessageApp(a.first, n, Icon.createWithBitmap(glyph(0, a.third, Color.WHITE))) }
        val photo = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888).also { b ->
            Canvas(b).apply {
                drawPaint(Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 0f, 1200f, 0xFF7FB2E5.toInt(), 0xFFF6D7A7.toInt(), android.graphics.Shader.TileMode.CLAMP) })
                drawCircle(1250f, 380f, 150f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF4C2.toInt() })
                drawRect(0f, 760f, 1600f, 1200f, Paint().apply { color = 0xFF3F7D3A.toInt() })
            }
        }
        val ids = listOf("clock_weather", "clock_agenda", PhotosPage.ID, NowPlayingPage.ID)
        val styles = listOf(
            "vertical · strip" to MessageStyle(vertical = true, overlay = false),
            "vertical · overlay" to MessageStyle(vertical = true, overlay = true),
            "horizontal · strip" to MessageStyle(vertical = false, overlay = false),
            "horizontal · overlay" to MessageStyle(vertical = false, overlay = true),
        )
        File("build/previews").mkdirs()
        for (night in listOf(false, true)) {
            val frames = mutableListOf<Pair<String, Bitmap>>()
            for (id in ids) for ((sname, style) in styles) {
                val data = DisplayData().apply {
                    events = listOf(Event("Dentist", now + 3_600_000, now + 5_400_000, false, 0xFF4285F4.toInt()))
                    nextAlarm = NextAlarm(now + 8 * 3_600_000L, "com.google.android.deskclock")
                }
                val page = PageRegistry.all.first { it.id == id }.create(c, data)
                if (page is PhotosPage) page.showForPreview(photo, 60_000)
                if (page is NowPlayingPage) page.player.apply {
                    fakeSession = true
                    track = com.nightstand.media.NowPlaying.Track("com.spotify.music", "Spotify", "Midnight City", "Sample Artist", null, 243_000)
                }
                val box = PageContainer(c)
                box.setPages(listOf(page), null)
                box.palette = if (night) Palette.night() else Palette.DAY
                box.battery = BatteryState(78, true, true, 85)
                box.messageStyle = style; box.messages = apps
                box.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
                box.layout(0, 0, W, H)
                val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
                val cv = Canvas(b); cv.drawColor(Color.BLACK); box.draw(cv)
                frames += "${PageRegistry.title(id)} · $sname" to b
            }
            val cols = styles.size; val sw = W / 3; val sh = H / 3; val g = 16; val lh = 30
            val sheet = Bitmap.createBitmap(g + cols * (sw + g), g + ids.size * (sh + lh + g), Bitmap.Config.ARGB_8888)
            val sc = Canvas(sheet); sc.drawColor(0xFF2B2B30.toInt())
            val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold; textSize = 18f; color = 0xFFE8E8EE.toInt() }
            frames.forEachIndexed { i, (name, b) ->
                val x = g + (i % cols) * (sw + g); val y = g + (i / cols) * (sh + lh + g)
                sc.drawText(name, x.toFloat(), y + 22f, title)
                sc.drawBitmap(b, null, Rect(x, y + lh, x + sw, y + lh + sh), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            File("build/previews/messages-${if (night) "night" else "day"}.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        MessageStrip.iconOverride = null
    }
}
