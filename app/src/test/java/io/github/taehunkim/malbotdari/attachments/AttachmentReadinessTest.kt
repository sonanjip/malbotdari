package io.github.taehunkim.malbotdari.attachments

import org.junit.Assert.*
import org.junit.Test

class AttachmentReadinessTest {
    @Test fun sendingWaitsForCopiesAndEveryPendingAttachmentToBeReady() {
        assertFalse(AttachmentReadiness.canSend(1, emptyList()))
        assertFalse(AttachmentReadiness.canSend(0, emptyList(), failedImports = true))
        for (state in listOf("AWAITING", "QUEUED", "PROCESSING", "FAILED"))
            assertFalse(state, AttachmentReadiness.canSend(0, listOf("READY", state)))
        assertTrue(AttachmentReadiness.canSend(0, listOf("READY", "READY")))
        assertTrue(AttachmentReadiness.canSend(0, emptyList()))
    }
}
