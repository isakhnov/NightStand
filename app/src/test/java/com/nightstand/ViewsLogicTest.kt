package com.nightstand

import com.nightstand.media.NowPlaying
import com.nightstand.photos.KenBurns
import com.nightstand.weather.WeatherCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewsLogicTest {
    @Test fun weatherCodes() {
        assertEquals(WeatherCodes.Icon.CLEAR, WeatherCodes.icon(0))
        assertEquals(WeatherCodes.Icon.PARTLY_CLOUDY, WeatherCodes.icon(2))
        assertEquals(WeatherCodes.Icon.RAIN, WeatherCodes.icon(63))
        assertEquals(WeatherCodes.Icon.RAIN, WeatherCodes.icon(81))
        assertEquals(WeatherCodes.Icon.SNOW, WeatherCodes.icon(85))
        assertEquals(WeatherCodes.Icon.THUNDER, WeatherCodes.icon(95))
        assertEquals("Rain", WeatherCodes.label(61))
    }

    private val eps = 0.01f

    @Test fun kenBurns_startsFittedEndsFilled() {
        // Landscape 4000x3000 photo into a 500x400 panel.
        val start = KenBurns.frame(4000, 3000, 500f, 400f, 0, 12_000, 45_000, 1)
        assertEquals(500f / 4000, start.scale, eps)                 // fit: width-limited
        assertTrue(start.top > 0)                                  // letterboxed top/bottom
        val filled = KenBurns.frame(4000, 3000, 500f, 400f, 12_000, 12_000, 45_000, 1)
        assertEquals(400f / 3000, filled.scale, 1e-4f)              // fill: height-limited
        assertEquals(0f, filled.top, eps)
        assertEquals((500f - 4000 * 400f / 3000) / 2, filled.left, eps) // centred
    }

    @Test fun kenBurns_panRevealsCroppedEdge() {
        val end = KenBurns.frame(4000, 3000, 500f, 400f, 45_000, 12_000, 45_000, 1)
        val w = 4000 * 400f / 3000
        assertEquals(0f, end.left, eps)                            // slid to the left edge
        val endOther = KenBurns.frame(4000, 3000, 500f, 400f, 45_000, 12_000, 45_000, -1)
        assertEquals(500f - w, endOther.left, eps)                 // or to the right edge
    }

    @Test fun timeFormat() {
        assertEquals("1:43", NowPlaying.format(103_400))
        assertEquals("0:00", NowPlaying.format(-5))
    }
}
