package com.nightstand.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nightstand.Prefs

/** Shared state/actions every tab gets. */
class SettingsScope(
    val tick: Int,
    val perms: List<Perm>,
    val refresh: () -> Unit,
    val fix: (Perm) -> Unit,
    val goTo: (Tab) -> Unit,
)

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    @Suppress("DEPRECATION") VIEWS("Views", Icons.Filled.List),
    LOOK("Look", Icons.Filled.Edit),
    SETUP("Setup", Icons.Filled.CheckCircle),
    DEBUG("Debug", Icons.Filled.Build),
}

@Composable
fun SettingsApp(initialTab: Tab = Tab.HOME) {
    val c = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var tick by remember { mutableIntStateOf(0) }
    var showDebug by remember { mutableStateOf(Prefs.showDebug(c)) }

    // Re-read permission states whenever we come back from a system page.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val perms = remember(tick) { Permissions.list(c) }
    val missing = perms.count { !it.granted }

    val scope = SettingsScope(
        tick = tick, perms = perms,
        refresh = { tick++; showDebug = Prefs.showDebug(c) },
        fix = { p ->
            when {
                p.runtime != null -> launcher.launch(p.runtime)
                p.settingsIntent != null -> runCatching { c.startActivity(p.settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        },
        goTo = { tab = it },
    )
    if (!showDebug && tab == Tab.DEBUG) tab = Tab.SETUP
    val tabs = Tab.values().filter { it != Tab.DEBUG || showDebug }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Surface1) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = {
                            if (t == Tab.SETUP && missing > 0) BadgedBox(badge = { Badge { Text("$missing") } }) { Icon(t.icon, null) }
                            else Icon(t.icon, null)
                        },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = AccentBg, selectedIconColor = Accent),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                Tab.HOME -> HomeTab(scope)
                Tab.VIEWS -> ViewsTab(scope)
                Tab.LOOK -> LookTab(scope)
                Tab.SETUP -> SetupTab(scope)
                Tab.DEBUG -> DebugTab(scope)
            }
        }
    }
}
