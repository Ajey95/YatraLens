package com.yatralens

/** Links a reviewed current turn to the exact saved local evidence row. */
object ReviewedEvidenceState {
    @Volatile var turnId: Long? = null
        private set
    @Volatile var evidenceId: String? = null
        private set
    fun set(turn: Long?, evidence: String?) {
        turnId = turn
        evidenceId = evidence
    }
}
