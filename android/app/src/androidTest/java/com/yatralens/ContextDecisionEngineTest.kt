package com.yatralens

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ContextDecisionEngineTest {
    @Test fun sourcedForecastSuggestsVerificationButUnknownOrStaleDoesNotAlert() {
        val now = Instant.parse("2026-11-21T12:00:00Z")
        val forecast = ForecastObservation("ACTION_RECOMMENDED", "https://api.open-meteo.com/example",
            now.minusSeconds(60), "boat ride", now.plusSeconds(3600), now.plusSeconds(7200))
        val situation = TripSituation(tripId = "fixture", forecast = forecast)
        val alert = ContextDecisionEngine.evaluate(situation, emptyList(), now).single()
        assertEquals("CAUTION", alert.severity)
        assertTrue(alert.message.contains("Confirm operating status"))
        assertEquals(forecast.sourceUrl, alert.sourceRef)
        assertTrue(ContextDecisionEngine.evaluate(situation.copy(forecast =
            forecast.copy(retrievedAt = now.minusSeconds(3 * 3600))), emptyList(), now).isEmpty())
        assertTrue(ContextDecisionEngine.evaluate(situation.copy(forecast =
            forecast.copy(decision = "NO_ALERT")), emptyList(), now).isEmpty())
    }
}
