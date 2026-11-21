package com.yatralens

/** Save traveller-reported trip facts, not every task or sensor event. */
object TripContextExtractor {
    data class Report(val title: String, val detail: String)

    private val location = Regex(
        "\\b(?:i am|i'm|we are|we're)\\s+(?:at|in|near)\\s+([^,.!?]+)",
        RegexOption.IGNORE_CASE)
    private val destination = Regex(
        "\\b(?:i am|i'm|we are|we're)\\s+(?:going to|travelling to|traveling to|visiting)\\s+([^,.!?]+)",
        RegexOption.IGNORE_CASE)
    private val plan = Regex(
        "\\b(?:i|we)\\s+(?:plan to|will|booked)\\s+([^,.!?]+)",
        RegexOption.IGNORE_CASE)
    private val boundary = Regex("\\b(?:and|read|tell|show|check|can|because|then)\\b",
        RegexOption.IGNORE_CASE)
    private val tengglishArea = Regex(
        "\\b(?:nenu|memu|manam)\\s+(.{3,100}?)\\s+(?:daggara|deggara|lo)\\s+(?:unna|unnam|unnanu)\\b",
        RegexOption.IGNORE_CASE)
    private val tengglishDestination = Regex(
        "\\b(?:nenu|memu|manam)\\s+(.{3,100}?)\\s+ki\\s+" +
            "(?:veltunna|velthunna|velta|veltham|vellali|velali)\\b",
        RegexOption.IGNORE_CASE)

    fun reportedFacts(request: String): List<Report> = buildList {
        for ((pattern, title) in listOf(location to "Traveller-reported area",
                destination to "Traveller-reported destination", plan to "Traveller trip plan")) {
            val value = pattern.find(request)?.groupValues?.get(1)
                ?.split(boundary, limit = 2)?.firstOrNull()?.trim().orEmpty()
            if (value.length in 3..100 && !value.equals("here", true) &&
                !value.equals("there", true) && !value.equals("this", true))
                add(Report(title, value))
        }
        for ((pattern, title) in listOf(tengglishArea to "Traveller-reported area",
                tengglishDestination to "Traveller-reported destination")) {
            val value = pattern.find(request)?.groupValues?.get(1)?.trim().orEmpty()
            if (value.length in 3..100 && value !in listOf("ikkada", "akkada"))
                add(Report(title, value))
        }
    }

    fun materialLocalReply(text: String): Boolean = Regex(
        "₹|\\b(?:rs|rupees|fare|charge|ticket|boarding|return|life\\s*jacket|promis|[0-9]{1,2}:[0-9]{2}|[0-9]{3,})\\b|രൂപ|ചാർജ്|ടിക്കറ്റ്|ലൈഫ്|ബോർഡിംഗ്|മടക്കം",
        RegexOption.IGNORE_CASE).containsMatchIn(text)
}
