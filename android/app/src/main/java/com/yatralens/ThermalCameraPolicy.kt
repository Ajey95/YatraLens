package com.yatralens

import android.os.PowerManager

/** Keep a reduced camera stream at severe heat; close it at critical heat. */
object ThermalCameraPolicy {
    const val RECOVERY_MS = 60_000L

    fun canOpen(status: Int, lastCriticalAtMs: Long, nowMs: Long): Boolean {
        if (status >= PowerManager.THERMAL_STATUS_CRITICAL) return false
        if (lastCriticalAtMs <= 0L) return true
        return status <= PowerManager.THERMAL_STATUS_MODERATE &&
            nowMs >= lastCriticalAtMs && nowMs - lastCriticalAtMs >= RECOVERY_MS
    }
}
