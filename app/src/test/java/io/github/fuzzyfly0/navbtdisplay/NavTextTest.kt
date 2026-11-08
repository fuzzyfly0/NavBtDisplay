package io.github.fuzzyfly0.navbtdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavTextTest {

    @Test
    fun `title and text are joined, sub text becomes artist`() {
        val car = NavText.compose(
            "Google Maps",
            RawNavFields(title = "300 m", text = "Rechts abbiegen auf Hauptstraße", subText = "12 Min. · 5,3 km"),
        )!!
        assertEquals("300 m · Rechts abbiegen auf Hauptstraße", car.title)
        assertEquals("12 Min. · 5,3 km", car.artist)
        assertEquals("Google Maps", car.album)
    }

    @Test
    fun `big text is preferred over text and duplicates are dropped`() {
        val car = NavText.compose(
            "OsmAnd",
            RawNavFields(title = "Turn left", text = "Turn left", bigText = "Turn left\nonto Main St"),
        )!!
        assertEquals("Turn left onto Main St", car.title)
        assertEquals("OsmAnd", car.artist)

        val same = NavText.compose("X", RawNavFields(title = "Turn left onto Main St", text = "Main St"))!!
        assertEquals("Turn left onto Main St", same.title)
    }

    @Test
    fun `falls back to remote view texts`() {
        val car = NavText.compose(
            "Waze",
            RawNavFields(viewTexts = listOf("200 m", "Exit 12", "ETA 18:34", " ")),
        )!!
        assertEquals("200 m · Exit 12", car.title)
        assertEquals("ETA 18:34", car.artist)
    }

    @Test
    fun `nothing usable gives null`() {
        assertNull(NavText.compose("X", RawNavFields(title = " ‎ ", viewTexts = listOf(""))))
    }

    @Test
    fun `maneuver key ignores counting down distance`() {
        assertEquals(
            NavText.maneuverKey("In 300 m rechts abbiegen"),
            NavText.maneuverKey("In 50 m rechts abbiegen"),
        )
        assertEquals(
            NavText.maneuverKey("1,2 km · Links halten"),
            NavText.maneuverKey("800 m · Links halten"),
        )
        assert(NavText.maneuverKey("Rechts abbiegen") != NavText.maneuverKey("Links abbiegen"))
    }

    @Test
    fun `clean strips bidi marks and newlines`() {
        assertEquals("a b", NavText.clean("⁨a⁩\n  b"))
    }
}
