package com.example.simplephotouploader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PilotModeChoiceTest {
    @Test fun eachPilotPhoneConfirmsIndependently() {
        for (city in listOf("Белозерск", " Кадуй ", "КИРИЛЛОВ", "Новый филиал")) {
            assertTrue(AppPrefs.needsPilotModeChoice(city, "first", null))
            assertFalse(AppPrefs.needsPilotModeChoice(city, "first", "first"))
            assertTrue(AppPrefs.needsPilotModeChoice(city, "second", null))
            assertTrue(AppPrefs.needsPilotModeChoice(city, "second", "first"))
            assertFalse(AppPrefs.needsPilotModeChoice(city, "second", "second"))
        }
        assertFalse(AppPrefs.needsPilotModeChoice("  ", "first", null))
        assertFalse(AppPrefs.needsPilotModeChoice("Кадуй", null, null))
    }
}
