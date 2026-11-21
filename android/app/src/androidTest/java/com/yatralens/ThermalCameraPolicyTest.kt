package com.yatralens

import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThermalCameraPolicyTest {
    @Test fun severeHeatRequiresLightStatusAndCooldownBeforeRestart() {
        val severeAt = 100_000L
        assertFalse(ThermalCameraPolicy.canOpen(PowerManager.THERMAL_STATUS_SEVERE, 0, severeAt))
        assertFalse(ThermalCameraPolicy.canOpen(PowerManager.THERMAL_STATUS_MODERATE,
            severeAt, severeAt + 120_000))
        assertFalse(ThermalCameraPolicy.canOpen(PowerManager.THERMAL_STATUS_LIGHT,
            severeAt, severeAt + 59_999))
        assertTrue(ThermalCameraPolicy.canOpen(PowerManager.THERMAL_STATUS_LIGHT,
            severeAt, severeAt + 60_000))
        assertTrue(ThermalCameraPolicy.canOpen(PowerManager.THERMAL_STATUS_MODERATE,
            0, severeAt))
    }
}
