package com.nightstand.ui

import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nightstand.CalendarSource
import com.nightstand.Prefs
import com.nightstand.pages.BigClockPage
import com.nightstand.pages.ClockAgendaPage
import com.nightstand.pages.NowPlayingPage
import com.nightstand.pages.PageRegistry
import com.nightstand.pages.PhotosPage
import com.nightstand.pages.WeatherPage
import com.nightstand.photos.PhotoLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ViewsTab(s: SettingsScope) {
    val c = LocalContext.current
    var version by remember { mutableIntStateOf(0) } // bump to re-read prefs
    val order = remember(version) { PageRegistry.ordered(c) }
    val off = remember(version) { Prefs.disabledPages(c) }
    var expanded by remember { mutableStateOf<String?>(null) }

    fun move(i: Int, d: Int) {
        val ids = order.map { it.id }.toMutableList()
        val j = i + d
        if (j !in ids.indices) return
        ids[i] = ids[j].also { ids[j] = ids[i] }
        Prefs.setPageOrder(c, ids); version++
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        ScreenHeader("Views", "Order and options · swipe between them on the stand")
        Column(Modifier.padding(horizontal = 14.dp)) {
            SettingsCard(padding = 8) {
                order.forEachIndexed { i, e ->
                    val open = expanded == e.id
                    val on = e.id !in off
                    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(if (open) Surface2 else Color.Transparent)) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                IconButton({ move(i, -1) }, Modifier.size(26.dp), enabled = i > 0) {
                                    Icon(Icons.Filled.KeyboardArrowUp, "Move up", tint = if (i > 0) Muted else Surface3)
                                }
                                IconButton({ move(i, 1) }, Modifier.size(26.dp), enabled = i < order.size - 1) {
                                    Icon(Icons.Filled.KeyboardArrowDown, "Move down", tint = if (i < order.size - 1) Muted else Surface3)
                                }
                            }
                            Box(Modifier.width(92.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Surface3, RoundedCornerShape(10.dp))) {
                                PagePreviewImage(e.id, night = false, key = s.tick + version)
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(e.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(subtitle(c, e.id), fontSize = 12.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (hasOptions(e.id)) IconButton({ expanded = if (open) null else e.id }) {
                                Icon(Icons.Filled.Settings, "Options", tint = if (open) Accent else Muted)
                            }
                            Switch(on, { Prefs.setPageEnabled(c, e.id, it); version++ })
                        }
                        if (open) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                            ViewOptions(e.id) { version++ }
                        }
                    }
                }
            }
            Text("A view that's off is skipped when you swipe. At least one view always stays on.",
                fontSize = 12.sp, color = Muted, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun hasOptions(id: String) = id != BigClockPage.ID

private fun subtitle(c: android.content.Context, id: String): String = when (id) {
    ClockAgendaPage.ID -> {
        val cals = CalendarSource.googleCalendars(c)
        val on = cals.count { it.id.toString() !in Prefs.calendarsOff(c) }
        if (cals.isEmpty()) "Google Calendar" else "Google Calendar · $on of ${cals.size} calendars"
    }
    WeatherPage.ID -> (if (Prefs.fahrenheit(c)) "°F" else "°C") + " · current location"
    BigClockPage.ID -> "Clock only"
    PhotosPage.ID -> when (Prefs.photoSource(c)) {
        Prefs.PhotoSource.ALL -> "All photos"
        Prefs.PhotoSource.FAVORITES -> "Favorites"
        Prefs.PhotoSource.ALBUM -> "Album · " + (Prefs.photoAlbum(c)?.second ?: "—")
    }
    NowPlayingPage.ID -> "Spotify or any player"
    else -> ""
}

@Composable
private fun ViewOptions(id: String, changed: () -> Unit) {
    val c = LocalContext.current
    when (id) {
        WeatherPage.ID -> {
            OptionLabel("Temperature")
            var f by remember { mutableStateOf(Prefs.fahrenheit(c)) }
            Segmented(listOf("°F", "°C"), if (f) 0 else 1, { f = it == 0; Prefs.setFahrenheit(c, f); changed() })
        }
        ClockAgendaPage.ID -> {
            OptionLabel("Google calendars")
            val cals = remember { CalendarSource.googleCalendars(c) }
            if (cals.isEmpty()) Text("No Google calendars found (or Calendar permission missing).", fontSize = 13.sp, color = Muted)
            var offSet by remember { mutableStateOf(Prefs.calendarsOff(c)) }
            cals.forEach { cal ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(cal.id.toString() !in offSet, { on ->
                        Prefs.setCalendarOn(c, cal.id, on); offSet = Prefs.calendarsOff(c); changed()
                    })
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(cal.color or 0xFF000000.toInt())))
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(cal.name, fontSize = 15.sp)
                        Text(cal.account, fontSize = 12.sp, color = Muted)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            var show by remember { mutableStateOf(Prefs.showContent(c)) }
            SettingRow("Show notification titles while locked", "Otherwise only the app name and count") {
                Switch(show, { show = it; Prefs.setShowContent(c, it); changed() })
            }
        }
        PhotosPage.ID -> {
            OptionLabel("Source")
            var src by remember { mutableStateOf(Prefs.photoSource(c)) }
            var album by remember { mutableStateOf(Prefs.photoAlbum(c)?.first) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("All photos", src == Prefs.PhotoSource.ALL) { src = Prefs.PhotoSource.ALL; Prefs.setPhotoSource(c, src); changed() }
                Chip("Favorites", src == Prefs.PhotoSource.FAVORITES) { src = Prefs.PhotoSource.FAVORITES; Prefs.setPhotoSource(c, src); changed() }
                Chip("Album", src == Prefs.PhotoSource.ALBUM) { src = Prefs.PhotoSource.ALBUM; Prefs.setPhotoSource(c, src); changed() }
            }
            if (src == Prefs.PhotoSource.ALBUM) {
                val albums by produceState(emptyList<PhotoLibrary.Album>()) {
                    value = withContext(Dispatchers.IO) { PhotoLibrary.albums(c) }
                }
                if (albums.isEmpty()) Text("No albums found (or Photos permission missing).", fontSize = 13.sp, color = Muted,
                    modifier = Modifier.padding(top = 10.dp))
                albums.chunked(3).forEach { row ->
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { a ->
                            AlbumTile(a, a.id == album, Modifier.weight(1f)) {
                                album = a.id; Prefs.setPhotoAlbum(c, a.id, a.name); Prefs.setPhotoSource(c, Prefs.PhotoSource.ALBUM); changed()
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("At night photos turn red, stop moving and change every 10 minutes.", fontSize = 13.sp, color = Muted)
        }
        NowPlayingPage.ID -> {
            Text("Shows whatever is playing, Spotify first, with ⏮ ⏯ ⏭. Needs Notification access (Setup).",
                fontSize = 13.sp, color = Muted)
        }
    }
}

@Composable
private fun OptionLabel(t: String) = Text(t.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Muted,
    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))

@Composable
private fun AlbumTile(a: PhotoLibrary.Album, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalContext.current
    val thumb by produceState<ImageBitmap?>(null, a.cover) {
        value = withContext(Dispatchers.IO) {
            runCatching { c.contentResolver.loadThumbnail(a.cover, Size(240, 240), null).asImageBitmap() }.getOrNull()
        }
    }
    Column(modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.2f).clip(RoundedCornerShape(12.dp)).background(Surface3)
            .border(if (selected) 2.dp else 0.dp, if (selected) Accent else Color.Transparent, RoundedCornerShape(12.dp))) {
            thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1.2f)) }
        }
        Text("${a.name} · ${a.count}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) Accent else Muted, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(top = 4.dp))
    }
}
