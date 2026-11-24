package workshop.readingcompanion

import android.content.Context
import android.net.Uri
import java.io.File

/** The book open in the reading app: NeoReader (BOOX) first, then KOReader. */
data class Book(
    val path: String,
    val title: String,
    val author: String?,
    val progress: String?,
) {
    val format: String get() = path.substringAfterLast('.', "").lowercase()
}

object BookContext {
    private val METADATA = Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata")

    // NeoReader stamps lastAccess when a lookup is made, so anything older is not the open book.
    private const val MAX_AGE_MS = 30 * 60 * 1000L

    fun current(context: Context): Book? = neoReader(context) ?: koReader()

    private fun neoReader(context: Context): Book? = try {
        context.contentResolver.query(
            METADATA,
            arrayOf("title", "name", "authors", "progress", "nativeAbsolutePath", "lastAccess"),
            "lastAccess IS NOT NULL",
            null,
            "lastAccess DESC",
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val lastAccess = c.getLong(5)
            val path = c.getString(4) ?: return@use null
            if (System.currentTimeMillis() - lastAccess > MAX_AGE_MS) return@use null
            val name = c.getString(1) ?: path.substringAfterLast('/')
            Book(
                path = path,
                title = c.getString(0)?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'),
                author = c.getString(2)?.takeIf { it.isNotBlank() },
                progress = c.getString(3)?.takeIf { it.isNotBlank() },
            )
        }
    } catch (e: Exception) {
        null
    }

    private val KOREADER_DIRS = listOf("/storage/emulated/0/koreader", "/sdcard/koreader")

    /**
     * KOReader's last opened book, from its settings file (needs all-files access). Title, author
     * and progress come from the book's sidecar, which KOReader saves on close and every few minutes.
     */
    private fun koReader(): Book? = try {
        val home = KOREADER_DIRS.map(::File).firstOrNull { File(it, "settings.reader.lua").canRead() }
        home?.let { dir ->
            val settings = File(dir, "settings.reader.lua").readText()
            val path = luaString(settings, "lastfile")?.takeIf { File(it).exists() } ?: return@let null
            val meta = sidecar(dir, path)?.readText()
            val title = meta?.let { luaString(it, "title") }?.takeIf { it.isNotBlank() }
            val author = meta?.let { luaString(it, "authors") }?.takeIf { it.isNotBlank() }
            val percent = meta?.let { Regex("\\[\"percent_finished\"\\]\\s*=\\s*([0-9.]+)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
            Book(
                path = path,
                title = title ?: path.substringAfterLast('/').substringBeforeLast('.'),
                author = author?.replace("\\n", ", "),
                // Same "page/total" shape as NeoReader so the passage finder can use it.
                progress = percent?.let { "${Math.round(it * 10000)}/10000" },
            )
        }
    } catch (e: Exception) {
        null
    }

    /** The book's metadata file: next to the book, or under koreader/docsettings. */
    private fun sidecar(home: File, path: String): File? {
        val book = File(path)
        val ext = book.extension.lowercase()
        val sdr = book.nameWithoutExtension + ".sdr"
        return listOf(
            File(book.parentFile, "$sdr/metadata.$ext.lua"),
            File(home, "docsettings${book.parent}/$sdr/metadata.$ext.lua"),
        ).firstOrNull { it.canRead() }
    }

    /** The first `["key"] = "value"` in a KOReader Lua table, unescaped. */
    private fun luaString(lua: String, key: String): String? =
        Regex("\\[\"" + Regex.escape(key) + "\"\\]\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(lua)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")?.replace("\\\\", "\\")
}
