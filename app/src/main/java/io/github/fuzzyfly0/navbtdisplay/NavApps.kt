package io.github.fuzzyfly0.navbtdisplay

/**
 * Navigation apps whose ongoing guidance notification we try to read.
 *
 * [key] is the stable id stored in the preferences; [packages] lists all known
 * package names (stores, betas, F-Droid builds) of the same app.
 */
data class NavApp(val key: String, val label: String, val packages: List<String>)

object NavApps {
    val ALL = listOf(
        NavApp("gmaps", "Google Maps", listOf("com.google.android.apps.maps")),
        NavApp("waze", "Waze", listOf("com.waze")),
        NavApp("osmand", "OsmAnd", listOf("net.osmand", "net.osmand.plus", "net.osmand.dev")),
        NavApp(
            "organicmaps",
            "Organic Maps",
            listOf("app.organicmaps", "app.organicmaps.beta", "app.organicmaps.debug"),
        ),
        NavApp("comaps", "CoMaps", listOf("app.comaps", "app.comaps.fdroid", "app.comaps.debug")),
        NavApp("here", "HERE WeGo", listOf("com.here.app.maps")),
    )

    private val byPackage: Map<String, NavApp> =
        ALL.flatMap { app -> app.packages.map { it to app } }.toMap()

    fun forPackage(packageName: String): NavApp? = byPackage[packageName]
}
