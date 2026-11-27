package com.nightstand.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nightstand.Prefs

@Composable
fun SetupTab(s: SettingsScope) {
    val c = LocalContext.current
    val done = s.perms.count { it.granted }
    val missing = s.perms.size - done
    var debug by remember { mutableStateOf(Prefs.showDebug(c)) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        ScreenHeader("Setup", "$done of ${s.perms.size} done")
        Column(Modifier.padding(horizontal = 14.dp)) {
            if (missing > 0) SettingsCard(color = WarnBg) {
                Text("$missing thing${if (missing > 1) "s" else ""} need attention", color = Warn, fontWeight = FontWeight.Bold)
                Text("Required items keep Nightstand appearing overnight; the others enable views.",
                    fontSize = 13.sp, color = Muted)
            }
            PermSection("Required", s.perms.filter { it.required }, s)
            PermSection("For views", s.perms.filter { !it.required }, s)

            SectionLabel("Advanced")
            SettingsCard {
                SettingRow("Show Debug tab", "Live sensors, test buttons and the log") {
                    Switch(debug, { debug = it; Prefs.setShowDebug(c, it); s.refresh() })
                }
            }
            SectionLabel("About & credits")
            val version = remember { runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "" }
            CreditsCard(version ?: "")
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun PermSection(title: String, list: List<Perm>, s: SettingsScope) {
    SectionLabel(title)
    SettingsCard(padding = 12) {
        list.forEachIndexed { i, p ->
            SettingRow(p.title, if (!p.granted && p.problem != null) p.problem else p.sub) {
                if (p.granted) Pill("On", ok = true)
                else FilledTonalButton({ s.fix(p) }) { Text(p.fixLabel, fontWeight = FontWeight.Bold) }
            }
            if (i < list.size - 1) HorizontalDivider(color = Surface2)
        }
    }
}
