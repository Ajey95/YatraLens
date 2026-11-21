package com.yatralens

import org.json.JSONObject

/** Ephemeral model output. No raw image is retained. */
object SceneState {
    @Volatile var current: JSONObject? = null
        private set
    fun set(value: JSONObject?) { current = value }
}
