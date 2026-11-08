package io.github.fuzzyfly0.navbtdisplay

/**
 * Navigation apps whose ongoing guidance notification we try to read.
 *
 * [key] is the stable id stored in the preferences; [packages] lists all known
 * package names of the same app.
 */
data class NavApp(val key: String, val label: String, val packages: List<String>)

object NavApps {
    val ALL = listOf(
        NavApp("gmaps", "Google Maps", listOf("com.google.android.apps.maps")),
        NavApp("mapy", "Mapy.com", listOf("cz.seznam.mapy")),
    )

    private val byPackage: Map<String, NavApp> =
        ALL.flatMap { app -> app.packages.map { it to app } }.toMap()

    fun forPackage(packageName: String): NavApp? = byPackage[packageName]
}
