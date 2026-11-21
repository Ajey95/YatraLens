package com.yatralens

/** A visible, reviewed turn. The owner is selected by the user, never guessed from audio. */
data class ConversationTurn(
    val id: Long,
    val owner: String,
    val original: String,
    val translated: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val status: String,
    val reviewFlags: List<String> = emptyList()
)

object ConversationState {
    @Volatile var current: ConversationTurn? = null
        private set

    fun set(turn: ConversationTurn?) { current = turn }
}
