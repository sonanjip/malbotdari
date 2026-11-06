package io.github.taehunkim.malbotdari.chat.schedule

/** How a file task's condition is matched against new files (Settings). */
enum class FileMatchMode {
    /** Nunsori's meaning + word match (embeddings), then optionally the chat model. */
    EMBEDDING,

    /**
     * OndolSaem's decision model answers yes/no for every new file with text (documents, recognized text in photos,
     * transcripts); files without text use the meaning match.
     */
    DECISION,
}

/** What happens to files the decision model is unsure about (Settings). */
enum class UnsureAction { DROP, ASK_LLM, KEEP }
