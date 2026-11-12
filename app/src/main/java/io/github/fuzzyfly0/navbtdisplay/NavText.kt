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
    /**
     * Localized maneuver derived from the notification icon's resource name, e.g. "Rechts".
     * Some apps (Mapy.com) show the direction only as an arrow icon, not as text.
     */
    val iconManeuver: String? = null,
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
    /** The maneuver after the next one, e.g. "Dann links abbiegen". */
    val then: String? = null,
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

    /** Words that show a text already says which way to go. */
    private val directionWord = Regex(
        "\\b(?:links|rechts|left|right|vlevo|vpravo|doleva|doprava|geradeaus|straight|continue|weiter|rovně|" +
            "pokračujte|wenden|u-turn|kreisverkehr|roundabout|kruhov\\w*|ausfahrt|exit|sjezd\\w*|výjezd\\w*|" +
            "ziel|destination|keep|halten|abbiegen|turn|odbočte|merge|einfädeln)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** The second line of the big text when it announces the following maneuver. */
    private val thenMarker = Regex("^(?:dann|then|poté|potom|pak)\\b", RegexOption.IGNORE_CASE)

    /** App names that some apps put into a text field; they carry no guidance. */
    private val appName = Regex(
        "^(?:google maps|maps|mapy(?:\\.cz|\\.com)?|blitzer\\.de(?: pro)?)$",
        RegexOption.IGNORE_CASE,
    )

    /** Lines of a warning notification that do contain a warning. */
    private val warningMarker = Regex(
        "blitzer|radar|messung|kontrolle|gefahr|stau|unfall|baustelle|panne|glätte|achtung|warnung|" +
            "speed ?camera|hazard|\\d+\\s?km/h",
        RegexOption.IGNORE_CASE,
    )

    /** Removed before looking for [warningMarker], so "Blitzer.de ist aktiv" is no warning. */
    private val brandName = Regex("blitzer\\.de(?: pro)?", RegexOption.IGNORE_CASE)

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
    private fun isAppName(s: String, appLabel: String?) =
        appName.matches(s) || (appLabel != null && s.equals(appLabel, ignoreCase = true))

    /** [clean] that also drops fields which only contain an app name ("Mapy.cz"). */
    private fun useful(s: String?, appLabel: String?) = clean(s)?.takeUnless { isAppName(it, appLabel) }

    /**
     * Maps the resource name of a maneuver icon (e.g. "ic_turn_right", "nav_roundabout")
     * to a direction key, or `null` if the name says nothing about a direction.
     */
    fun directionKey(iconName: String?): String? {
        val n = iconName?.lowercase() ?: return null
        val left = "left" in n || "vlevo" in n || "links" in n
        val right = "right" in n || "vpravo" in n || "rechts" in n
        val side = if (left) "_left" else if (right) "_right" else ""
        return when {
            "roundabout" in n || "rotary" in n || "kruh" in n -> "roundabout"
            "uturn" in n || "u_turn" in n || "turnaround" in n -> "uturn"
            "destination" in n || "finish" in n || "arrive" in n -> "destination"
            ("ramp" in n || "exit" in n) && side.isNotEmpty() -> "exit$side"
            "sharp" in n && side.isNotEmpty() -> "sharp$side"
            ("slight" in n || "bear" in n) && side.isNotEmpty() -> "slight$side"
            ("keep" in n || "fork" in n) && side.isNotEmpty() -> "keep$side"
            side.isNotEmpty() -> side.removePrefix("_")
            "straight" in n || "continue" in n || "rovne" in n -> "straight"
            else -> null
        }
    }

    private fun bigLines(raw: RawNavFields) = raw.bigText?.lineSequence()?.mapNotNull(::clean)?.toList().orEmpty()

    private fun extras(raw: RawNavFields, appLabel: String?) = listOfNotNull(
        useful(raw.title, appLabel),
        useful(raw.text, appLabel) ?: bigLines(raw).firstOrNull()?.takeUnless { isAppName(it, appLabel) },
    ).distinct()

    /**
     * Whether title/text already contain a maneuver. If not (e.g. only "Mapy.cz" and a
     * distance), the guidance is in a custom layout and the RemoteViews texts are needed.
     */
    fun extrasHaveManeuver(raw: RawNavFields, appLabel: String?): Boolean =
        extras(raw, appLabel).any { !isDistance(it) && !isSummary(it) }

    /** Splits the notification fields into [NavInfo], or `null` if there is no maneuver at all. */
    fun parse(raw: RawNavFields, appLabel: String? = null): NavInfo? {
        // The first line of the big text is usually the same as the text; a
        // following "Then turn left" is kept as extra info, lanes etc. are dropped.
        val then = bigLines(raw).drop(1).firstOrNull { thenMarker.containsMatchIn(it) }
        val extras = extras(raw, appLabel)
        val candidates = if (extrasHaveManeuver(raw, appLabel)) {
            extras
        } else {
            (extras + raw.viewTexts.mapNotNull { useful(it, appLabel) }).distinct()
        }
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
        val summary = useful(raw.subText, appLabel)
            ?: useful(raw.infoText, appLabel)
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

        // Text without any direction ("Hlavní") is the street; the direction then only
        // exists as the notification icon.
        val icon = clean(raw.iconManeuver)
        if (icon != null && (maneuver == null || !directionWord.containsMatchIn(maneuver!!))) {
            if (street == null) street = maneuver
            maneuver = icon
        }

        if (maneuver == null && distance == null) return null
        return NavInfo(distance = distance, maneuver = maneuver, street = street, summary = summary, then = then)
    }

    /**
     * Car layout: title = distance + maneuver, artist = street, album = trip summary
     * (plus the following maneuver). Missing parts move up, so the most important
     * information is always in the title; unknown fields stay empty.
     */
    fun compose(appLabel: String, raw: RawNavFields): CarText? {
        val info = parse(raw, appLabel) ?: return null
        val title = listOfNotNull(info.distance, info.maneuver).joinToString(" · ")
        val lower = listOfNotNull(info.street, info.summary, info.then)
        return CarText(
            title = title,
            artist = lower.getOrElse(0) { "" },
            album = lower.drop(1).joinToString(" · "),
            maneuverKey = maneuverKey(listOfNotNull(info.maneuver, info.street).joinToString(" ")),
        )
    }

    /**
     * A warning (speed camera, hazard) for the car display, or `null` if the
     * notification is only the warning app's status ("Blitzer.de ist aktiv").
     */
    fun composeWarning(appLabel: String, raw: RawNavFields): CarText? {
        val lines = (
            listOf(raw.title, raw.text) +
                raw.bigText?.lines().orEmpty() +
                listOf(raw.subText, raw.infoText) +
                raw.viewTexts
            )
            .mapNotNull { useful(it, appLabel) }
            .distinct()
        fun isWarning(s: String) = warningMarker.containsMatchIn(s.replace(brandName, ""))
        val main = lines.firstOrNull(::isWarning) ?: return null
        val rest = lines.filter { it != main }
        return CarText(
            title = "⚠ $main",
            artist = rest.getOrElse(0) { "" },
            album = rest.getOrElse(1) { "" },
            maneuverKey = "warning " + maneuverKey(main),
        )
    }
}
