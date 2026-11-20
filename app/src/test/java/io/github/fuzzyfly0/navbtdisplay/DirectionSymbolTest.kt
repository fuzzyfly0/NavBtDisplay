package io.github.fuzzyfly0.navbtdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectionSymbolTest {

    @Test
    fun `direction from German wording`() {
        assertEquals("right", NavText.directionFromText("Rechts abbiegen"))
        assertEquals("left", NavText.directionFromText("Links abbiegen auf Hauptstraße"))
        assertEquals("keep_left", NavText.directionFromText("Links halten"))
        assertEquals("slight_right", NavText.directionFromText("Leicht rechts abbiegen"))
        assertEquals("sharp_left", NavText.directionFromText("Scharf links abbiegen"))
        assertEquals("exit_right", NavText.directionFromText("Rechts die Ausfahrt nehmen"))
        assertEquals("roundabout", NavText.directionFromText("Im Kreisverkehr die 2. Ausfahrt nehmen"))
        assertEquals("uturn", NavText.directionFromText("Wenden"))
        assertEquals("destination", NavText.directionFromText("Ziel erreicht"))
        assertEquals("straight", NavText.directionFromText("Fahren Sie los auf Hauptstraße"))
        assertEquals("straight", NavText.directionFromText("Weiter auf B27"))
    }

    @Test
    fun `direction from English and Czech wording`() {
        assertEquals("right", NavText.directionFromText("Turn right onto Main St"))
        assertEquals("keep_left", NavText.directionFromText("Keep left to stay on A9"))
        assertEquals("roundabout", NavText.directionFromText("At the roundabout, take the 3rd exit"))
        assertEquals("right", NavText.directionFromText("Odbočte vpravo na Hlavní"))
        assertEquals("straight", NavText.directionFromText("Pokračujte rovně"))
    }

    @Test
    fun `street names do not count as a direction`() {
        // "Linksstraße" contains "links", but not as a word.
        assertNull(NavText.directionFromText("Linksstraße"))
        assertNull(NavText.directionFromText("Zielstattstraße"))
        assertNull(NavText.directionFromText(null))
    }

    @Test
    fun `symbols per style`() {
        assertEquals("->", NavText.symbol("right", null, SymbolStyle.ASCII))
        assertEquals("→", NavText.symbol("right", null, SymbolStyle.ARROWS))
        assertEquals("O2", NavText.symbol("roundabout", 2, SymbolStyle.ASCII))
        assertEquals("↻2", NavText.symbol("roundabout", 2, SymbolStyle.ARROWS))
        assertNull(NavText.symbol("right", null, SymbolStyle.OFF))
        assertNull(NavText.symbol("unknown", null, SymbolStyle.ARROWS))
    }

    @Test
    fun `Vela notification gets a symbol in front of the title`() {
        val raw = RawNavFields(
            title = "In 272 m · Fahren Sie los auf Hauptstraße",
            text = "1 Std., 20 Min. · 95.3 km · Ankunft 11:21",
        )
        val ascii = NavText.compose("Vela Maps", raw, SymbolStyle.ASCII)!!
        assertEquals("^ 272 m · Fahren Sie los", ascii.title)
        assertEquals("Hauptstraße", ascii.artist)
        assertEquals("1 Std., 20 Min. · 95.3 km · Ankunft 11:21", ascii.album)

        assertEquals("272 m · Fahren Sie los", NavText.compose("Vela Maps", raw, SymbolStyle.OFF)!!.title)

        // The symbol must not change the key, or every style switch would look like a new maneuver.
        assertEquals(ascii.maneuverKey, NavText.compose("Vela Maps", raw, SymbolStyle.ARROWS)!!.maneuverKey)
    }

    @Test
    fun `roundabout exit number is part of the symbol`() {
        val car = NavText.compose(
            "X",
            RawNavFields(title = "200 m", text = "Im Kreisverkehr die 2. Ausfahrt nehmen"),
            SymbolStyle.ARROWS,
        )!!
        assertEquals("↻2 200 m · Im Kreisverkehr die 2. Ausfahrt nehmen", car.title)
    }

    @Test
    fun `status lines without distance and direction are no guidance`() {
        // Google Maps while connected to Android Auto, without a route.
        assertNull(NavText.compose("Google Maps", RawNavFields(title = "Mit Google Maps fahren")))
        assertNull(NavText.compose("Google Maps", RawNavFields(title = "Hauptstraße")))
        // A maneuver without distance still counts.
        assertEquals("Rechts abbiegen", NavText.compose("X", RawNavFields(title = "Rechts abbiegen"))!!.title)
    }

    @Test
    fun `icon direction is used when the text has none`() {
        val car = NavText.compose(
            "Mapy.com",
            RawNavFields(title = "300 m", text = "Hlavní", iconManeuver = "Rechts", iconDirection = "right"),
            SymbolStyle.ASCII,
        )!!
        assertEquals("-> 300 m · Rechts", car.title)
        assertEquals("Hlavní", car.artist)
    }
}
