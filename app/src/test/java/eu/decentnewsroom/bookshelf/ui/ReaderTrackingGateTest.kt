package eu.decentnewsroom.bookshelf.ui

import eu.decentnewsroom.bookshelf.ui.reader.ReaderTrackingGate
import eu.decentnewsroom.bookshelf.ui.reader.localReadingCycleTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTrackingGateTest {
    @Test
    fun rereadCycleStartsAfterLogicalCompletionWithoutOverflow() {
        assertEquals(13_000L, localReadingCycleTime(10_500L, 12L))
        assertEquals(20_000L, localReadingCycleTime(20_000L, 12L))
        assertEquals(Long.MAX_VALUE, localReadingCycleTime(10L, Long.MAX_VALUE))
    }

    @Test
    fun pixelFlushAndFooterDoNotReplayAnObservedSection() {
        val gate = ReaderTrackingGate()
        val ticket = gate.sectionChanged("reader", 4)!!
        assertTrue(gate.accepts(ticket))
        assertNull(gate.sectionChanged("reader", 4))
        assertNull(gate.sectionChanged("reader", null))
        assertNull(gate.sectionChanged("reader", 4))
    }

    @Test
    fun resetRejectsQueuedAdvanceAndWaitsForNewChapterTransition() {
        val gate = ReaderTrackingGate()
        val old = gate.sectionChanged("reader", 4)!!
        gate.invalidatePendingAdvances()
        assertFalse(gate.accepts(old))
        assertNull(gate.sectionChanged("reader", 4))
        val next = gate.sectionChanged("reader", 3)!!
        assertTrue(gate.accepts(next))
    }

    @Test
    fun newReaderHasItsOwnInitialTransitionAndAccountChangeInvalidatesWork() {
        val gate = ReaderTrackingGate()
        val old = gate.sectionChanged("first", 2)!!
        gate.invalidatePendingAdvances()
        assertFalse(gate.accepts(old))
        assertNotNull(gate.sectionChanged("second", 2))
    }
}
