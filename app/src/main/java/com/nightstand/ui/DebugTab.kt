package com.nightstand.ui

import android.app.KeyguardManager
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nightstand.PostureDetector
import com.nightstand.StandByState
import com.nightstand.WatcherService
import com.nightstand.weather.Weather
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Suppress("UNUSED_PARAMETER") // same signature as the other tabs
@Composable
fun DebugTab(s: SettingsScope) {
    val c = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val lux = rememberLux()
    var force by remember { mutableStateOf(StandByState.forceNight) }

    @Suppress("UNUSED_EXPRESSION") now // recompose every second
    val batt = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val plugged = (batt?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    val pct = batt?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) }
    val locked = c.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    val up = StandByState.watcherStartedAt.takeIf { it > 0 }?.let { (now - it) / 60_000 }
    val posture = StandByState.lastPosture

    Column(Modifier.verticalScroll(rememberScrollState())) {
        ScreenHeader("Debug", "Live — updates every second")
        Column(Modifier.padding(horizontal = 14.dp)) {
            val cells = listOf(
                Triple("POSTURE", posture.name.lowercase().replaceFirstChar { it.uppercase() },
                    if (posture == PostureDetector.Posture.LANDSCAPE) Ok else OnDark),
                Triple("LIGHT", lux?.let { "%.1f lux".format(it) } ?: "—", OnDark),
                Triple("POWER", (if (plugged) "Plugged" else "Battery") + (pct?.let { " · $it%" } ?: ""), OnDark),
                Triple("LOCKED", if (locked) "Yes" else "No", OnDark),
                Triple("NIGHT MODE", when (force) { true -> "Forced on"; false -> "Forced off"; null -> "Auto" }, OnDark),
                Triple("WATCHER", up?.let { "Running ${it / 60}h ${it % 60}m" } ?: "Not running", if (up != null) OnDark else Warn),
            )
            cells.chunked(2).forEach { row ->
                Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (k, v, col) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Surface2).padding(12.dp)) {
                            Text(k, fontSize = 11.sp, color = Muted, fontWeight = FontWeight.Bold)
                            Text(v, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = col)
                        }
                    }
                }
            }
            Text("Posture is only measured while plugged in.", fontSize = 12.sp, color = Muted)

            SectionLabel("Test")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ WatcherService.launch(c) }) { Text("Launch now") }
                OutlinedButton({
                    force = when (force) { null -> true; true -> false; false -> null }
                    StandByState.forceNight = force
                }) { Text("Night: " + when (force) { null -> "auto"; true -> "on"; false -> "off" }) }
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ Weather.invalidate(); Weather.refreshIfStale(c) {} }) { Text("Refresh weather") }
                // Start a timer in the Clock app first; the dump finalises the timer parser.
                OutlinedButton({
                    val alarm = (c.getSystemService(android.app.AlarmManager::class.java)).nextAlarmClock
                    WatcherService.log("next alarm raw: " + (alarm?.let {
                        "${java.util.Date(it.triggerTime)} from ${it.showIntent?.creatorPackage}"
                    } ?: "none"))
                    val dump = com.nightstand.NotificationFeed.clockDump
                    WatcherService.log("clock notifications:\n" + dump.ifEmpty { "(none — is a timer running? is Notification access on?)" })
                    WatcherService.log("parsed timers: " + com.nightstand.NotificationFeed.timers)
                }) { Text("Capture clock notifications") }
            }

            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp, start = 8.dp)) {
                Text("LOG", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Muted, modifier = Modifier.weight(1f))
                Text("SHARE", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Accent, modifier = Modifier.clickable {
                    c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Nightstand log")
                        putExtra(Intent.EXTRA_TEXT, StandByState.logText())
                    }, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
            }
            SettingsCard(padding = 14) {
                Text(StandByState.logText().ifEmpty { "(empty — events appear here as they happen)" },
                    fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 17.sp, color = Color(0xFFB9B9C0))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
