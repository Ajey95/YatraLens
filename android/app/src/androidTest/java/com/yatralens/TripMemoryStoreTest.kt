package com.yatralens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TripMemoryStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun reportedAreaAndSelectedLocalReplyAppearAsSourcedTripContext() {
        val store = TripMemoryStore(context)
        val tripId = UUID.randomUUID().toString()
        try {
            store.startTrip(tripId, "CONVERSATION")
            assertEquals(true, store.saveReportedContext(tripId,
                "Traveller-reported area", "Alappuzha bus station"))
            assertEquals(false, store.saveReportedContext(tripId,
                "Traveller-reported area", "Alappuzha bus station"))
            assertNotNull(store.saveTypedLocalStatement(tripId,
                "ടിക്കറ്റ് നിരക്ക് ₹1200. മടക്കം 16:00-ന്.", "ml-IN"))
            val entries = store.recentTripContext(80).filter { it.tripId == tripId }
            assertEquals(true, entries.any { it.title == "Traveller-reported area" &&
                it.source == "Traveller reported in assistant" })
            assertEquals(true, entries.any { it.title.contains("typed reply") &&
                it.detail.contains("₹1200") && it.source == "Traveller-entered local reply" })
        } finally {
            store.writableDatabase.delete("trip", "id=?", arrayOf(tripId))
            store.close()
        }
    }

    @Test fun timelineCombinesSavedContextAcrossTripsAndExcludesFixtures() {
        val store = TripMemoryStore(context)
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val older = Instant.parse("2026-11-20T08:00:00Z")
        val newer = Instant.parse("2026-11-21T08:00:00Z")
        try {
            store.startTrip(first, "CONVERSATION", older)
            store.saveTranscript(first, "Fare is ₹1200", "en-IN", 1500,
                observedAt = older.plusSeconds(60))
            store.endTrip(first, older.plusSeconds(3600))
            store.startTrip(second, "CONTINUOUS", newer)
            store.saveDestination(second, "Jetty", 9.5, 76.35, "Traveller-entered ticket note")
            store.saveContextNote(second, "Weather check", "Rain risk uncertain",
                "Open-Meteo forecast", newer.plusSeconds(120))
            store.endTrip(second, newer.plusSeconds(3600))
            val entries = store.recentTimeline(200).filter { it.tripId == first || it.tripId == second }
            assertEquals(true, entries.any { it.tripId == first && it.detail.contains("₹1200") })
            assertEquals(true, entries.any { it.tripId == second && it.title.contains("Jetty") &&
                it.source.contains("unverified") })
            assertEquals(true, entries.any { it.tripId == second && it.title == "Weather check" &&
                it.source == "Open-Meteo forecast" && it.observedAt == newer.plusSeconds(120).toString() })
            assertEquals(true, entries.zipWithNext().all { (a, b) -> a.observedAt >= b.observedAt })
            assertEquals(false, entries.any { it.source.contains("TEST_FIXTURE") })
        } finally {
            store.writableDatabase.delete("trip", "id IN (?,?)", arrayOf(first, second))
            store.close()
        }
    }

    @Test fun bookingAndOperatorQuoteKeepBothSourcesAndRequireResolution() {
        val store = TripMemoryStore(context)
        val tripId = UUID.randomUUID().toString()
        store.startTrip(tripId, "TEST_FIXTURE")
        val time = Instant.parse("2026-11-21T10:00:00Z")
        val booking = store.ingestClaim(TripMemoryStore.ClaimDraft(
            tripId, "boat-fixture-1", "boarding_time", "15:30", "15:30",
            "TEST_BOOKING", "TEST_FIXTURE", "Fixture ticket", "Fixture booking says 15:30",
            "text", null, observedAt = time, permissionScope = "TEST_FIXTURE"
        ))
        assertNull(booking.conflictId)
        val quote = store.ingestClaim(TripMemoryStore.ClaimDraft(
            tripId, "boat-fixture-1", "boarding_time", "16:00", "16:00",
            "TEST_TRANSCRIPT", "TEST_FIXTURE", "Fixture operator turn", "Fixture operator says 16:00",
            "text", null, observedAt = time, permissionScope = "TEST_FIXTURE"
        ))
        assertNotNull(quote.conflictId)
        assertEquals(1, store.openConflictCount(tripId))
        store.resolveConflict(quote.conflictId!!, "CONFIRM_NEW", quote.claimId, "Fixture decision")
        assertEquals(0, store.openConflictCount(tripId))
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM source WHERE trip_id=?", arrayOf(tripId)).use {
            it.moveToFirst(); assertEquals(2, it.getInt(0))
        }
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM claim WHERE trip_id=?", arrayOf(tripId)).use {
            it.moveToFirst(); assertEquals(2, it.getInt(0))
        }
        store.endTrip(tripId)
        store.close()
    }

    @Test fun unsourcedClaimCannotBeSaved() {
        val store = TripMemoryStore(context)
        val tripId = UUID.randomUUID().toString()
        store.startTrip(tripId, "TEST_FIXTURE")
        assertThrows(IllegalArgumentException::class.java) {
            store.ingestClaim(TripMemoryStore.ClaimDraft(
                tripId, "boat-fixture-2", "price", "1200", "1200", "TEST",
                "TEST_FIXTURE", "No evidence", null, "text", null,
                observedAt = Instant.now(), permissionScope = "TEST_FIXTURE"
            ))
        }
        store.endTrip(tripId)
        store.close()
    }

    @Test fun separateValidityWindowsDoNotCreateFalseConflict() {
        val store = TripMemoryStore(context)
        val tripId = UUID.randomUUID().toString()
        store.startTrip(tripId, "TEST_FIXTURE")
        val observed = Instant.parse("2026-11-21T10:00:00Z")
        val first = store.ingestClaim(TripMemoryStore.ClaimDraft(
            tripId, "boat-fixture-3", "price", "1200", "1200", "TEST_QUOTE",
            "TEST_FIXTURE", "Morning fare", "Fixture morning fare 1200", "text", null,
            observedAt = observed, permissionScope = "TEST_FIXTURE",
            validFrom = observed, validUntil = observed.plusSeconds(3600)
        ))
        val second = store.ingestClaim(TripMemoryStore.ClaimDraft(
            tripId, "boat-fixture-3", "price", "1400", "1400", "TEST_QUOTE",
            "TEST_FIXTURE", "Afternoon fare", "Fixture afternoon fare 1400", "text", null,
            observedAt = observed, permissionScope = "TEST_FIXTURE",
            validFrom = observed.plusSeconds(3600), validUntil = observed.plusSeconds(7200)
        ))
        assertNull(first.conflictId)
        assertNull(second.conflictId)
        assertEquals(0, store.openConflictCount(tripId))
        store.endTrip(tripId)
        store.close()
    }

    @Test fun reviewedMediaTextKeepsProviderAndFingerprintWithoutRawMedia() {
        val store = TripMemoryStore(context)
        val tripId = UUID.randomUUID().toString()
        store.startTrip(tripId, "TEST_FIXTURE")
        val speechEvidence = store.saveTranscript(tripId, "Where is the jetty?", "en-IN", 1800)
        val ocrEvidence = store.saveOcrText(tripId, "ബോട്ട് ജെട്ടി 15:30", "fixture-sha256", "fixture-job")
        store.readableDatabase.rawQuery("""SELECT s.kind, s.provider, s.source_hash,
            e.evidence_uri FROM evidence e JOIN source s ON s.id=e.source_id WHERE e.id=?""",
            arrayOf(ocrEvidence)).use {
            assertEquals(true, it.moveToFirst())
            assertEquals("selected_image_ocr", it.getString(0))
            assertEquals("Sarvam Document AI", it.getString(1))
            assertEquals("fixture-sha256", it.getString(2))
            assertNull(it.getString(3))
        }
        store.readableDatabase.rawQuery("SELECT excerpt FROM evidence WHERE id=?", arrayOf(speechEvidence)).use {
            assertEquals(true, it.moveToFirst())
            assertEquals("Where is the jetty?", it.getString(0))
        }
        store.endTrip(tripId)
        store.close()
    }

    @Test fun reviewedTranscriptFactRetainsItsEvidenceAndCreatesConflict() {
        val store = TripMemoryStore(context)
        val tripId = UUID.randomUUID().toString()
        store.startTrip(tripId, "TEST_FIXTURE")
        val observed = Instant.parse("2026-11-21T10:00:00Z")
        val booking = store.ingestClaim(TripMemoryStore.ClaimDraft(
            tripId, "boat-fixture-4", "boarding_time", "15:30", "15:30", "TEST_BOOKING",
            "TEST_FIXTURE", "Booking", "Fixture booking says 15:30", "text", null,
            observedAt = observed, permissionScope = "TEST_FIXTURE"
        ))
        assertNull(booking.conflictId)
        val evidenceId = store.saveTranscript(tripId, "Boat operator says 16:00", "en-IN", 1500)
        val operator = store.claimFromEvidence(tripId, evidenceId, "boat-fixture-4",
            "boarding_time", "16:00", "16:00", observed)
        assertEquals(evidenceId, operator.evidenceId)
        assertNotNull(operator.conflictId)
        assertEquals(1, store.openConflictCount(tripId))
        assertThrows(IllegalArgumentException::class.java) {
            store.claimFromEvidence(UUID.randomUUID().toString(), evidenceId,
                "boat-fixture-4", "price", "1200", "1200")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.resolveConflict(operator.conflictId!!, "CONFIRM_NEW", null, null)
        }
        store.resolveConflict(operator.conflictId!!, "CONFIRM_NEW", operator.claimId, "Confirmed with operator")
        assertEquals(0, store.openConflictCount(tripId))
        store.endTrip(tripId)
        store.close()
    }

    @Test fun travellerDestinationHasLocalSourceAndCoordinates() {
        val store = TripMemoryStore(context)
        val tripId = UUID.randomUUID().toString()
        store.startTrip(tripId, "TEST_FIXTURE")
        val destination = store.saveDestination(tripId, "Houseboat jetty", 9.5, 76.35,
            "Fixture booking names Houseboat jetty")
        assertEquals("Houseboat jetty", store.latestDestination(tripId)?.placeName)
        assertEquals(9.5, store.latestDestination(tripId)!!.latitude, 0.000001)
        store.readableDatabase.rawQuery("SELECT status FROM trip_event WHERE id=?",
            arrayOf(destination.eventId)).use {
            assertEquals(true, it.moveToFirst())
            assertEquals("USER_ENTERED_UNVERIFIED", it.getString(0))
        }
        store.endTrip(tripId)
        store.close()
    }

    @Test fun deletingSpeechEvidenceRemovesDerivedClaimAndConflict() {
        val store = TripMemoryStore(context)
        val tripId = UUID.randomUUID().toString()
        store.startTrip(tripId, "TEST_FIXTURE")
        store.ingestClaim(TripMemoryStore.ClaimDraft(
            tripId, "boat-delete-fixture", "boarding_time", "15:30", "15:30", "TEST_BOOKING",
            "TEST_FIXTURE", "Booking", "Fixture booking 15:30", "text", null,
            observedAt = Instant.now(), permissionScope = "TEST_FIXTURE"))
        val speech = store.saveTranscript(tripId, "Operator says 16:00", "en-IN", 1500)
        store.claimFromEvidence(tripId, speech, "boat-delete-fixture", "boarding_time", "16:00", "16:00")
        assertEquals(1, store.openConflictCount(tripId))
        assertEquals(true, store.deleteEvidenceCascade(tripId, speech))
        assertEquals(0, store.openConflictCount(tripId))
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM claim WHERE trip_id=?",
            arrayOf(tripId)).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM source WHERE trip_id=?",
            arrayOf(tripId)).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        store.endTrip(tripId)
        store.close()
    }

    @Test fun reviewedPhraseCanBeReadAndDeletedOffline() {
        val store = OfflinePhraseStore(context)
        val id = store.saveReviewed("Where is the jetty?", "ബോട്ട് ജെട്ടി എവിടെയാണ്?", "en-IN", "ml-IN")
        assertEquals(id, store.recent().first().id)
        assertEquals(true, store.delete(id))
        store.close()
    }

    @Test fun proposedDetailDefaultsToTranscriptObservationTime() {
        val store = TripMemoryStore(context)
        val tripId = UUID.randomUUID().toString()
        val captured = Instant.parse("2026-11-21T10:00:00Z")
        store.startTrip(tripId, "TEST_FIXTURE")
        val evidenceId = store.saveTranscript(tripId, "Fare is ₹1200", "en-IN", 1800,
            observedAt = captured, owner = "LOCAL")
        val claim = store.claimFromEvidence(tripId, evidenceId, "boat-test", "price", "₹1200", "1200")
        store.readableDatabase.rawQuery("SELECT observed_at FROM claim WHERE id=?",
            arrayOf(claim.claimId)).use {
            assertEquals(true, it.moveToFirst())
            assertEquals(captured.toString(), it.getString(0))
        }
        assertEquals(evidenceId, store.recentEvidenceItems(tripId).single().id)
        store.endTrip(tripId)
        store.close()
    }

    @Test fun selectedOcrLanguageIsPreservedWithEvidence() {
        val store = TripMemoryStore(context)
        val tripId = UUID.randomUUID().toString()
        store.startTrip(tripId, "TEST_FIXTURE")
        val evidenceId = store.saveOcrText(tripId, "బస్సు సమయం 15:30", "fixture-hash", "fixture-job",
            language = "te-IN")
        store.readableDatabase.rawQuery("""SELECT s.language FROM evidence e
            JOIN source s ON s.id=e.source_id WHERE e.id=?""", arrayOf(evidenceId)).use {
            assertEquals(true, it.moveToFirst())
            assertEquals("te-IN", it.getString(0))
        }
        store.endTrip(tripId)
        store.close()
    }
}

