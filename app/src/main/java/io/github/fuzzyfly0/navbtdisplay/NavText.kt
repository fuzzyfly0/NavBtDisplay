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

/**
 * What is sent to the car as AVRCP metadata.
 *
 * [maneuverKey] identifies the current maneuver independent of the counting-down
 * distance, so "In 300 m turn right" and "In 200 m turn right" share one key.
 */
data class CarText(
    val title: String,
    val artist: String,
    val album: String,
    val maneuverKey: String,
)

/** Pure text logic, kept free of Android classes so it can be unit-tested on the JVM. */
object NavText {

    private val invisibleChars = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")
    private val whitespace = Regex("\\s+")
    private val distance = Regex(
        "\\d+(?:[.,]\\d+)?\\s*(?:km|m|mi|ft|yd|min|h|std|meter|metern|kilometer)?\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Removes bidi/zero-width marks and collapses all whitespace (incl. newlines). */
    fun clean(s: String?): String? =
        s?.replace(invisibleChars, "")?.replace(whitespace, " ")?.trim()?.takeIf { it.isNotEmpty() }

    /** The maneuver text with all numbers and distance/time units removed. */
    fun maneuverKey(s: String): String =
        s.replace(distance, " ").replace(whitespace, " ").trim().lowercase()

    /**
     * Turns the notification fields into car text, or `null` if there is nothing
     * usable (e.g. a "GPS signal lost" placeholder without any text).
     */
    fun compose(appLabel: String, raw: RawNavFields): CarText? {
        val title = clean(raw.title)
        // The expanded text often repeats or extends the collapsed one; prefer it.
        val body = clean(raw.bigText) ?: clean(raw.text)
        val views = raw.viewTexts.mapNotNull(::clean).distinct()

        // Drop whichever of title/body is already contained in the other one.
        val fromExtras = when {
            title == null || body == null -> listOfNotNull(title, body)
            body.contains(title) -> listOf(body)
            title.contains(body) -> listOf(title)
            else -> listOf(title, body)
        }
        val usesViews = fromExtras.isEmpty()
        val primary = if (usesViews) views.take(2) else fromExtras
        if (primary.isEmpty()) return null

        val carTitle = primary.joinToString(" · ")
        val artist = clean(raw.subText)
            ?: clean(raw.infoText)
            ?: (if (usesViews) views.getOrNull(2) else null)
            ?: appLabel
        return CarText(
            title = carTitle,
            artist = artist,
            album = appLabel,
            maneuverKey = maneuverKey(carTitle),
        )
    }
}
