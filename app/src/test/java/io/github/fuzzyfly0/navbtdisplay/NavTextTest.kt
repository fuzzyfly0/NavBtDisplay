package io.github.fuzzyfly0.navbtdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The notification layouts below are assumptions, not recorded data;
 * replace them with real samples from the debug log once available.
 */
class NavTextTest {

    @Test
    fun `distance title, maneuver text and summary sub text`() {
        val car = NavText.compose(
            "Google Maps",
            RawNavFields(
                title = "300 m",
                text = "Rechts abbiegen auf Hauptstraße",
                subText = "12 Min. · 5,3 km · Ankunft 18:34",
            ),
        )!!
        assertEquals("300 m · Rechts abbiegen", car.title)
        assertEquals("Hauptstraße", car.artist)
        assertEquals("12 Min. · 5,3 km · Ankunft 18:34", car.album)
    }

    @Test
    fun `distance prefix inside the maneuver is split off`() {
        val info = NavText.parse(RawNavFields(title = "In 1,2 km Turn right onto Main St"))!!
        assertEquals("1,2 km", info.distance)
        assertEquals("Turn right", info.maneuver)
        assertEquals("Main St", info.street)
    }

    @Test
    fun `distance suffix is split off and not taken as street`() {
        val info = NavText.parse(RawNavFields(title = "Turn left onto Elm St in 300 m"))!!
        assertEquals("300 m", info.distance)
        assertEquals("Turn left", info.maneuver)
        assertEquals("Elm St", info.street)
    }

    @Test
    fun `direction keeps its word`() {
        val info = NavText.parse(RawNavFields(title = "800 m", text = "Ausfahrt 12 Richtung München"))!!
        assertEquals("Ausfahrt 12", info.maneuver)
        assertEquals("Richtung München", info.street)
    }

    @Test
    fun `czech wording from Mapy com`() {
        val info = NavText.parse(RawNavFields(title = "Za 200 m", text = "Odbočte vpravo na Hlavní"))!!
        assertEquals("200 m", info.distance)
        assertEquals("Odbočte vpravo", info.maneuver)
        assertEquals("Hlavní", info.street)
    }

    @Test
    fun `to stay on is not cut at to`() {
        val info = NavText.parse(RawNavFields(title = "Keep left to stay on A9"))!!
        assertEquals("Keep left to stay", info.maneuver)
        assertEquals("A9", info.street)
    }

    @Test
    fun `without street the summary moves to artist`() {
        val car = NavText.compose(
            "Mapy.com",
            RawNavFields(title = "500 m", text = "Turn left", subText = "ETA 18:34"),
        )!!
        assertEquals("500 m · Turn left", car.title)
        assertEquals("ETA 18:34", car.artist)
        assertEquals("Mapy.com", car.album)
    }

    @Test
    fun `summary found among remote view texts`() {
        val car = NavText.compose(
            "Google Maps",
            RawNavFields(viewTexts = listOf("200 m", "Links abbiegen auf Ringstraße", "18:34 · 9 km", " ")),
        )!!
        assertEquals("200 m · Links abbiegen", car.title)
        assertEquals("Ringstraße", car.artist)
        assertEquals("18:34 · 9 km", car.album)
    }

    @Test
    fun `only the first line of big text is used`() {
        val info = NavText.parse(RawNavFields(title = "100 m", bigText = "Turn left\nThen turn right"))!!
        assertEquals("Turn left", info.maneuver)
    }

    @Test
    fun `nothing usable gives null`() {
        assertNull(NavText.compose("X", RawNavFields(title = " ‎ ", viewTexts = listOf(""))))
        assertNull(NavText.compose("X", RawNavFields(subText = "18:34")))
    }

    @Test
    fun `maneuver key ignores counting down distance`() {
        val a = NavText.compose("X", RawNavFields(title = "300 m", text = "Rechts abbiegen auf A"))!!
        val b = NavText.compose("X", RawNavFields(title = "50 m", text = "Rechts abbiegen auf A"))!!
        val c = NavText.compose("X", RawNavFields(title = "50 m", text = "Links abbiegen auf A"))!!
        assertEquals(a.maneuverKey, b.maneuverKey)
        assertNotEquals(a.maneuverKey, c.maneuverKey)
    }

    @Test
    fun `clean strips bidi marks and newlines`() {
        assertEquals("a b", NavText.clean("⁨a⁩\n  b"))
    }
}
