package com.nightstand.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.nightstand.CalendarSource
import com.nightstand.NotificationFeed
import com.nightstand.Palette
import com.nightstand.Prefs
import com.nightstand.WatcherService
import com.nightstand.pages.DisplayData
import com.nightstand.pages.PageRegistry
import com.nightstand.pages.PhotosPage
import com.nightstand.photos.PhotoLibrary
import com.nightstand.weather.Weather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Draws a real Nightstand page into a small bitmap, so settings show exactly
 * what the display will — same code, current settings, real data. Rendered at
 * 1x density (915×412 px) to keep thumbnails cheap.
 */
object PagePreview {
    const val W = 915
    const val H = 412

    /** Shown in the preview when nothing is unread, so the Messages options can be judged. */
    private val SAMPLE_MESSAGES = listOf(
        com.nightstand.messages.MessageApp("org.thoughtcrime.securesms", 3),
        com.nightstand.messages.MessageApp("com.whatsapp", 12),
        com.nightstand.messages.MessageApp("com.google.android.apps.messaging", 1),
    )

    /**
     * Never throws: a preview that fails to draw must not take the settings
     * screen down with it (0.5 crashed on open this way). On failure the
     * error goes to the log and a plain placeholder is shown.
     */
    suspend fun render(c: Context, id: String, night: Boolean): ImageBitmap = try {
        renderUnsafe(c, id, night)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e // tab switched while drawing: not an error
    } catch (t: Throwable) {
        WatcherService.log("preview $id failed: $t")
        placeholder()
    }

    private fun placeholder(): ImageBitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        cv.drawColor(android.graphics.Color.BLACK)
        cv.drawText("Preview unavailable", W / 2f, H / 2f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8E8E93.toInt(); textSize = 28f; textAlign = android.graphics.Paint.Align.CENTER
        })
        return b.asImageBitmap()
    }

    private suspend fun renderUnsafe(c: Context, id: String, night: Boolean): ImageBitmap {
        // Photos: decode one real photo off the main thread first.
        val photo = if (id == PhotosPage.ID) withContext(Dispatchers.IO) {
            PhotoLibrary.query(c).firstOrNull()?.let { PhotoLibrary.decode(c, it, 1000, software = true) }
        } else null
        val data = withContext(Dispatchers.IO) {
            Weather.loadCache(c)
            DisplayData().apply {
                events = CalendarSource.upcoming(c)
                notifs = NotificationFeed.snapshot
                showNotifContent = Prefs.showContent(c)
            }
        }
        val ctx = c.createConfigurationContext(Configuration(c.resources.configuration).apply { densityDpi = 160 })
        val entry = PageRegistry.all.firstOrNull { it.id == id } ?: PageRegistry.all.first()
        val page = entry.create(ctx, data)
        page.palette = if (night) Palette.night(Prefs.nightColor(c)) else Palette.DAY
        if (page is PhotosPage && photo != null) page.showForPreview(photo, 60_000)
        // Host it like the display does, so message badges (strip/overlay) show too.
        val host = com.nightstand.pages.PageContainer(ctx)
        host.palette = page.palette
        host.setPages(listOf(page), page.pageId)
        host.messageStyle = Prefs.messageStyle(c)
        host.messages = NotificationFeed.messages.ifEmpty { SAMPLE_MESSAGES }
        host.measure(
            View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, W, H)
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        host.draw(Canvas(b))
        return b.asImageBitmap()
    }
}

/** A page preview; re-renders whenever [key] changes. */
@Composable
fun PagePreviewImage(id: String, night: Boolean, key: Any?, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    val img by produceState<ImageBitmap?>(null, id, night, key) { value = PagePreview.render(c, id, night) }
    img?.let {
        Image(it, null, contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxWidth().aspectRatio(PagePreview.W / PagePreview.H.toFloat()))
    }
}
