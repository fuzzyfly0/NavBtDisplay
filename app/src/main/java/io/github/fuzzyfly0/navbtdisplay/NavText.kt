package io.github.fuzzyfly0.navbtdisplay

/** Raw text fields of one navigation notification, before any interpretation. */
data class RawNavFields(
    val title: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    val subText: String? = null,
    val infoText: String? = null,
    /** Texts found in custom RemoteViews, in layout order (fallback for apps without extras). */
    val viewTexts: List<String> = emptyList(),
)

/** The guidance split into its parts; every part is optional. */
data class NavInfo(
    /** Distance to the next maneuver, e.g. "300 m". */
    val distance: String? = null,
    /** The maneuver without distance and (if it could be split off) without the street. */
    val maneuver: String? = null,
    /** Street / road / direction of the next maneuver, e.g. "Hauptstraße" or "Richtung München". */
    val street: String? = null,
    /** Trip summary: arrival time, remaining time and distance. */
    val summary: String? = null,
)

/**
 * What is sent to the car as AVRCP metadata.
 *
 * [maneuverKey] identifies the current maneuver independent of the counting-down
 * distance, so "300 m · Turn right" and "200 m · Turn right" share one key.
 */
data class CarText(
    val title: String,
    val artist: String,
    val album: String,
    val maneuverKey: String,
)

/**
 * Pure text logic, kept free of Android classes so it can be unit-tested on the JVM.
 *
 * The heuristics cover English, German and Czech wording (Google Maps, Mapy.com).
 * They have to be checked against real notifications, see the debug log.
 */
object NavText {

    private val invisibleChars = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")
    private val whitespace = Regex("\\s+")

    private const val DIST = "\\d+(?:[.,]\\d+)?\\s?(?:km|m|mi|ft|yd)"

    /** A field that is only a distance: "300 m", "In 1,2 km", "za 300 m". */
    private val distanceOnly = Regex("^(?:in|za)?\\s*($DIST)$", RegexOption.IGNORE_CASE)

    /** A distance at the start of a maneuver: "In 300 m rechts abbiegen", "300 m · Turn right". */
    private val distancePrefix =
        Regex("^(?:in|za)?\\s*($DIST)\\b\\s*[·,:\\-–]?\\s*(.+)$", RegexOption.IGNORE_CASE)

    /** A distance at the end of a maneuver: "Turn left in 300 m". */
    private val distanceSuffix =
        Regex("^(.+?)\\s*[·,]?\\s+(?:in|za)\\s+($DIST)$", RegexOption.IGNORE_CASE)

    /** Clock times, durations and arrival words that mark the trip summary. */
    private val summaryMarker = Regex(
        "\\b\\d{1,2}:\\d{2}\\b|\\b\\d+\\s?(?:min|std|h|hr|hod)\\b|\\b(?:eta|arrival|ankunft|příjezd|cíl)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Words after which the street / direction follows. Directions keep their
     * word ("Richtung München"), plain prepositions are dropped ("auf Hauptstraße" → "Hauptstraße").
     * " to " is deliberately missing: "Keep left to stay on A9".
     */
    private val streetMarker = Regex(
        "\\s(?:(onto|on|into|auf|in die|in den|in|na|do)|(toward|towards|richtung|směr|ve směru))\\s+(.+)$",
        RegexOption.IGNORE_CASE,
    )

    private val unitsForKey = Regex(
        "\\d+(?:[.,]\\d+)?\\s*(?:km|m|mi|ft|yd|min|h|std)?\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Removes bidi/zero-width marks and collapses all whitespace (incl. newlines). */
    fun clean(s: String?): String? =
        s?.replace(invisibleChars, "")?.replace(whitespace, " ")?.trim()?.takeIf { it.isNotEmpty() }

    /** The maneuver text with all numbers and distance/time units removed. */
    fun maneuverKey(s: String): String =
        s.replace(unitsForKey, " ").replace(whitespace, " ").trim().lowercase()

    private fun isDistance(s: String) = distanceOnly.matches(s)
    private fun isSummary(s: String) = summaryMarker.containsMatchIn(s)

    /** Splits the notification fields into [NavInfo], or `null` if there is no maneuver at all. */
    fun parse(raw: RawNavFields): NavInfo? {
        // The first line of the big text is usually the same as the text; further
        // lines ("Then turn left", lanes) are too much for a car display.
        val bigFirstLine = raw.bigText?.lineSequence()?.firstOrNull { it.isNotBlank() }
        val extras = listOfNotNull(clean(raw.title), clean(raw.text) ?: clean(bigFirstLine)).distinct()
        val candidates = extras.ifEmpty { raw.viewTexts.mapNotNull(::clean).distinct() }
        if (candidates.isEmpty()) return null

        var distance = candidates.firstOrNull(::isDistance)?.let { distanceOnly.find(it)!!.groupValues[1] }
        var maneuver = candidates.firstOrNull { !isDistance(it) && !isSummary(it) }
        if (maneuver != null && distance == null) {
            val prefix = distancePrefix.find(maneuver!!)
            val suffix = distanceSuffix.find(maneuver!!)
            if (prefix != null) {
                distance = prefix.groupValues[1]
                maneuver = prefix.groupValues[2]
            } else if (suffix != null) {
                distance = suffix.groupValues[2]
                maneuver = suffix.groupValues[1]
            }
        }
        val summary = clean(raw.subText)
            ?: clean(raw.infoText)
            ?: candidates.firstOrNull { it != maneuver && !isDistance(it) && isSummary(it) }

        var street: String? = null
        maneuver?.let { m ->
            streetMarker.find(m)?.let { match ->
                val before = m.substring(0, match.range.first).trim()
                // Only split if a maneuver word remains ("Weiter auf B27" → "Weiter" + "B27").
                if (before.isNotEmpty()) {
                    val direction = match.groupValues[2]
                    val rest = match.groupValues[3].trim()
                    street = if (direction.isNotEmpty()) "$direction $rest" else rest
                    maneuver = before
                }
            }
        }

        if (maneuver == null && distance == null) return null
        return NavInfo(distance = distance, maneuver = maneuver, street = street, summary = summary)
    }

    /**
     * Car layout: title = distance + maneuver, artist = street, album = trip summary.
     * Missing parts move up, so the most important information is always in the title.
     */
    fun compose(appLabel: String, raw: RawNavFields): CarText? {
        val info = parse(raw) ?: return null
        val title = listOfNotNull(info.distance, info.maneuver).joinToString(" · ")
        val artist: String
        val album: String
        when {
            info.street != null -> {
                artist = info.street
                album = info.summary ?: appLabel
            }
            info.summary != null -> {
                artist = info.summary
                album = appLabel
            }
            else -> {
                artist = appLabel
                album = appLabel
            }
        }
        return CarText(
            title = title,
            artist = artist,
            album = album,
            maneuverKey = maneuverKey(listOfNotNull(info.maneuver, info.street).joinToString(" ")),
        )
    }
}
