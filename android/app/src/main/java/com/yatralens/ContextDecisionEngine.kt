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
            !forecast.retrievedAt.isAfter(now) &&
            !forecast.retrievedAt.isBefore(now.minus(Duration.ofHours(2))) &&
            forecast.eventEnd.isAfter(now) &&
            !forecast.eventStart.isBefore(now.minus(Duration.ofHours(1))) &&
            !forecast.eventStart.isAfter(now.plus(Duration.ofHours(24)))) {
            alerts.add(ContextAlert("forecast:${forecast.activity}:${forecast.eventStart}",
                "Check your ${forecast.activity} plan",
                "Weather information overlaps this activity. Confirm operating status with the operator.",
                forecast.sourceUrl, "CAUTION"))
        }
        return alerts
    }
}
