package com.yatralens

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject

/** On-device ARM64 Mirage adapter; only user-selected captures reach this model. */
object MirageNative {
    private var loaded = false
    private external fun describeRgb(model: ByteArray, rgb: ByteArray, width: Int, height: Int): String

    @Synchronized fun describe(context: Context, input: Bitmap): JSONObject {
        if (!loaded) System.loadLibrary("yatralens_vision")
        val maxSide = 512
        val factor = minOf(1.0, maxSide.toDouble() / maxOf(input.width, input.height))
        val width = maxOf(1, (input.width * factor).toInt())
        val height = maxOf(1, (input.height * factor).toInt())
        val bitmap = Bitmap.createScaledBitmap(input, width, height, true)
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        if (bitmap !== input) bitmap.recycle()
        val rgb = ByteArray(width * height * 3)
        for (index in argb.indices) {
            val pixel = argb[index]
            rgb[index * 3] = (pixel shr 16).toByte()
            rgb[index * 3 + 1] = (pixel shr 8).toByte()
            rgb[index * 3 + 2] = pixel.toByte()
        }
        val model = if (loaded) ByteArray(0) else context.assets.open("mirage.cact").use { it.readBytes() }
        val raw = try { describeRgb(model, rgb, width, height) }
            finally { rgb.fill(0); model.fill(0); argb.fill(0) }
        val json = try { JSONObject(raw) } catch (_: Exception) {
            JSONObject().put("status", "UNPARSEABLE_MODEL_OUTPUT").put("raw", raw.take(2000))
        }
        if (json.optString("status") != "MODEL_LOAD_FAILED") loaded = true
        return json
    }
}
