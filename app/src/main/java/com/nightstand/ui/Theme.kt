package com.nightstand.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Accent = Color(0xFFFF9F0A)
val AccentBg = Color(0xFF3A2A10)
val Ok = Color(0xFF34C759)
val OkBg = Color(0xFF14301C)
val Warn = Color(0xFFFFB340)
val WarnBg = Color(0xFF2A1F0C)
val Surface1 = Color(0xFF121214)
val Surface2 = Color(0xFF1C1C1F)
val Surface3 = Color(0xFF26262A)
val Muted = Color(0xFF9A9AA2)
val OnDark = Color(0xFFF2F2F5)

@Composable
fun NightstandTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Accent, onPrimary = Color(0xFF1A1205),
        primaryContainer = AccentBg, onPrimaryContainer = Accent,
        secondaryContainer = AccentBg, onSecondaryContainer = Accent,
        background = Color.Black, onBackground = OnDark,
        surface = Color.Black, onSurface = OnDark,
        surfaceVariant = Surface2, onSurfaceVariant = Muted,
        surfaceContainer = Surface1, outline = Color(0xFF4A4A50),
    ),
    content = content,
)

@Composable
fun ScreenHeader(title: String, sub: String? = null) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 14.dp)) {
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        if (sub != null) Text(sub, fontSize = 13.sp, color = Muted)
    }
}

@Composable
fun SectionLabel(text: String) = Text(
    text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Muted,
    letterSpacing = 0.8.sp, modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp),
)

@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    color: Color = Surface1,
    padding: Int = 16,
    content: @Composable ColumnScope.() -> Unit,
) = Surface(
    color = color, shape = RoundedCornerShape(22.dp),
    modifier = modifier.fillMaxWidth().padding(bottom = 12.dp),
) { Column(Modifier.padding(padding.dp), content = content) }

@Composable
fun SettingRow(
    title: String,
    sub: String? = null,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) = Row(
    modifier.fillMaxWidth().padding(vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
) {
    leading?.invoke()
    Column(Modifier.weight(1f)) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (sub != null) Text(sub, fontSize = 13.sp, color = Muted)
    }
    trailing?.invoke(this)
}

/** Simple segmented control matching the mockups (no experimental APIs). */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface2).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (i == selected) Surface3 else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (i == selected) OnDark else Muted)
            }
        }
    }
}

@Composable
fun Pill(text: String, ok: Boolean) = Text(
    text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (ok) Ok else Warn,
    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (ok) OkBg else AccentBg)
        .padding(horizontal = 9.dp, vertical = 3.dp),
)

@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) = Text(
    text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) Accent else OnDark,
    modifier = Modifier.clip(RoundedCornerShape(10.dp))
        .background(if (on) AccentBg else Color.Transparent)
        .border(1.5.dp, if (on) Accent else Color(0xFF4A4A50), RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
)
