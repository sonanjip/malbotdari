package io.github.taehunkim.malbotdari.sharing

import io.github.taehunkim.malbotdari.attachments.ImportBudget
import org.junit.Assert.*
import org.junit.Test

class ShareSelectionTest {
    @Test fun unsupportedAndOversizedItemsAreExplainedAndNotSelected() {
        val items = listOf(ShareCandidate("ok", "text/plain", 4, true), ShareCandidate("zip", "application/zip", 4, false),
            ShareCandidate("big", "image/png", ImportBudget.MAX_FILE_BYTES + 1, true))
        val selected = ShareSelection.initial(items)
        assertEquals(setOf(0), selected)
        assertEquals(ShareSelection.Reason.UNSUPPORTED, ShareSelection.reason(items, selected, 1))
        assertEquals(ShareSelection.Reason.FILE_TOO_LARGE, ShareSelection.reason(items, selected, 2))
    }

    @Test fun countAndTotalLimitsCanBeResolvedByDeselecting() {
        val items = List(11) { ShareCandidate("$it", "image/jpeg", 1, true) }
        val selected = ShareSelection.initial(items)
        assertEquals(10, selected.size)
        assertEquals(ShareSelection.Reason.TOO_MANY, ShareSelection.reason(items, selected, 10))
        assertNull(ShareSelection.reason(items, selected - 0, 10))
        val big = List(3) { ShareCandidate("$it", "audio/wav", ImportBudget.MAX_FILE_BYTES, true) }
        assertEquals(ShareSelection.Reason.TOTAL_TOO_LARGE, ShareSelection.reason(big, setOf(0, 1), 2))
    }

    @Test fun unknownSizeIsAllowedButStillConsumesAFileSlot() {
        val items = listOf(ShareCandidate("stream", "audio/wav", null, true))
        assertEquals(setOf(0), ShareSelection.initial(items))
        assertNull(ShareSelection.reason(items, emptySet(), 0))
    }
}
