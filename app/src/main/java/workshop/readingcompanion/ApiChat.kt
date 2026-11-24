package workshop.readingcompanion

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Chat through any OpenAI-compatible API instead of a website. Defaults to OpenRouter (one key,
 * hundreds of models); the address can point anywhere else that speaks the same API.
 * Each book keeps its conversation in a JSON file in the app's private storage.
 */
object ApiChat {

    /** Messages sent per request: the first one (the book intro) plus the most recent ones. */
    private const val MAX_SENT = 20

    data class Settings(val preset: Backends.Preset, val base: String, val key: String, val model: String) {
        val label: String get() = preset.label.substringBefore(" (")
    }

    /** Settings for the API preset currently chosen (or [id] if given). */
    fun settings(prefs: SharedPreferences, id: String = Backends.apiPreset(Backends.current(prefs)).id): Settings {
        val p = Backends.preset(id)
        return Settings(
            preset = p,
            base = prefs.getString("api.$id.base", null)?.takeIf { it.isNotBlank() } ?: p.base,
            key = prefs.getString("api.$id.key", "") ?: "",
            model = prefs.getString("api.$id.model", null)?.takeIf { it.isNotBlank() } ?: p.model,
        )
    }

    fun save(prefs: SharedPreferences, id: String, base: String, key: String, model: String) = prefs.edit()
        .putString("api.$id.base", base.trim().trimEnd('/'))
        .putString("api.$id.key", key.trim())
        .putString("api.$id.model", model.trim())
        .apply()

    /** The model ids the server offers (GET /models). Blocking: call off the UI thread. */
    fun models(s: Settings): List<String> {
        val conn = (URL(s.base + "/models").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            if (s.key.isNotBlank()) setRequestProperty("Authorization", "Bearer ${s.key}")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            val data = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONArray("data")
            return (0 until data.length()).map { data.getJSONObject(it).getString("id") }.sorted()
        } finally {
            conn.disconnect()
        }
    }

    private fun file(context: Context, book: Book?): File {
        val dir = File(context.filesDir, "api-chats").apply { mkdirs() }
        val name = book?.path?.let { sha1(it) } ?: "no-book"
        return File(dir, "$name.json")
    }

    fun history(context: Context, book: Book?): JSONArray {
        val f = file(context, book)
        return if (f.exists()) runCatching { JSONArray(f.readText()) }.getOrElse { JSONArray() } else JSONArray()
    }

    fun store(context: Context, book: Book?, messages: JSONArray) = file(context, book).writeText(messages.toString())

    fun clear(context: Context, book: Book?) { file(context, book).delete() }

    fun message(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    /** Sends the conversation and returns the answer text. Blocking: call off the UI thread. */
    fun complete(s: Settings, messages: JSONArray): String {
        val sent = JSONArray()
        val n = messages.length()
        if (n > 0) sent.put(messages[0])
        for (i in maxOf(1, n - (MAX_SENT - 1)) until n) sent.put(messages[i])

        val body = JSONObject().put("model", s.model).put("messages", sent).toString()
        val conn = (URL(s.base + "/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (s.key.isNotBlank()) setRequestProperty("Authorization", "Bearer ${s.key}")
            setRequestProperty("X-Title", "Reading Companion")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val reason = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                throw RuntimeException("HTTP $code: ${reason ?: text.take(300)}")
            }
            return JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content").trim()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The conversation as a plain black-on-white page for the e-ink screen, with a box for
     * follow-up questions (sent to RC.ask). [status] is shown under the last message.
     */
    fun page(title: String, messages: JSONArray, status: String?): String = buildString {
        append("""<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{font:17px/1.45 serif;color:#000;background:#fff;margin:0;padding:12px 14px 90px}
h1{font-size:15px;font-weight:normal;border-bottom:1px solid #000;padding-bottom:4px;margin:0 0 10px}
.m{white-space:pre-wrap;margin:0 0 14px}
.u{border-left:3px solid #000;padding-left:8px;font-size:15px}
.s{font-style:italic}
form{position:fixed;left:0;right:0;bottom:0;background:#fff;border-top:1px solid #000;display:flex;padding:8px;gap:8px}
textarea{flex:1;font:16px serif;border:1px solid #000;height:48px;resize:none}
button{font:16px serif;border:2px solid #000;background:#fff;color:#000;padding:0 14px}
</style></head><body><h1>""")
        append(esc(title)).append("</h1>")
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val cls = if (m.optString("role") == "user") "m u" else "m"
            append("<div class=\"$cls\">").append(bold(esc(m.optString("content")))).append("</div>")
        }
        status?.let { append("<div class=\"m s\">").append(esc(it)).append("</div>") }
        append("""<div id="end"></div>
<form onsubmit="var t=document.getElementById('q');if(t.value.trim()){RC.ask(t.value);t.value='';}return false;">
<textarea id="q" placeholder="Ask a follow-up"></textarea><button>Send</button></form>
<script>document.getElementById('end').scrollIntoView();</script></body></html>""")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Markdown **bold**, *italic* and # headings; the rest reads fine as plain text. */
    private fun bold(s: String) = s
        .replace(Regex("\\*\\*(.+?)\\*\\*")) { "<b>${it.groupValues[1]}</b>" }
        .replace(Regex("(?<![*\\w])\\*(?!\\s)([^*\\n]+?)\\*(?![*\\w])")) { "<i>${it.groupValues[1]}</i>" }
        .replace(Regex("(?m)^#{1,6} (.+)$")) { "<b>${it.groupValues[1]}</b>" }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
