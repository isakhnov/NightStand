package com.nightstand

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.nightstand.ui.NightstandTheme
import com.nightstand.ui.SettingsApp
import com.nightstand.ui.Tab
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders each settings tab to app/build/previews/settings-TAB.png (phone portrait). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-port-xhdpi")
class SettingsPreviewTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(tab: Tab) {
        val c = ApplicationProvider.getApplicationContext<android.content.Context>()
        Prefs.setShowDebug(c, true)
        rule.setContent { NightstandTheme { SettingsApp(tab) } }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        val out = File("build/previews").apply { mkdirs() }
        File(out, "settings-${tab.name.lowercase()}.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun home() = shot(Tab.HOME)
    @Test fun views() = shot(Tab.VIEWS)
    @Test fun look() = shot(Tab.LOOK)
    @Test fun setup() = shot(Tab.SETUP)
    @Test fun debug() = shot(Tab.DEBUG)
}
