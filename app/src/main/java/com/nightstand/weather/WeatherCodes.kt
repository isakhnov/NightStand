package com.nightstand.weather

/** WMO weather interpretation codes (as used by Open-Meteo) → icon + label. Pure; unit-tested. */
object WeatherCodes {
    enum class Icon { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, THUNDER }

    fun icon(code: Int): Icon = when (code) {
        0, 1 -> Icon.CLEAR
        2 -> Icon.PARTLY_CLOUDY
        3 -> Icon.CLOUDY
        45, 48 -> Icon.FOG
        in 51..57 -> Icon.DRIZZLE
        in 61..67, in 80..82 -> Icon.RAIN
        in 71..77, 85, 86 -> Icon.SNOW
        in 95..99 -> Icon.THUNDER
        else -> Icon.CLOUDY
    }

    fun label(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly Clear"
        2 -> "Partly Cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        in 51..55 -> "Drizzle"
        56, 57 -> "Freezing Drizzle"
        61, 63 -> "Rain"
        65 -> "Heavy Rain"
        66, 67 -> "Freezing Rain"
        in 71..75, 77 -> "Snow"
        80, 81 -> "Showers"
        82 -> "Heavy Showers"
        85, 86 -> "Snow Showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm, Hail"
        else -> "—"
    }
}
