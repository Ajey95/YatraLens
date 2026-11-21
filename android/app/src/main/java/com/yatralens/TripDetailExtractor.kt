package com.yatralens

/** Conservative, source-linked candidates. A candidate is never a confirmed schedule. */
object TripDetailExtractor {
    data class Candidate(val predicate: String, val raw: String, val normalized: String)

    fun extract(text: String): List<Candidate> {
        val result = mutableListOf<Candidate>()
        val money = Regex("(?:₹\\s*|\\b(?:rs\\.?|rupees)\\s*)([0-9][0-9,]*)|\\b([0-9][0-9,]*)\\s*(?:rupees|rs\\.?|രൂപ)\\b", RegexOption.IGNORE_CASE)
            .find(text)
        if (money != null) {
            val amount = (money.groupValues[1].ifBlank { money.groupValues[2] })
                .replace(",", "").toIntOrNull()
            if (amount != null && amount in 1..100_000)
                result.add(Candidate("price", money.value, "INR $amount"))
        }
        val boarding = Regex("\\b(board(?:ing)?|depart(?:ure|ing|s)?|leav(?:e|ing|es))\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(text) || listOf("ബോർഡ", "പുറപ്പെട", "బోర్డ", "प्रस्थान", "बोर्डिंग")
            .any { it in text }
        if (boarding) {
            val times = Regex("(?<!\\d)(?:[01]?\\d|2[0-3]):[0-5]\\d(?:\\s*(?:am|pm))?(?!\\d)", RegexOption.IGNORE_CASE)
                .findAll(text).toList()
            if (times.size == 1) {
                val raw = times.single().value.trim()
                val parts = Regex("(\\d{1,2}):(\\d{2})\\s*(am|pm)?", RegexOption.IGNORE_CASE)
                    .matchEntire(raw)
                if (parts != null) {
                    var hour = parts.groupValues[1].toInt()
                    val minute = parts.groupValues[2].toInt()
                    val period = parts.groupValues[3].lowercase()
                    if (period.isNotBlank() && hour in 1..12) {
                        hour = hour % 12 + if (period == "pm") 12 else 0
                    }
                    if (hour in 0..23) result.add(Candidate("boarding_time", raw,
                        "%02d:%02d".format(java.util.Locale.ROOT, hour, minute)))
                }
            }
        }
        return result
    }
}
