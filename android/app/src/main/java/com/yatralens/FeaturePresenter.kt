package com.yatralens

import org.json.JSONObject

/** Short, source-aware summaries for the selected feature checks. */
object FeaturePresenter {
    fun describe(kind: String, result: JSONObject): String {
        val status = result.optString("status", "Ready")
        val reason = result.optString("reason")
        val source = result.optString("source_url", result.optString("official_depot_enquiry"))
        val body = when (kind) {
            "typed translation", "scene translation", "transport question", "assistant response" -> buildString {
                append("Translation ready for review.")
                val flags = result.optJSONArray("review_flags")
                if (flags != null && flags.length() > 0)
                    append(" Check numbers, names and times before speaking.")
            }
            "ocr interpretation" -> buildString {
                append("Translation needs review: ")
                append(result.optString("translated_text", "Unavailable"))
                val cues = result.optJSONObject("cues")
                val times = cues?.optJSONArray("times")
                val dates = cues?.optJSONArray("dates")
                if ((times?.length() ?: 0) > 0 || (dates?.length() ?: 0) > 0)
                    append("\nPossible times: ${times?.length() ?: 0}; dates: ${dates?.length() ?: 0}. Check the photo.")
            }
            "agreement" -> buildString {
                append("Unconfirmed quote. ")
                val proposals = result.optJSONArray("proposals")
                if (proposals == null || proposals.length() == 0) append("No price or time was extracted.")
                else for (i in 0 until proposals.length()) {
                    val item = proposals.optJSONObject(i) ?: continue
                    append("\n${item.optString("kind")}: ${item.optString("raw_excerpt")} (review against transcript)")
                }
            }
            "destination" -> buildString {
                append(status.replace('_', ' '))
                if (!result.isNull("distance_m")) append(" • about ${result.optInt("distance_m")} m from saved point")
                if (!result.isNull("bearing_degrees")) append(" • bearing ${result.optInt("bearing_degrees")}°")
                append(". Confirm the place sign; this is not a walking route.")
            }
            "culture" -> if (status == "UNKNOWN") reason else buildString {
                append("Possible match: ${result.optString("name")}. Confirm its name on a sign.")
                val facts = result.optJSONArray("facts")
                if (facts != null) for (i in 0 until facts.length()) append("\n• ${facts.optString(i)}")
                append("\nOpening and visitor rules: unverified.")
            }
            "discovery" -> buildString {
                val places = result.optJSONArray("results")
                if (places == null || places.length() == 0) append(reason.ifBlank { "No sourced match nearby." })
                else for (i in 0 until places.length()) {
                    val place = places.optJSONObject(i) ?: continue
                    append("${place.optString("name")} • ${place.optString("area")}")
                    append("\nAccess, seller and map pin are unverified.")
                    append("\nSource: ${place.optString("source_url")}\n")
                }
            }
            "transport" -> "${status.replace('_', ' ')}. ${reason.ifBlank { "Ask the conductor before boarding." }}"
            else -> "$status. $reason"
        }
        return buildString {
            append(body)
            if (kind == "culture" && status != "UNKNOWN") append("\nSource: ${result.optString("source_url")}")
            else if (source.isNotBlank()) append("\nSource: $source")
        }.trim()
    }
}
