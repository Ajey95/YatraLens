package com.yatralens

import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThermalCameraPolicyTest {
    @Test fun severeHeatKeepsCameraButCriticalRequiresCooldown() {
        val criticalAt = 100_000L
        assertTrue(ThermalCameraPolicy.canOpen(PowerManager.THERMAL_STATUS_SEVERE, 0, criticalAt))
        assertFalse(ThermalCameraPolicy.canOpen(PowerManager.THERMAL_STATUS_CRITICAL, 0, criticalAt))
        assertFalse(ThermalCameraPolicy.canOpen(PowerManager.THERMAL_STATUS_SEVERE,
            criticalAt, criticalAt + 120_000))
        assertFalse(ThermalCameraPolicy.canOpen(PowerManager.THERMAL_STATUS_MODERATE,
            criticalAt, criticalAt + 59_999))
        assertTrue(ThermalCameraPolicy.canOpen(PowerManager.THERMAL_STATUS_MODERATE,
            criticalAt, criticalAt + 60_000))
    }
}
