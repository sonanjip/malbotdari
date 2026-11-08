package io.github.taehunkim.malbotdari.attachments.extract

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Read access to the entries of an OOXML / OpenDocument package (a zip). */
interface ZipSource : AutoCloseable {
    fun names(): List<String>
    fun read(name: String): ByteArray?
    override fun close() {}

    companion object {
        /** Entries are decompressed on demand; large media files in the package are never read. */
        fun fromFile(file: File): ZipSource = object : ZipSource {
            private val zip = ZipFile(file)
            override fun names() = zip.entries().asSequence().map { it.name }.toList()
            override fun read(name: String): ByteArray? = zip.getEntry(name)?.let { e ->
                if (e.size > MAX_ENTRY_BYTES) null else zip.getInputStream(e).use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (out.size().toLong() + n > MAX_ENTRY_BYTES) throw java.io.IOException("Zip entry too large")
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
            }
            override fun close() = zip.close()
        }

        fun fromBytes(bytes: ByteArray): ZipSource {
            val map = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                generateSequence { zin.nextEntry }.forEach { e -> if (!e.isDirectory) map[e.name] = zin.readBytes() }
            }
            return object : ZipSource {
                override fun names() = map.keys.toList()
                override fun read(name: String) = map[name]
            }
        }

        const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
    }
}
