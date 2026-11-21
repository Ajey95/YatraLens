package com.yatralens

import java.time.Duration
import java.time.Instant

data class ContextAlert(val key: String, val title: String, val message: String,
                        val sourceRef: String, val severity: String)

/** Deterministic, source-gated trip alerts. A forecast never implies cancellation. */
object ContextDecisionEngine {
    fun evaluate(situation: TripSituation, conflicts: List<TripMemoryStore.ConflictCard>,
                 now: Instant = Instant.now()): List<ContextAlert> {
        if (situation.tripId == null) return emptyList()
        val alerts = mutableListOf<ContextAlert>()
        for (conflict in conflicts) {
            if (conflict.claims.size < 2) continue
            alerts.add(ContextAlert("conflict:${conflict.conflictId}", "Trip detail needs review",
                "Two saved sources disagree about ${conflict.claims.first().predicate.replace('_', ' ')}. Review both before relying on a reminder.",
                conflict.conflictId, "REVIEW"))
        }
        val forecast = situation.forecast
        if (forecast != null && forecast.decision == "ACTION_RECOMMENDED" &&
            forecast.sourceUrl.startsWith("https://") &&
            Duration.between(forecast.retrievedAt, now).toMinutes() in 0..120 &&
            forecast.eventEnd.isAfter(now) &&
            Duration.between(now, forecast.eventStart).toHours() in -1..24) {
            alerts.add(ContextAlert("forecast:${forecast.activity}:${forecast.eventStart}",
                "Check your ${forecast.activity} plan",
                "The forecast overlaps this activity. Confirm operating status with the operator.",
                forecast.sourceUrl, "CAUTION"))
        }
        return alerts
    }
}
