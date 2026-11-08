package io.github.taehunkim.malbotdari.attachments

import java.util.Locale

/** Providers can return an extensionless name while still supplying a precise document MIME. */
object AttachmentFormat {
    private val officeMimes = mapOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.oasis.opendocument.text" to "odt",
        "application/vnd.oasis.opendocument.presentation" to "odp",
        "application/vnd.oasis.opendocument.spreadsheet" to "ods",
    )
    private val extensions = setOf("txt", "md", "csv", "tsv", "pdf", "docx", "pptx", "xlsx", "odt", "odp", "ods", "mp3", "m4a", "wav", "ogg", "opus", "flac", "aac")
    fun extension(name: String, mime: String): String =
        officeMimes[mime.substringBefore(';').trim().lowercase(Locale.ROOT)] ?: name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    fun supported(name: String, mime: String): Boolean {
        val type = mime.substringBefore(';').trim().lowercase(Locale.ROOT)
        return type.startsWith("image/") || type.startsWith("audio/") || type.startsWith("text/") || type == "application/pdf" || extension(name, type) in extensions
    }
}
