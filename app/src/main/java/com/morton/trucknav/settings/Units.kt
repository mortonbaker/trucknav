package com.morton.trucknav.settings

object Units {
    fun distance(meters: Double): String {
        val metric = Settings.get("units") == "metric"
        val value = if (metric) meters / 1000.0 else meters / 1609.344
        return (if (value < 10) "%.1f".format(value) else value.toInt().toString()) + if (metric) " km" else " mi"
    }
}
