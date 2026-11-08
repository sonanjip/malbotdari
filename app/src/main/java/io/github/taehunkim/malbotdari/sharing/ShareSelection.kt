package io.github.taehunkim.malbotdari.sharing

import io.github.taehunkim.malbotdari.attachments.ImportBudget

data class ShareCandidate(val name: String, val mime: String, val size: Long?, val supported: Boolean, val contentUri: Boolean = true)

object ShareSelection {
    enum class Reason { INVALID_URI, UNSUPPORTED, FILE_TOO_LARGE, TOO_MANY, TOTAL_TOO_LARGE }
    fun reason(items: List<ShareCandidate>, selected: Set<Int>, index: Int): Reason? {
        val item = items[index]
        if (!item.contentUri) return Reason.INVALID_URI
        if (!item.supported) return Reason.UNSUPPORTED
        if (item.size != null && item.size > ImportBudget.MAX_FILE_BYTES) return Reason.FILE_TOO_LARGE
        if (index in selected) return null
        if (selected.size >= ImportBudget.MAX_FILES) return Reason.TOO_MANY
        val total = selected.sumOf { items[it].size ?: 0L }
        if (item.size != null && item.size > ImportBudget.MAX_TOTAL_BYTES - total) return Reason.TOTAL_TOO_LARGE
        return null
    }
    fun initial(items: List<ShareCandidate>): Set<Int> {
        val selected = linkedSetOf<Int>()
        items.indices.forEach { if (reason(items, selected, it) == null) selected.add(it) }
        return selected
    }
}
