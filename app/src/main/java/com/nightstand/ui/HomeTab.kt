package com.nightstand.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nightstand.NightstandActivity
import com.nightstand.Prefs
import com.nightstand.WatcherService
import com.nightstand.pages.PageRegistry
import kotlin.math.roundToInt

/** The view Nightstand will open on: fixed choice, else last used, else first enabled. */
fun startViewId(c: android.content.Context): String {
    val enabled = PageRegistry.ordered(c).filter { it.id !in Prefs.disabledPages(c) }.ifEmpty { PageRegistry.all.take(1) }
    val want = Prefs.startPage(c) ?: Prefs.lastPage(c)
    return enabled.firstOrNull { it.id == want }?.id ?: enabled.first().id
}

@Composable
fun HomeTab(s: SettingsScope) {
    val c = LocalContext.current
    val version = remember { runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "" }
    var watcherOn by remember { mutableStateOf(Prefs.enabled(c)) }
    var startFixed by remember { mutableStateOf(Prefs.startPage(c) != null) }
    var startId by remember { mutableStateOf(Prefs.startPage(c)) }
    var brightness by remember { mutableFloatStateOf(Prefs.nightBrightness(c)) }
    val missing = s.perms.count { !it.granted }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        ScreenHeader("Nightstand", "v$version · ${android.os.Build.MODEL}")
        Column(Modifier.padding(horizontal = 14.dp)) {
            // Status
            SettingsCard(color = if (missing == 0) OkBg else WarnBg) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(if (missing == 0) Ok.copy(alpha = .18f) else AccentBg),
                        contentAlignment = Alignment.Center) {
                        Text(if (missing == 0) "✓" else "!", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                            color = if (missing == 0) Ok else Warn)
                    }
                    Column(Modifier.weight(1f).clickable(enabled = missing > 0) { s.goTo(Tab.SETUP) }) {
                        Text(if (!watcherOn) "Watcher is off" else if (missing == 0) "Ready" else "Watching for the charger",
                            fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (missing == 0) "Watching for the charger · all permissions granted"
                            else "$missing need attention · tap to fix ›",
                            fontSize = 13.sp, color = if (missing == 0) Muted else Warn,
                        )
                    }
                    Switch(watcherOn, {
                        watcherOn = it; Prefs.setEnabled(c, it)
                        if (it) WatcherService.start(c) else c.stopService(Intent(c, WatcherService::class.java))
                    })
                }
            }

            // Preview of tonight
            val previewId = remember(s.tick, startFixed, startId) { startViewId(c) }
            SettingsCard(padding = 0) {
                PagePreviewImage(previewId, night = true, key = s.tick)
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(PageRegistry.title(previewId) ?: "", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("What you'll see tonight · night mode", fontSize = 13.sp, color = Muted)
                    }
                    Button({ c.startActivity(Intent(c, NightstandActivity::class.java)) }) { Text("▶  Preview", fontWeight = FontWeight.Bold) }
                }
            }

            SectionLabel("Start with")
            SettingsCard {
                Segmented(listOf("Last used", "Fixed view"), if (startFixed) 1 else 0, { i ->
                    startFixed = i == 1
                    if (!startFixed) { startId = null; Prefs.setStartPage(c, null) }
                    else { startId = startViewId(c); Prefs.setStartPage(c, startId) }
                })
                if (startFixed) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        SettingRow(PageRegistry.title(startId) ?: "Choose a view",
                            "Opens first every time the phone goes on the stand",
                            Modifier.clickable { open = true }) { Text("▾", fontSize = 20.sp, color = Muted) }
                        DropdownMenu(open, { open = false }) {
                            PageRegistry.ordered(c).filter { it.id !in Prefs.disabledPages(c) }.forEach { e ->
                                DropdownMenuItem(text = { Text(e.title) }, onClick = {
                                    startId = e.id; Prefs.setStartPage(c, e.id); open = false
                                })
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text("Reopens on the view you swiped to last.", fontSize = 13.sp, color = Muted,
                        modifier = Modifier.padding(horizontal = 4.dp))
                }
            }

            SectionLabel("Tonight")
            SettingsCard {
                SettingRow("Night brightness", "Also: swipe up/down on the display") {
                    Text("${(brightness * 100).roundToInt()}%", color = Accent, fontWeight = FontWeight.Bold)
                }
                Slider(brightness, { brightness = it; Prefs.setNightBrightness(c, it) },
                    valueRange = Prefs.MIN_NIGHT..1f, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
