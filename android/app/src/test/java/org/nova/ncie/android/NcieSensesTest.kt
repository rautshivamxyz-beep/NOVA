package org.nova.ncie.android

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.86: the device-senses reply. The Android reads cannot run on the JVM,
 * so the part that can be wrong in a way the user sees - the wording, and the
 * honesty rules - is what these pin.
 */
class NcieSensesTest {

    private fun state(
        pct: Int = 80,
        charging: Boolean = false,
        online: Boolean = true,
        net: String = "wi-fi",
        screen: Boolean = true,
        free: Long = 8000L
    ) = NcieSenses.State(pct, charging, online, net, screen, free)

    @Test
    fun aHealthyPhoneReadsPlainly() {
        val s = NcieSenses.format(state())
        assertTrue(s.contains("battery: 80%"))
        assertTrue(s.contains("network: wi-fi"))
        assertTrue(s.contains("screen: on"))
        assertTrue(s.contains("storage free: 8000 MB"))
        assertTrue(!s.contains("charge before"))
    }

    @Test
    fun aLowBatteryIsCalledLowAndComesWithOneLineOfAdvice() {
        val s = NcieSenses.format(state(pct = 12))
        assertTrue(s.contains("12% (low)"))
        assertTrue(s.contains("Battery is low"))
    }

    @Test
    fun chargingIsNamed() {
        val s = NcieSenses.format(state(pct = 12, charging = true))
        assertTrue(s.contains("12% (charging)"))
        assertTrue(!s.contains("Battery is low"))
    }

    @Test
    fun anUnreadableBatteryIsNotZeroPercent() {
        val s = NcieSenses.format(state(pct = -1))
        assertTrue(s.contains("not readable"))
        assertTrue(!s.contains("0%"))
        assertTrue(!s.contains("-1%"))
    }

    @Test
    fun offlineSaysSoWithoutAlarm() {
        val s = NcieSenses.format(state(online = false, net = "no connection"))
        assertTrue(s.contains("network: no connection"))
        assertTrue(s.contains("still works offline"))
    }

    @Test
    fun nearlyFullStorageIsCalledOut() {
        val s = NcieSenses.format(state(free = 120L))
        assertTrue(s.contains("storage free: 120 MB"))
        assertTrue(s.contains("nearly full"))
    }
}
