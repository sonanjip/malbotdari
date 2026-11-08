package io.github.taehunkim.malbotdari.attachments

/** A failed or unfinished attachment must be retried or removed before the turn is sent. */
object AttachmentReadiness {
    fun canSend(activeImports: Int, pendingStates: List<String>, failedImports: Boolean = false): Boolean =
        !failedImports && activeImports == 0 && pendingStates.all { it == "READY" }
}
