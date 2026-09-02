package eu.decentnewsroom.bookshelf.data.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTipTest {
    @Test
    fun `tip preference keys are stable and unique`() {
        val keys = OnboardingTip.entries.map(OnboardingTip::preferenceKey)

        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all(String::isNotBlank))
    }
}
