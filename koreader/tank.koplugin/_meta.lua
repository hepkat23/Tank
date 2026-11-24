local _ = require("gettext")
return {
    fullname = _("Tank"),
    description = _([[AI reading companion. Select text and tap "Ask Tank": it explains it in the context of your book, one chat per book, no spoilers. Answers come from the Tank app on Android (ChatGPT/Claude website, no API needed), an API key (OpenRouter, Gemini, Anthropic, ...) or a local model (LM Studio, Ollama).]]),
}
