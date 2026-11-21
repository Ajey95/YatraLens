package com.yatralens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripDetailExtractorTest {
    @Test fun sourceLinkedFareAndBoardingTime() {
        val candidates = TripDetailExtractor.extract("The boat fare is ₹1,200. Boarding is at 16:00.")
        assertEquals("INR 1200", candidates.single { it.predicate == "price" }.normalized)
        assertEquals("16:00", candidates.single { it.predicate == "boarding_time" }.normalized)
    }

    @Test fun ignoresTimetableWithSeveralTimes() {
        assertTrue(TripDetailExtractor.extract("Departure 15:30 and 16:00 on Friday").isEmpty())
    }

    @Test fun twelveHourTimeNormalizes() {
        assertEquals("15:30", TripDetailExtractor.extract("Boarding at 3:30 pm")
            .single().normalized)
    }

    @Test fun malayalamNumberAndBoardingCue() {
        val candidates = TripDetailExtractor.extract("ചാർജ് 1200 രൂപ. ബോർഡിംഗ് 16:00")
        assertEquals("INR 1200", candidates.single { it.predicate == "price" }.normalized)
        assertEquals("16:00", candidates.single { it.predicate == "boarding_time" }.normalized)
    }
}
