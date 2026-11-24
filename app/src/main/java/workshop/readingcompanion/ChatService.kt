package workshop.readingcompanion

import org.json.JSONObject

/** A chat website the companion can drive, with how to start a chat, recognise one, and type into it. */
enum class ChatService(
    val label: String,
    val home: String,
    private val chatUrl: Regex,
    private val composers: List<String>,
    private val sendButtons: List<String>,
) {
    CHATGPT(
        label = "ChatGPT",
        home = "https://chatgpt.com/",
        chatUrl = Regex("^https://chatgpt\\.com/(?:g/[^/]+/)?c/[0-9a-f-]+"),
        composers = listOf("#prompt-textarea", "div[contenteditable=\"true\"]", "textarea"),
        sendButtons = listOf("button[data-testid=\"send-button\"]", "#composer-submit-button", "button[aria-label*=\"Send\"]", "form button[type=\"submit\"]"),
    ),
    CLAUDE(
        label = "Claude",
        home = "https://claude.ai/new",
        chatUrl = Regex("^https://claude\\.ai/chat/[0-9a-f-]+"),
        composers = listOf("div[contenteditable=\"true\"].ProseMirror", "div[contenteditable=\"true\"]", "textarea"),
        sendButtons = listOf("button[aria-label*=\"Send\"]", "fieldset button[type=\"submit\"]", "button[type=\"submit\"]"),
    );

    /** The conversation part of [url] if it is a saved chat, without query or fragment. */
    fun chatOf(url: String): String? = chatUrl.find(url)?.value

    /**
     * JS that waits for the composer, pastes [text] and presses send. It reports "sent", "typed"
     * (text in place but no send button found) or "nobox" through the RC bridge.
     */
    fun sendScript(text: String): String = """
        (function(text, composers, sends) {
          function find(list) { for (const s of list) { const e = document.querySelector(s); if (e) return e; } return null; }
          function fill(box) {
            box.focus();
            if (box.tagName === 'TEXTAREA') {
              const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
              setter.call(box, text);
              box.dispatchEvent(new Event('input', { bubbles: true }));
              return;
            }
            const dt = new DataTransfer();
            dt.setData('text/plain', text);
            box.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
            if (!box.innerText.trim()) document.execCommand('insertText', false, text);
          }
          let tries = 0;
          (function waitForBox() {
            const box = find(composers);
            if (!box) { if (++tries < 60) return setTimeout(waitForBox, 500); RC.result('nobox'); return; }
            fill(box);
            // Both sites send on Enter; used when no send button matches.
            function pressEnter() {
              box.focus();
              for (const type of ['keydown', 'keypress', 'keyup'])
                box.dispatchEvent(new KeyboardEvent(type, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
            }
            let n = 0;
            (function press() {
              const b = find(sends);
              if (b && !b.disabled) { b.click(); RC.result('sent'); return; }
              if (++n < 10) return setTimeout(press, 300);
              pressEnter();
              setTimeout(function() { RC.result(box.innerText.trim() || box.value ? 'typed' : 'sent'); }, 1500);
            })();
          })();
        })(${JSONObject.quote(text)}, ${json(composers)}, ${json(sendButtons)});
    """.trimIndent()

    /**
     * JS that renames the conversation at [chat] through the site's own API (what its rename menu
     * calls). Runs on the site's origin so the login cookies apply; reports "ok" or "fail" via RC.renamed.
     */
    fun renameScript(chat: String, title: String): String {
        val id = JSONObject.quote(chat.substringAfterLast('/'))
        val name = JSONObject.quote(title)
        val call = when (this) {
            CHATGPT -> """
                // The page embeds the session token; /api/auth/session is the older source.
                let token = null;
                try { token = window.__reactRouterContext.state.loaderData.root.clientBootstrap.session.accessToken; } catch (e) {}
                if (!token) for (const sc of document.querySelectorAll('script')) {
                  const m = sc.textContent.match(/"accessToken"\s*:\s*"([^"]+)"/);
                  if (m) { token = m[1]; break; }
                }
                if (!token) { try { token = JSON.parse(await (await fetch('/api/auth/session')).text()).accessToken; } catch (e) {} }
                if (!token) throw 'no access token on page';
                const r = await fetch('/backend-api/conversation/' + $id, {
                  method: 'PATCH',
                  headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token },
                  body: JSON.stringify({ title: $name })
                });
            """
            CLAUDE -> """
                const orgs = await (await fetch('/api/organizations')).json();
                const r = await fetch('/api/organizations/' + orgs[0].uuid + '/chat_conversations/' + $id, {
                  method: 'PUT',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ name: $name })
                });
            """
        }
        return "(async function() { try { $call RC.renamed(r.ok ? 'ok' : 'fail ' + r.status + ' ' + (await r.text()).slice(0, 200)); } catch (e) { RC.renamed('fail ' + e); } })();"
    }

    private fun json(list: List<String>) = list.joinToString(",", "[", "]") { JSONObject.quote(it) }
}
