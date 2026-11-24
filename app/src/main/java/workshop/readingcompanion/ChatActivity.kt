package workshop.readingcompanion

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * One popup that hosts the ChatGPT or Claude website, or a chat over an OpenAI-compatible API
 * (OpenRouter by default), and keeps one conversation per book. NeoReader opens it as the
 * "other dictionary"; each lookup goes into that book's chat.
 */
class ChatActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var header: TextView
    private lateinit var serviceButton: Button
    private lateinit var sizeButton: Button
    private val prefs by lazy { getSharedPreferences("companion", Context.MODE_PRIVATE) }

    /** Where lookups go; changed on the settings screen. */
    private val backend: String get() = Backends.current(prefs)

    private val service: ChatService get() = Backends.webService(backend)

    /** True when chatting through an API (see [ApiChat]) instead of a website. */
    private val api: Boolean get() = Backends.isApi(backend)

    /** The backend and window mode last shown, to notice changes made on the settings screen. */
    private var shownBackend: String? = null

    /** An API request is in flight. */
    private var asking = false

    private var fullscreen: Boolean
        get() = prefs.getBoolean("fullscreen", false)
        set(value) = prefs.edit().putBoolean("fullscreen", value).apply()

    private var book: Book? = null
    private var requestedSize: Pair<Int, Int>? = null

    /** Text waiting for the page to finish loading before it is typed in. */
    private var pending: String? = null
    private var pageReady = false

    /** The book whose new chat was just started, until the site gives the chat its address. */
    private var awaitingChatFor: Book? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        setContentView(buildLayout())
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        applyWindowSize()
        // Coming back from settings with a different AI: show that one.
        if (shownBackend != null && shownBackend != backend) {
            updateHeader()
            open(null)
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!api && web.canGoBack()) web.goBack() else moveTaskToBack(true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handle(intent: Intent) {
        val selection = intent.getStringExtra("EXTRA_QUERY")
            ?: intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)
        val width = intent.getIntExtra("EXTRA_WIDTH", 0)
        val height = intent.getIntExtra("EXTRA_HEIGHT", 0)
        if (width > 0 && height > 0) requestedSize = width to height
        applyWindowSize()

        // The library query and epub search touch the disk, so keep them off the UI thread.
        Thread {
            val current = BookContext.current(this)
            val passage = if (current != null && selection != null) PassageFinder.find(current, selection) else null
            runOnUiThread {
                book = current
                updateHeader()
                if (selection.isNullOrBlank()) open(null) else send(Prompt.lookup(current, selection, passage))
            }
        }.start()
    }

    private fun send(lookup: String) {
        val current = book
        if (api) {
            val fresh = ApiChat.history(this, current).length() == 0
            open(if (current != null && fresh) Prompt.intro(current) + "\n\n" + lookup else lookup)
            return
        }
        val saved = current?.let { savedChat(service, it) }
        open(if (current != null && saved == null) Prompt.intro(current) + "\n\n" + lookup else lookup)
        // Chats from before renaming existed, or whose rename failed, get another try.
        if (current != null && saved != null && !prefs.getBoolean(renamedKey(service, current), false)) {
            scheduleRename(service, current, saved)
        }
    }

    /**
     * Names the book's chat after the book. The sites set their own title once the first answer
     * arrives, so rename after that, and once more later in case the auto-title landed late.
     */
    private fun scheduleRename(service: ChatService, book: Book, chat: String) {
        val title = Prompt.chatTitle(book)
        for (delay in listOf(20_000L, 60_000L)) {
            web.postDelayed({
                val url = web.url ?: return@postDelayed
                // The API call needs the site's own origin for its cookies.
                if (Uri.parse(url).host != Uri.parse(service.home).host) return@postDelayed
                renaming = renamedKey(service, book).takeIf { delay == 60_000L }
                web.evaluateJavascript(service.renameScript(chat, title), null)
            }, delay)
        }
    }

    /** Set while the final rename attempt runs, so a success can be recorded. */
    private var renaming: String? = null

    private fun renamedKey(service: ChatService, book: Book) = "renamed:${service.name}:${book.path}"

    /** Shows the current book's chat (or a new one) and types [text] into it once it is ready. */
    private fun open(text: String?) {
        if (api) { openApi(text); return }
        val current = book
        val saved = current?.let { savedChat(service, it) }
        val target = saved ?: service.home
        awaitingChatFor = if (saved == null && text != null) current else null
        pending = text

        val showing = web.url
        val alreadyThere = showing != null && pageReady &&
            if (saved != null) service.chatOf(showing) == saved else showing.substringBefore('?').substringBefore('#') == service.home
        if (alreadyThere) typePending() else {
            pageReady = false
            web.loadUrl(target)
        }
    }

    /** Shows the book's API chat and, if [text] is given, asks it and shows the answer. */
    private fun openApi(text: String?) {
        val current = book
        val title = current?.let { Prompt.chatTitle(it) } ?: "No book"
        val settings = ApiChat.settings(prefs)
        val messages = ApiChat.history(this, current)
        if (text == null || asking) {
            showApi(title, messages, if (asking) "Thinking…" else null)
            return
        }
        if (settings.preset.needsKey && settings.key.isBlank()) {
            showApi(title, messages, "No ${settings.label} key yet. Tap the AI button at the top, paste your key, then look the word up again.")
            return
        }
        messages.put(ApiChat.message("user", text))
        ApiChat.store(this, current, messages)
        asking = true
        showApi(title, messages, "Thinking… (${settings.model})")
        Thread {
            val result = runCatching { ApiChat.complete(settings, messages) }
            runOnUiThread {
                asking = false
                result.onSuccess { answer ->
                    messages.put(ApiChat.message("assistant", answer))
                    ApiChat.store(this, current, messages)
                }
                // Only redraw if the reader is still on this book's API chat.
                if (api && book?.path == current?.path) {
                    showApi(title, messages, result.exceptionOrNull()?.let { "Error: ${it.message}" })
                }
            }
        }.start()
    }

    private fun showApi(title: String, messages: org.json.JSONArray, status: String?) {
        // A website page may still be loading (or redirecting) from the previous mode.
        web.stopLoading()
        pageReady = false
        web.loadDataWithBaseURL(null, ApiChat.page(title, messages, status), "text/html", "utf-8", null)
    }

    private fun typePending() {
        val text = pending ?: return
        pending = null
        web.evaluateJavascript(service.sendScript(text), null)
    }

    private fun savedChat(service: ChatService, book: Book): String? =
        prefs.getString("chat:${service.name}:${book.path}", null)

    private fun saveChat(service: ChatService, book: Book, url: String?) =
        prefs.edit().putString("chat:${service.name}:${book.path}", url).apply()

    private fun updateHeader() {
        val b = book
        header.text = when {
            b == null -> "No book open in NeoReader"
            else -> listOfNotNull(b.title, b.progress?.let { "p. $it" }).joinToString("  ·  ")
        } + if (Environment.isExternalStorageManager()) "" else "  (no file access: passages off)"
        serviceButton.text = Backends.label(backend) + " ▾"
        sizeButton.text = if (fullscreen) "Popup" else "Full"
        shownBackend = backend
    }

    private fun applyWindowSize() {
        val size = requestedSize
        if (fullscreen || size == null) window.setLayout(MATCH_PARENT, MATCH_PARENT)
        else window.setLayout(size.first, size.second)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildLayout(): LinearLayout {
        header = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.BLACK)
            maxLines = 2
            setPadding(16, 8, 8, 8)
        }
        // Opens the settings screen (which AI, key, model, window size).
        serviceButton = button(Backends.label(backend) + " ▾") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        sizeButton = button(if (fullscreen) "Popup" else "Full") {
            fullscreen = !fullscreen
            applyWindowSize()
            updateHeader()
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(header, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(serviceButton)
            addView(button("New") {
                if (api) ApiChat.clear(this@ChatActivity, book) else book?.let { saveChat(service, it, null) }
                open(null)
            })
            addView(sizeButton)
            addView(button("✕") { moveTaskToBack(true) })
        }

        CookieManager.getInstance().setAcceptCookie(true)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportMultipleWindows(false)
            // Sites (and Google sign-in) treat the stock WebView agent as an embedded browser.
            settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(" Version/4.0", "")
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(Bridge(), "RC")
            webViewClient = object : WebViewClient() {
                // The sites try intent:// links to hand off to their installed apps; stay in the WebView.
                // In API mode the page is ours, so late redirects from a website are ignored too.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    api || request.url.scheme !in setOf("http", "https")

                override fun onPageFinished(view: WebView, url: String) {
                    pageReady = true
                    // The API page is redrawn often; keep it out of Back history.
                    if (api) view.clearHistory()
                    typePending()
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    val forBook = awaitingChatFor ?: return
                    val chat = service.chatOf(url) ?: return
                    saveChat(service, forBook, chat)
                    awaitingChatFor = null
                    scheduleRename(service, forBook, chat)
                }
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private inner class Bridge {
        /** A follow-up typed into the API chat page. */
        @JavascriptInterface
        fun ask(text: String) = runOnUiThread { if (api && text.isNotBlank()) open(text.trim()) }

        @JavascriptInterface
        fun renamed(status: String) = runOnUiThread {
            android.util.Log.i("ReadingCompanion", "rename: $status")
            val key = renaming
            if (status == "ok" && key != null) prefs.edit().putBoolean(key, true).apply()
            renaming = null
        }

        @JavascriptInterface
        fun result(status: String) = runOnUiThread {
            // The composer keeps focus after sending, which brings up the keyboard over the answer.
            if (status == "sent") {
                web.evaluateJavascript("document.activeElement && document.activeElement.blur()", null)
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(web.windowToken, 0)
            }
            when (status) {
                "typed" -> Toast.makeText(this@ChatActivity, "Typed in. Tap send.", Toast.LENGTH_SHORT).show()
                "nobox" -> Toast.makeText(this@ChatActivity, "Couldn't find the message box. Log in first?", Toast.LENGTH_LONG).show()
            }
        }
    }
}
