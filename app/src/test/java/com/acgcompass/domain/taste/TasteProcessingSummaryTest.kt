package com.acgcompass.domain.taste

import org.junit.Assert.assertEquals
import org.junit.Test

class TasteProcessingSummaryTest {
    @Test
    fun otherFeatureCountExcludesPersonalWorks() {
        val summary = TasteProcessingSummary(localFeatureCount = 195, personalFeatureCount = 75)
        assertEquals(120, summary.otherFeatureCount)
    }

    @Test
    fun otherFeatureCountNeverNegative() {
        val summary = TasteProcessingSummary(localFeatureCount = 10, personalFeatureCount = 12)
        assertEquals(0, summary.otherFeatureCount)
    }
}
