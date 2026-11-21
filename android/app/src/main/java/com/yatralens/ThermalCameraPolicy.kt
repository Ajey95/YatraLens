package com.yatralens

import android.os.PowerManager

/** Hysteresis prevents camera reopen loops while the phone hovers near severe heat. */
object ThermalCameraPolicy {
    const val RECOVERY_MS = 60_000L

    fun canOpen(status: Int, lastSevereAtMs: Long, nowMs: Long): Boolean {
        if (status >= PowerManager.THERMAL_STATUS_SEVERE) return false
        if (lastSevereAtMs <= 0L) return true
        return status <= PowerManager.THERMAL_STATUS_LIGHT &&
            nowMs >= lastSevereAtMs && nowMs - lastSevereAtMs >= RECOVERY_MS
    }
}
