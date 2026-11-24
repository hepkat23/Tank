package workshop.readingcompanion

import android.content.SharedPreferences

/**
 * Where Tank sends lookups: one of the two chat websites, or an OpenAI-compatible API preset.
 * The choice is stored as "web:CHATGPT", "web:CLAUDE" or "api:<preset id>".
 */
object Backends {

    /** An OpenAI-compatible service. Address, key and model can all be changed in settings. */
    data class Preset(
        val id: String,
        val label: String,
        val base: String,
        val model: String,
        val needsKey: Boolean,
        val note: String,
    )

    val PRESETS = listOf(
        Preset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-v3.2", true,
            "One key for hundreds of models. Key: openrouter.ai/keys"),
        Preset("huggingface", "Hugging Face", "https://router.huggingface.co/v1", "openai/gpt-oss-120b", true,
            "Open models, free monthly credit. Token: huggingface.co/settings/tokens"),
        Preset("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", true,
            "Has a free tier. Key: aistudio.google.com/apikey"),
        Preset("anthropic", "Anthropic (Claude API)", "https://api.anthropic.com/v1", "claude-sonnet-5-5", true,
            "Claude by API key. Key: console.anthropic.com"),
        Preset("openai", "OpenAI", "https://api.openai.com/v1", "gpt-5-mini", true,
            "Key: platform.openai.com/api-keys"),
        Preset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", true,
            "Cheap. Key: platform.deepseek.com"),
        Preset("mistral", "Mistral", "https://api.mistral.ai/v1", "mistral-small-latest", true,
            "Key: console.mistral.ai"),
        Preset("lmstudio", "LM Studio (your computer)", "http://192.168.1.10:1234/v1", "", false,
            "Free and private. Put your computer's address here and start LM Studio's server."),
        Preset("ollama", "Ollama (your computer)", "http://192.168.1.10:11434/v1", "", false,
            "Free and private. Put your computer's address here; run Ollama with OLLAMA_HOST=0.0.0.0."),
        Preset("custom", "Other (OpenAI-compatible)", "https://", "", false,
            "Any server that speaks the OpenAI chat API."),
    )

    fun preset(id: String): Preset = PRESETS.firstOrNull { it.id == id } ?: PRESETS[0]

    /** The current choice, migrating settings from before this screen existed. */
    fun current(prefs: SharedPreferences): String {
        prefs.getString("backend", null)?.let { return it }
        val choice = if (prefs.getBoolean("api", false)) {
            // The old single API setting was OpenRouter by default.
            prefs.getString("api.key", null)?.let { prefs.edit().putString("api.openrouter.key", it).apply() }
            prefs.getString("api.model", null)?.let { prefs.edit().putString("api.openrouter.model", it).apply() }
            "api:openrouter"
        } else {
            "web:" + (prefs.getString("service", null) ?: ChatService.CHATGPT.name)
        }
        prefs.edit().putString("backend", choice).apply()
        return choice
    }

    fun set(prefs: SharedPreferences, choice: String) = prefs.edit().putString("backend", choice).apply()

    fun isApi(choice: String) = choice.startsWith("api:")

    fun webService(choice: String): ChatService =
        runCatching { ChatService.valueOf(choice.removePrefix("web:")) }.getOrDefault(ChatService.CHATGPT)

    fun apiPreset(choice: String): Preset = preset(choice.removePrefix("api:"))

    fun label(choice: String): String = if (isApi(choice)) apiPreset(choice).label.substringBefore(" (") else webService(choice).label
}
