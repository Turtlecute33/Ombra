package app.ombra.vpn

import android.content.Intent
import android.os.PowerManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WakeMonitorTest {
    @Test
    fun screenOnWakesRust() {
        assertTrue(WakeMonitor.isWake(Intent.ACTION_SCREEN_ON, deviceIdle = false))
        assertFalse(WakeMonitor.isWake(Intent.ACTION_SCREEN_OFF, deviceIdle = false))
    }

    @Test
    fun onlyTheEndOfDozeWakesRust() {
        assertTrue(WakeMonitor.isWake(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED, deviceIdle = false))
        assertFalse(WakeMonitor.isWake(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED, deviceIdle = true))
    }
}
