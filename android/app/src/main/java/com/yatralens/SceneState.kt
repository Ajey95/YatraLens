package com.yatralens

import org.json.JSONObject
import java.time.Instant

/** Ephemeral model output. No raw image is retained. */
object SceneState {
    @Volatile var current: JSONObject? = null
        private set
    @Volatile var capturedAt: Instant? = null
        private set
    fun set(value: JSONObject?) {
        current = value
        capturedAt = if (value == null) null else Instant.now()
    }
}
