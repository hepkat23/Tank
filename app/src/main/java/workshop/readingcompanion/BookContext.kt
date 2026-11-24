package workshop.readingcompanion

import android.content.Context
import android.net.Uri

/** The book NeoReader most recently had open, from its (exported) library database. */
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

    fun current(context: Context): Book? = try {
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
}
