package com.nightstand.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nightstand.NightColor
import com.nightstand.Prefs
import kotlin.math.roundToInt

/** Live ambient light in lux, or null if the phone has no light sensor. */
@Composable
fun rememberLux(): Float? {
    val c = LocalContext.current
    var lux by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) {
        val sm = c.getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { lux = e.values[0] }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sm.unregisterListener(l) }
    }
    return lux
}

@Composable
fun LookTab(s: SettingsScope) {
    val c = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var night by remember { mutableStateOf(true) }
    var analog by remember { mutableStateOf(Prefs.clockStyle(c) == Prefs.ClockStyle.ANALOG) }
    var seconds by remember { mutableStateOf(Prefs.secondHand(c)) }
    var color by remember { mutableStateOf(Prefs.nightColor(c)) }
    var brightness by remember { mutableFloatStateOf(Prefs.nightBrightness(c)) }
    var dark by remember { mutableStateOf(Prefs.darkness(c)) }
    val lux = rememberLux()

    Column(Modifier.verticalScroll(rememberScrollState())) {
        ScreenHeader("Look", "Changes show in the preview right away")
        Column(Modifier.padding(horizontal = 14.dp)) {
            SettingsCard(padding = 10) {
                Segmented(listOf("Day", "Night"), if (night) 1 else 0, { night = it == 1 })
                Spacer(Modifier.height(10.dp))
                Box(Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, Surface3, RoundedCornerShape(14.dp))) {
                    PagePreviewImage(startViewId(c), night, key = "$version/${s.tick}")
                }
            }

            SectionLabel("Clock")
            SettingsCard {
                Segmented(listOf("◷  Analog", "12:45  Digital"), if (analog) 0 else 1, {
                    analog = it == 0
                    Prefs.setClockStyle(c, if (analog) Prefs.ClockStyle.ANALOG else Prefs.ClockStyle.DIGITAL); version++
                })
                SettingRow("Second hand", "Analog only") {
                    Switch(seconds, { seconds = it; Prefs.setSecondHand(c, it); version++ }, enabled = analog)
                }
                var secNight by remember { mutableStateOf(Prefs.secondHandAtNight(c)) }
                SettingRow("Second hand at night", "Off saves power: the face then redraws once a minute") {
                    Switch(secNight, { secNight = it; Prefs.setSecondHandAtNight(c, it); version++ }, enabled = analog && seconds)
                }
            }

            SectionLabel("Night mode")
            SettingsCard {
                Text("Color", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    NightColor.values().forEach { nc ->
                        val sel = nc == color
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { color = nc; Prefs.setNightColor(c, nc); night = true; version++ }) {
                            Box(Modifier.size(58.dp).border(if (sel) 3.dp else 0.dp, if (sel) OnDark else Color.Transparent, CircleShape)
                                .padding(5.dp).clip(CircleShape).background(Color(nc.argb)))
                            Text(nc.label, fontSize = 12.sp, color = if (sel) OnDark else Muted,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                SettingRow("Brightness", null, Modifier.padding(top = 8.dp)) {
                    Text("${(brightness * 100).roundToInt()}%", color = Accent, fontWeight = FontWeight.Bold)
                }
                Slider(brightness, { brightness = it; Prefs.setNightBrightness(c, it) },
                    valueRange = Prefs.MIN_NIGHT..1f, modifier = Modifier.fillMaxWidth())
                val now = lux?.let { l ->
                    "Now: ${if (l < 10) "%.1f".format(l) else l.roundToInt().toString()} lux · " +
                        if (l < dark.enterLux) "red" else if (l > dark.exitLux) "day" else "in between"
                } ?: "No light sensor: red from 22:00 to 07:00"
                var motion by remember { mutableStateOf(Prefs.motionToWake(c)) }
                SettingRow("Motion to wake", "At night, a bump of the nightstand brightens the display for a few seconds") {
                    Switch(motion, { motion = it; Prefs.setMotionToWake(c, it) })
                }
                SettingRow("Turns red when the room is", now, Modifier.padding(top = 6.dp))
                Segmented(Prefs.Darkness.values().map { it.label }, dark.ordinal, { i ->
                    dark = Prefs.Darkness.values()[i]; Prefs.setDarkness(c, dark)
                })
            }
            SectionLabel("Charging")
            SettingsCard {
                var ring by remember { mutableStateOf(Prefs.showBatteryRing(c)) }
                val limit = Prefs.chargeLimit(c)
                SettingRow("Charging ring (top-left)",
                    "Level, charge limit tick, solid when charged" +
                        if (limit < 100) "\nCharge limit learned: $limit %" else "\nNo charge limit seen yet") {
                    Switch(ring, { ring = it; Prefs.setShowBatteryRing(c, it) })
                }
                var hide95 by remember { mutableStateOf(Prefs.hideRingAbove95(c)) }
                SettingRow("Hide when charged above 95 %", "Show the ring only while it's still charging up") {
                    Switch(hide95, { hide95 = it; Prefs.setHideRingAbove95(c, it) }, enabled = ring)
                }
            }

            SectionLabel("Alarms & timers")
            SettingsCard {
                var alarm by remember { mutableStateOf(Prefs.showAlarm(c)) }
                var timers by remember { mutableStateOf(Prefs.showTimers(c)) }
                var bigDay by remember { mutableStateOf(Prefs.bigCountdownDay(c)) }
                var bigNight by remember { mutableStateOf(Prefs.bigCountdownNight(c)) }
                val detected = remember(s.tick, alarm) { com.nightstand.clock.Alarms.explain(c) }
                SettingRow("Next alarm", "Clock app alarms ringing within 24 h\nNow: $detected") {
                    Switch(alarm, { alarm = it; Prefs.setShowAlarm(c, it); version++ })
                }
                // Ownerless entries (Google Clock on the S26 — but also Samsung Routines/Bedtime):
                // let the user say which times aren't alarms.
                var hiddenTimes by remember { mutableStateOf(Prefs.hiddenAlarmTimes(c)) }
                val raw = remember(s.tick, hiddenTimes) {
                    c.getSystemService(android.app.AlarmManager::class.java).nextAlarmClock
                        ?.takeIf { it.showIntent?.creatorPackage == null }
                }
                raw?.let { info ->
                    val key = com.nightstand.clock.Alarms.timeKey(info.triggerTime)
                    val label = android.text.format.DateFormat.format(
                        if (android.text.format.DateFormat.is24HourFormat(c)) "HH:mm" else "h:mm a", info.triggerTime)
                    if (key !in hiddenTimes) androidx.compose.material3.TextButton({
                        Prefs.setAlarmTimeHidden(c, key, true); hiddenTimes = Prefs.hiddenAlarmTimes(c); version++
                    }) { Text("Not my alarm (Routine/Bedtime): hide alarms at $label") }
                }
                if (hiddenTimes.isNotEmpty()) {
                    Text("Hidden alarm times", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
                    hiddenTimes.sorted().forEach { key ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(key, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            androidx.compose.material3.TextButton({
                                Prefs.setAlarmTimeHidden(c, key, false); hiddenTimes = Prefs.hiddenAlarmTimes(c); version++
                            }) { Text("Unhide") }
                        }
                    }
                }
                val timerNow = remember(s.tick, timers) {
                    when {
                        !com.nightstand.NotificationFeed.isGranted(c) -> "Notification access is off (Setup)"
                        com.nightstand.NotificationFeed.timerReport.isEmpty() -> "no clock-app notification right now"
                        else -> com.nightstand.NotificationFeed.timerReport.joinToString("\n")
                    }
                }
                SettingRow("Running timers", "From the clock app's timer notification\nNow: $timerNow") {
                    Switch(timers, { timers = it; Prefs.setShowTimers(c, it); version++ })
                }
                SettingRow("Big countdown, last minute · day", "Replaces the right-hand view") {
                    Switch(bigDay, { bigDay = it; Prefs.setBigCountdownDay(c, it) }, enabled = timers)
                }
                SettingRow("Big countdown, last minute · night", null) {
                    Switch(bigNight, { bigNight = it; Prefs.setBigCountdownNight(c, it) }, enabled = timers)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
