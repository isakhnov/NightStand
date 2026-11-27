package com.nightstand.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * About & credits (Setup tab). Required attributions:
 *  - Open-Meteo weather data: CC BY 4.0 → attribution with links.
 *  - MobileCLIP-S0 (Apple): its licence asks to keep the notice → full text
 *    shipped in assets/licenses and shown here.
 */
@Composable
fun CreditsCard(version: String) {
    val c = LocalContext.current
    var showApple by remember { mutableStateOf(false) }
    fun open(url: String) = runCatching {
        c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    SettingsCard(padding = 14) {
        Text("Nightstand $version", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("Bedside display for Android. Everything runs on this phone.", fontSize = 13.sp, color = Muted)
        HorizontalDivider(color = Surface2, modifier = Modifier.padding(vertical = 10.dp))

        Credit("Weather data by Open-Meteo.com", "Licensed under CC BY 4.0 · tap for details") {
            open("https://open-meteo.com/")
        }
        Credit("CC BY 4.0 licence", "creativecommons.org/licenses/by/4.0") {
            open("https://creativecommons.org/licenses/by/4.0/")
        }
        Credit("MobileCLIP-S0 image model", "© 2024 Apple Inc. · converted to TensorFlow Lite · tap for licence") {
            showApple = true
        }
        Credit("LiteRT (TensorFlow Lite) via Google Play services", "Apache License 2.0")
        Credit("AndroidX, Jetpack Compose, Kotlin", "Apache License 2.0")
    }

    if (showApple) {
        val text = remember {
            runCatching { c.assets.open("licenses/mobileclip-apple-LICENSE.txt").bufferedReader().readText() }
                .getOrDefault("Licence text missing.")
        }
        AlertDialog(
            onDismissRequest = { showApple = false },
            title = { Text("MobileCLIP licence") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                }
            },
            confirmButton = { TextButton({ showApple = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun Credit(title: String, sub: String, onClick: (() -> Unit)? = null) {
    Column((if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 6.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(sub, fontSize = 12.sp, color = if (onClick != null) Accent else Muted)
    }
}
