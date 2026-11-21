package com.yatralens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripContextExtractorTest {
    @Test fun remembersReportedAreaAndDestinationWithoutSavingEveryQuestion() {
        val reports = TripContextExtractor.reportedFacts(
            "I'm at Alappuzha bus station, read this board. I'm going to Marari Beach by bus.")
        assertEquals("Alappuzha bus station", reports[0].detail)
        assertEquals("Traveller-reported area", reports[0].title)
        assertEquals("Marari Beach by bus", reports[1].detail)
        assertTrue(TripContextExtractor.reportedFacts("What is the weather now?").isEmpty())
    }

    @Test fun onlyMaterialTypedLocalRepliesBecomeTripEvidence() {
        assertTrue(TripContextExtractor.materialLocalReply("ടിക്കറ്റ് നിരക്ക് ₹1200. മടക്കം 16:00-ന്."))
        assertTrue(TripContextExtractor.materialLocalReply("ലൈഫ് ജാക്കറ്റ് നൽകും."))
        assertFalse(TripContextExtractor.materialLocalReply("നമസ്കാരം."))
    }

    @Test fun remembersTengglishTripReports() {
        val area = TripContextExtractor.reportedFacts(
            "Nenu Alappuzha bus stand daggara unna. Ee bus ekkadiki velthundi?")
        assertEquals("Traveller-reported area", area.single().title)
        assertEquals("Alappuzha bus stand", area.single().detail)
        val destination = TripContextExtractor.reportedFacts("Nenu Marari Beach ki vellali")
        assertEquals("Traveller-reported destination", destination.single().title)
        assertEquals("Marari Beach", destination.single().detail)
    }
}
