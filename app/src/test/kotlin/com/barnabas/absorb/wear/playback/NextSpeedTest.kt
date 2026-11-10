package com.barnabas.absorb.wear.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class NextSpeedTest {

    @Test
    fun stepsToTheNextFasterPreset() {
        assertEquals(1.25f, nextSpeed(1f, listOf(0.75f, 1f, 1.25f, 1.5f)))
    }

    @Test
    fun aSpeedBetweenPresetsGoesToTheOneAbove() {
        assertEquals(1.5f, nextSpeed(1.3f, listOf(0.75f, 1f, 1.25f, 1.5f)))
    }

    @Test
    fun wrapsToTheSlowestPastTheTop() {
        assertEquals(0.75f, nextSpeed(1.5f, listOf(0.75f, 1f, 1.25f, 1.5f)))
        assertEquals(0.75f, nextSpeed(3f, listOf(0.75f, 1f, 1.25f, 1.5f)))
    }

    @Test
    fun presetsAreTakenInOrderWhateverOrderTheyCame() {
        assertEquals(1.25f, nextSpeed(1f, listOf(2f, 1.25f, 0.75f, 1f)))
    }

    @Test
    fun noPresetsMeansThePhoneAppsDefaults() {
        assertEquals(1.25f, nextSpeed(1f, emptyList()))
        assertEquals(0.75f, nextSpeed(2.5f, emptyList()))
    }
}
