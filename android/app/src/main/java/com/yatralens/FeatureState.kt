package com.yatralens

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Ephemeral output for user-triggered cloud features; trip memory is separate. */
object FeatureState {
    private val results = ConcurrentHashMap<String, JSONObject>()
    fun set(kind: String, value: JSONObject?) {
        if (value == null) results.remove(kind) else results[kind] = value
    }
    fun get(kind: String): JSONObject? = results[kind]
    fun clear() = results.clear()
}
