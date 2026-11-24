package workshop.readingcompanion

import android.text.Html
import java.io.File
import java.util.zip.ZipFile

/** Where a selection sits in the book: the chapter, the surrounding text, and the selection widened to whole words. */
data class Passage(val chapter: String?, val text: String, val selection: String)

object PassageFinder {
    private const val RADIUS = 600

    /**
     * Finds [selection] in the book. A phrase can appear many times, so every match is collected
     * in reading order and the one closest to the reader's progress wins.
     */
    fun find(book: Book, selection: String): Passage? {
        if (book.format != "epub") return null
        val file = File(book.path)
        if (!file.canRead()) return null
        val needle = normalize(selection)
        if (needle.length < 2) return null
        return try {
            ZipFile(file).use { zip ->
                val names = spine(zip) ?: zip.entries().asSequence().map { it.name }
                    .filter { it.lowercase().let { n -> n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm") } }
                    .toList()
                class Hit(val pos: Long, val html: String, val text: String, val at: Int)
                val hits = mutableListOf<Hit>()
                var offset = 0L
                for (name in names) {
                    val entry = zip.getEntry(name) ?: continue
                    val html = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    val text = plain(html)
                    var at = text.indexOf(needle, ignoreCase = true)
                    while (at >= 0) {
                        hits += Hit(offset + at, html, text, at)
                        at = text.indexOf(needle, at + 1, ignoreCase = true)
                    }
                    offset += text.length
                }
                val target = progress(book)?.let { (it * offset).toLong() }
                val best = if (target == null) hits.firstOrNull() else hits.minByOrNull { kotlin.math.abs(it.pos - target) }
                best?.let { passageAt(it.html, it.text, it.at, needle.length) }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** NeoReader's progress "page/total" as a fraction of the book, if it has one. */
    private fun progress(book: Book): Double? {
        val (page, total) = book.progress?.split('/')?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.takeIf { it.size == 2 } ?: return null
        return if (total > 0) (page / total).coerceIn(0.0, 1.0) else null
    }

    /** The content documents in reading order, from the EPUB's package file. */
    private fun spine(zip: ZipFile): List<String>? = runCatching {
        val container = zip.getInputStream(zip.getEntry("META-INF/container.xml")).bufferedReader().use { it.readText() }
        val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)!!.groupValues[1]
        val dir = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val opf = zip.getInputStream(zip.getEntry(opfPath)).bufferedReader().use { it.readText() }
        val items = Regex("<item\\b[^>]*>").findAll(opf).associate { m ->
            val tag = m.value
            val id = Regex("\\bid=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: ""
            val href = Regex("\\bhref=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: ""
            id to java.net.URLDecoder.decode(href, "UTF-8")
        }
        Regex("<itemref\\b[^>]*idref=\"([^\"]+)\"").findAll(opf)
            .mapNotNull { items[it.groupValues[1]]?.let { href -> dir + href } }.toList()
            .takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun plain(html: String): String {
        val body = html.replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), " ")
        return normalize(Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY).toString())
    }

    private fun passageAt(html: String, text: String, at: Int, length: Int): Passage {
        var selStart = at
        var selEnd = at + length
        while (selStart > 0 && text[selStart - 1].isLetterOrDigit()) selStart--
        while (selEnd < text.length && text[selEnd].isLetterOrDigit()) selEnd++

        val from = sentenceStart(text, (selStart - RADIUS).coerceAtLeast(0))
        val to = sentenceEnd(text, (selEnd + RADIUS).coerceAtMost(text.length))
        return Passage(
            chapter = chapterTitle(html),
            text = text.substring(from, to).trim(),
            selection = text.substring(selStart, selEnd),
        )
    }

    private fun chapterTitle(html: String): String? =
        listOf("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>", "(?is)<title[^>]*>(.*?)</title>")
            .firstNotNullOfOrNull { Regex(it).find(html)?.groupValues?.get(1) }
            ?.let { normalize(Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString()) }
            ?.takeIf { it.isNotBlank() && it.length < 120 }

    private fun sentenceStart(text: String, from: Int): Int {
        if (from == 0) return 0
        val stop = text.indexOfAny(charArrayOf('.', '!', '?'), from)
        return if (stop in from until from + 200) stop + 1 else from
    }

    private fun sentenceEnd(text: String, to: Int): Int {
        if (to >= text.length) return text.length
        val stop = text.lastIndexOfAny(charArrayOf('.', '!', '?'), to - 1)
        return if (stop > to - 200) stop + 1 else to
    }

    private fun normalize(s: String): String = s
        .replace('’', '\'').replace('‘', '\'')
        .replace('“', '"').replace('”', '"')
        .replace(' ', ' ').replace('­'.toString(), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
