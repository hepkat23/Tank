package workshop.readingcompanion

/** The text sent to the chat for one lookup. */
object Prompt {

    /** The name given to a book's chat on the site. */
    fun chatTitle(book: Book): String = listOfNotNull(book.title, book.author).joinToString(" – ")

    /**
     * The first message in a book's chat leads with the title and author so the site's
     * auto-generated chat title names the book.
     */
    fun intro(book: Book): String = buildString {
        append(book.title)
        book.author?.let { append(" by ").append(it) }
        append("\n\n")
        append("I'm reading this book and will send you words and passages as I go. ")
        append("For each one, explain what it means here, in the context of the book, briefly and in English. ")
        append("Don't spoil anything past the page I'm on.")
    }

    fun lookup(book: Book?, selection: String, passage: Passage?): String = buildString {
        val where = listOfNotNull(
            book?.progress?.let { "Page $it" },
            passage?.chapter?.let { "\"$it\"" },
        )
        if (where.isNotEmpty()) append(where.joinToString(" · ")).append("\n")
        append("Selected: \"").append(passage?.selection ?: selection.trim()).append("\"")
        passage?.text?.let { append("\n\nPassage:\n").append(it) }
    }
}
