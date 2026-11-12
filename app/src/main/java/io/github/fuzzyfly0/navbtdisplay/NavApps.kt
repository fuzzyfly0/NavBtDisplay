package io.github.fuzzyfly0.navbtdisplay

/**
 * Apps whose notifications we try to read.
 *
 * [key] is the stable id stored in the preferences; [packages] lists all known
 * package names of the same app.
 */
data class NavApp(
    val key: String,
    val label: String,
    val packages: List<String>,
    val kind: Kind = Kind.NAVIGATION,
) {
    enum class Kind {
        /** Ongoing turn-by-turn guidance notification. */
        NAVIGATION,

        /** Short-lived warnings (speed cameras, hazards), shown on top of the guidance for a few seconds. */
        WARNING,
    }
}

object NavApps {
    val ALL = listOf(
        NavApp("gmaps", "Google Maps", listOf("com.google.android.apps.maps")),
        NavApp("mapy", "Mapy.com", listOf("cz.seznam.mapy")),
        NavApp(
            "blitzer",
            "Blitzer.de",
            listOf("de.blitzer", "de.blitzer.plus", "net.graphmasters.blitzerdex"),
            NavApp.Kind.WARNING,
        ),
    )

    private val byPackage: Map<String, NavApp> =
        ALL.flatMap { app -> app.packages.map { it to app } }.toMap()

    fun forPackage(packageName: String): NavApp? = byPackage[packageName]
}
