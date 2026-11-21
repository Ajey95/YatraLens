package com.yatralens

import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.util.Locale

data class SceneAnswer(val status: String, val text: String, val cues: List<String>,
                       val model: String = "Mirage 4.0.0")

/** Answers only from selected-capture cues; never identifies an exact boarding gate from objects alone. */
object SceneAnswerEngine {
    fun answer(scene: JSONObject?, capturedAt: Instant?, question: String,
               now: Instant = Instant.now()): SceneAnswer {
        if (scene == null || capturedAt == null || Duration.between(capturedAt, now).seconds !in 0..120)
            return SceneAnswer("STALE_OR_MISSING", "Take a new scene photo first.", emptyList())
        if (scene.optString("status").endsWith("FAILED") || scene.optString("status") == "UNPARSEABLE_MODEL_OUTPUT")
            return SceneAnswer("MODEL_UNAVAILABLE", "I could not analyze this scene. Try another photo.", emptyList())
        val objects = scene.optJSONArray("objects")
        val visible = (0 until (objects?.length() ?: 0)).mapNotNull { index ->
            val item = objects?.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name").trim()
            val score = item.optDouble("score", 0.0)
            if (name.isBlank() || score < 0.3) null else name to score
        }.take(5)
        val texts = scene.optJSONArray("text")
        val signCues = (0 until (texts?.length() ?: 0)).mapNotNull { index ->
            val item = texts?.optJSONObject(index) ?: return@mapNotNull null
            val value = item.optString("text", item.optString("value")).trim()
            value.takeIf { it.isNotBlank() }
        }
        val q = question.lowercase(Locale.ROOT)
        val clues = signCues.filter { it.contains("ticket", ignoreCase = true) ||
            it.contains("boarding", ignoreCase = true) }
        if (q.contains("ticket") || q.contains("board") || q.contains("queue")) {
            return if (clues.isNotEmpty()) SceneAnswer("VISUAL_CUE_NEEDS_CONFIRMATION",
                "A sign may read ‘${clues.first().take(80)}’. Confirm the ticket or boarding point with staff.",
                listOf("scene text: ${clues.first().take(80)}"))
            else SceneAnswer("UNKNOWN", "This photo does not verify the ticket counter or boarding queue. Ask staff.",
                visible.map { "possible object: ${it.first}" })
        }
        if (q.contains("see") || q.contains("describe") || q.contains("what")) {
            return if (visible.isNotEmpty()) SceneAnswer("TENTATIVE_DESCRIPTION",
                "The on-device model may see ${visible.joinToString { it.first }}. Check the scene directly before acting.",
                visible.map { "${it.first} (${(it.second * 100).toInt()}%)" })
            else SceneAnswer("UNKNOWN", "I cannot identify reliable objects in this photo.", emptyList())
        }
        return SceneAnswer("UNKNOWN", "I cannot answer that from this photo alone. Ask a local person.",
            visible.map { "possible object: ${it.first}" })
    }
}
