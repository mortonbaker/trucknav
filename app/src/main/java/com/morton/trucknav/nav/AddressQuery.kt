package com.morton.trucknav.nav

// Pure text rules for the "Where to?" field, kept free of Android so they unit-test on the JVM.
// Photon returns nothing at all for a full postal address with a suite and a ZIP
// ("8131 Gateway Dr Ste 500, Argyle, TX 76226"), and komoot's index has no house point for
// many new-build addresses, so: strip what Photon chokes on, and let a house-numbered query
// fall back to Nominatim when Photon has no hit on that number.
object AddressQuery {
    // Designator + a value that has a digit or is one letter ("Ste 500", "Suite A", "Apt. 12B"),
    // so "Space Center" and "Main St Floral" survive.
    private val unit = Regex("""(?i)(,\s*|\s+)(?:ste|suite|unit|apt|apartment|bldg|building|rm|room|fl|floor|spc|space|lot)\b\.?\s*#?\s*(?:[a-z]?\d[a-z0-9-]*|[a-z])\b""")
    private val hashUnit = Regex("""\s*#\s*[a-z0-9-]+\b""", RegexOption.IGNORE_CASE)
    private val zip = Regex("""\b\d{5}(?:-\d{4})?\s*$""")
    private val leadingNumber = Regex("""^\s*(\d+[a-zA-Z]?)\s+\S""")
    private val coords = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*[, ]\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")

    /** Query as sent to the geocoder: no suite/unit, no trailing ZIP, tidy separators. */
    fun normalize(q: String): String {
        var s = q.trim()
        s = unit.replace(s, "")
        s = hashUnit.replace(s, "")
        s = zip.replace(s, "")
        return s.replace(Regex("""\s*,\s*"""), ", ").replace(Regex("""\s+"""), " ").trim().trimEnd(',').trim()
    }

    /** "8131 Gateway Dr ..." → "8131"; null when the query does not start with a house number. */
    fun houseNumber(q: String): String? = leadingNumber.find(q)?.groupValues?.get(1)

    /** "33.09962, -97.21646" → (lat, lng); null unless both are in range. */
    fun coordinates(q: String): Pair<Double, Double>? {
        val m = coords.find(q) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lng = m.groupValues[2].toDoubleOrNull() ?: return null
        return if (lat in -90.0..90.0 && lng in -180.0..180.0) lat to lng else null
    }
}
