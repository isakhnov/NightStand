package com.nightstand.ui

import android.net.Uri
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nightstand.Prefs
import com.nightstand.photos.PhotoLibrary
import com.nightstand.photos.smart.ModelStore
import com.nightstand.photos.smart.PhotoIndexDb
import com.nightstand.photos.smart.SmartPhotos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Danger = Color(0xFFFF6961)

/** Views → Photos ⚙ → Smart selection (mockup docs/photos-intelligence/step-a/b-photos-options.png). */
@Composable
fun SmartPhotoOptions(openHidden: () -> Unit, changed: () -> Unit) {
    val c = LocalContext.current
    val engine = remember { SmartPhotos.get(c) }
    var smart by remember { mutableStateOf(Prefs.photoSmart(c)) }
    var version by remember { mutableIntStateOf(0) }
    remember { ModelStore.refresh(c); 0 }
    val model by ModelStore.state.collectAsState()
    val counts by produceState<PhotoIndexDb.Counts?>(null, version, model) {
        value = withContext(Dispatchers.IO) { runCatching { engine.db.counts() }.getOrNull() }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) ModelStore.import(c, uri) { ok -> if (ok) engine.modelChanged() }
    }
    fun download() = ModelStore.download(c) { ok -> if (ok) engine.modelChanged() }

    SectionTitle("Smart selection")
    Segmented(listOf("Off", "Smart"), if (smart) 1 else 0, {
        smart = it == 1; Prefs.setPhotoSmart(c, smart); changed()
        if (smart && !ModelStore.isInstalled(c)) download()
    })
    Spacer(Modifier.height(10.dp))

    if (smart) {
        when (val m = model) {
            is ModelStore.State.Downloading -> StatusCard {
                Text("Downloading the photo model…", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                val total = if (m.total > 0) m.total else ModelStore.SIZE_BYTES
                LinearProgressIndicator(progress = { (m.done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("%.1f of %.1f MB · photos stay random until it's ready".format(m.done / 1e6, total / 1e6),
                    fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
            }
            ModelStore.State.Verifying -> StatusCard { Text("Checking the model file…", fontSize = 15.sp) }
            ModelStore.State.Ready -> StatusCard {
                val n = counts
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Learning", ok = true)
                    Text("Learning from ${n?.swipes ?: 0} swipe" + if (n?.swipes == 1) "" else "s", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    if (n == null) "…" else "%,d photos checked · %,d skipped as utility shots · %,d duplicates folded · runs only on the charger"
                        .format(n.scored, n.junk, n.bursts),
                    fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 6.dp),
                )
            }
            else -> StatusCard {
                val failed = m as? ModelStore.State.Failed
                Text(if (failed != null) "Couldn't get the photo model" else "The photo model isn't on this phone yet",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(failed?.message ?: "Smart selection needs a %.0f MB model file, downloaded once. Until then photos stay random."
                    .format(ModelStore.SIZE_BYTES / 1e6), fontSize = 13.sp, color = if (failed != null) Warn else Muted,
                    modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ download() }) { Text(if (failed != null) "Try again" else "Download") }
                    OutlinedButton({ importer.launch(arrayOf("*/*")) }) { Text("Import model file…") }
                }
            }
        }
    }

    var radius by remember { mutableStateOf(Prefs.photoLookAlike(c)) }
    SectionTitle("Look-alike strictness")
    Segmented(Prefs.LookAlike.values().map { it.label }, radius.ordinal, {
        radius = Prefs.LookAlike.values()[it]; Prefs.setPhotoLookAlike(c, radius); engine.radiusChanged(); version++; changed()
    })
    Text(when (radius) {
        Prefs.LookAlike.STRICT -> "Hides only near-identical shots of a photo you swipe away"
        Prefs.LookAlike.NORMAL -> "Hides the same shot and close variations"
        Prefs.LookAlike.WIDE -> "Also hides the same scene from another angle"
    }, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(start = 4.dp, top = 6.dp))

    var burst by remember { mutableStateOf(Prefs.photoOnePerBurst(c)) }
    SettingRow("Show one per burst", "Only the best of photos taken seconds apart") {
        Switch(burst, { burst = it; Prefs.setPhotoOnePerBurst(c, it); changed() })
    }
    HorizontalDivider(color = Surface3)
    val n = counts
    SettingRow("Hidden photos", n?.let { "${it.hiddenByYou} you swiped away · ${it.lookAlikes} look-alikes · ${it.bursts} in bursts" } ?: "…",
        Modifier.clickable(onClick = openHidden)) {
        Icon(Icons.Filled.KeyboardArrowRight, null, tint = Muted)
    }
    HorizontalDivider(color = Surface3)
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { confirm = true }.padding(vertical = 10.dp)) {
        Text("Reset learning", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Danger)
        Text("Forget swipes and restore every hidden photo", fontSize = 13.sp, color = Muted)
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Reset learning?") },
        text = { Text("Every swipe is forgotten and all hidden photos come back. Bursts stay folded.") },
        confirmButton = { TextButton({ confirm = false; engine.resetLearning { version++; changed() } }) { Text("Reset", color = Danger) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
    )
    Spacer(Modifier.height(8.dp))
    Text("On the stand, swipe down on a photo to hide it and similar ones (by day; not at night). " +
        "Everything is worked out on this phone; nothing is uploaded. At night photos turn red, stop moving and change every 10 minutes.",
        fontSize = 13.sp, color = Muted)
}

@Composable
private fun SectionTitle(t: String) = Text(t.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Muted,
    modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))

@Composable
private fun StatusCard(content: @Composable () -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).padding(14.dp)) { content() }

/** Hidden photos (mockup c-hidden-photos.png): tabs You hid / Look-alikes / Bursts, multi-select, Restore. */
@Composable
fun HiddenPhotosScreen(onBack: () -> Unit) {
    val c = LocalContext.current
    val engine = remember { SmartPhotos.get(c) }
    BackHandler(onBack = onBack)
    var tab by remember { mutableIntStateOf(0) }
    var version by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    val states = listOf(PhotoIndexDb.HIDDEN, PhotoIndexDb.LOOKALIKE, PhotoIndexDb.BURST)
    val all by produceState<List<List<PhotoIndexDb.Row>>>(listOf(emptyList(), emptyList(), emptyList()), version) {
        value = withContext(Dispatchers.IO) {
            states.map { s -> runCatching { engine.db.rows("state=?", arrayOf("$s"), order = "hidden_at DESC", limit = 600) }.getOrDefault(emptyList()) }
        }
    }
    val rows = all[tab]
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, end = 20.dp)) {
            IconButton(onBack) { @Suppress("DEPRECATION") Icon(Icons.Filled.ArrowBack, "Back") }
            Column {
                Text("Hidden photos", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Text("Never shown on the stand", fontSize = 13.sp, color = Muted)
            }
        }
        Segmented(listOf("You hid · ${all[0].size}", "Look-alikes · ${all[1].size}", "Bursts · ${all[2].size}"), tab,
            { tab = it; selected = emptySet() }, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            if (rows.isEmpty()) Text(when (tab) {
                0 -> "Nothing yet. Swipe down on a photo on the stand (by day) to hide it."
                1 -> "Photos that look like ones you hid appear here."
                else -> "Extra shots from bursts appear here; the best one is shown."
            }, fontSize = 14.sp, color = Muted, modifier = Modifier.padding(8.dp))
            rows.chunked(3).forEach { line ->
                Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    line.forEach { r ->
                        val on = r.mediaId in selected
                        HiddenTile(r, on, Modifier.weight(1f)) { selected = if (on) selected - r.mediaId else selected + r.mediaId }
                    }
                    repeat(3 - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth().background(Surface1).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (selected.isEmpty()) "Tap photos to select" else "${selected.size} selected", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(if (tab == 2) "Restoring shows it besides the best shot" else "Restoring teaches it you like these", fontSize = 13.sp, color = Muted)
            }
            Button({ engine.restore(selected) { selected = emptySet(); version++ } }, enabled = selected.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Restore", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun HiddenTile(r: PhotoIndexDb.Row, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalContext.current
    val thumb by produceState<ImageBitmap?>(null, r.mediaId) {
        value = withContext(Dispatchers.IO) {
            val uri = r.uri?.let(Uri::parse) ?: PhotoLibrary.uri(r.mediaId)
            runCatching { c.contentResolver.loadThumbnail(uri, Size(300, 300), null).asImageBitmap() }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(14.dp)
    Box(modifier.aspectRatio(1f).clip(shape).background(Surface3)
        .border(if (selected) 3.dp else 0.dp, if (selected) Accent else Color.Transparent, shape).clickable(onClick = onClick)) {
        thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(24.dp).clip(CircleShape)
            .background(if (selected) Accent else Color(0x66000000)).border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(16.dp))
        }
        val date = if (r.dateTaken > 0) SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(r.dateTaken)) else null
        if (date != null) Text(date, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(8.dp))
                .background(Color(0x99000000)).padding(horizontal = 7.dp, vertical = 2.dp))
    }
}

/** Debug tab card: model state, DB counts, last scoring latency. */
@Composable
fun SmartPhotosDebug(tick: Long) {
    val c = LocalContext.current
    val engine = remember { SmartPhotos.get(c) }
    val counts by produceState<PhotoIndexDb.Counts?>(null, tick / 3000) {
        value = withContext(Dispatchers.IO) { runCatching { engine.db.counts() }.getOrNull() }
    }
    val file = ModelStore.file(c)
    val model = when {
        !file.exists() -> "not downloaded"
        else -> when (engine.host.state) {
            com.nightstand.photos.smart.EmbedderHost.State.LOADED -> "loaded"
            com.nightstand.photos.smart.EmbedderHost.State.UNLOADED -> "unloaded"
            com.nightstand.photos.smart.EmbedderHost.State.FAILED -> "failed: " + (engine.host.lastError ?: "?")
        } + " · %.1f MB · %d loads".format(file.length() / 1e6, engine.host.loads)
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).background(Surface2).padding(12.dp)) {
        Text("SMART PHOTOS", fontSize = 11.sp, color = Muted, fontWeight = FontWeight.Bold)
        Text("Model: $model", fontSize = 13.sp)
        Text("Selection: ${if (Prefs.photoSmart(c)) "Smart" else "Off"} · last pick ${engine.lastMode}", fontSize = 13.sp)
        Text("Last scoring latency: " + engine.host.lastLatencyMs.let { if (it < 0) "—" else "$it ms" }, fontSize = 13.sp)
        Text("Scored in the background tonight: ${engine.trickledTonight} / ${SmartPhotos.TRICKLE_CAP}", fontSize = 13.sp)
        Text(counts?.let {
            "DB: ${it.photos} photos · ${it.scored} scored · ${it.junk} junk · ${it.hiddenByYou} hidden · " +
                "${it.lookAlikes} look-alikes · ${it.bursts} burst extras (${it.groups} groups) · ${it.swipes} live swipes"
        } ?: "DB: …", fontSize = 13.sp)
    }
}
