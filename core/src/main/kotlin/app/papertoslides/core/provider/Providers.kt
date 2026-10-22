package app.papertoslides.core.provider

enum class ApiStyle { GEMINI, OPENAI }

/**
 * An AI service the user can bring a key for. [models] are vision-capable defaults in order of preference; the real
 * list is checked against the provider's own `/models` at job start and the user can override it.
 * [maxImages] = pages one request may carry (the client splits bigger slices itself).
 */
data class Provider(
    val id: String,
    val label: String,
    val style: ApiStyle,
    val baseUrl: String,
    val models: List<String>,
    val maxImages: Int,
    val keyHelp: String,
)

object Providers {
    val GEMINI = Provider("gemini", "Google Gemini", ApiStyle.GEMINI, "https://generativelanguage.googleapis.com/v1beta", emptyList(), 4,
        "Free key: aistudio.google.com → Get API key")
    val GROQ = Provider("groq", "Groq", ApiStyle.OPENAI, "https://api.groq.com/openai/v1",
        listOf("meta-llama/llama-4-scout-17b-16e-instruct", "meta-llama/llama-4-maverick-17b-128e-instruct"), 5,
        "Free key: console.groq.com/keys (starts with gsk_)")
    val NVIDIA = Provider("nvidia", "NVIDIA NIM", ApiStyle.OPENAI, "https://integrate.api.nvidia.com/v1",
        listOf("meta/llama-4-maverick-17b-128e-instruct", "meta/llama-3.2-90b-vision-instruct", "meta/llama-3.2-11b-vision-instruct", "microsoft/phi-4-multimodal-instruct"), 1,
        "Free key: build.nvidia.com → any model → Get API Key (starts with nvapi-)")
    val OPENROUTER = Provider("openrouter", "OpenRouter", ApiStyle.OPENAI, "https://openrouter.ai/api/v1",
        listOf("meta-llama/llama-4-maverick:free", "meta-llama/llama-4-scout:free", "qwen/qwen2.5-vl-72b-instruct:free", "google/gemma-3-27b-it:free"), 3,
        "Free key: openrouter.ai/keys (models ending in :free)")
    val MISTRAL = Provider("mistral", "Mistral", ApiStyle.OPENAI, "https://api.mistral.ai/v1",
        listOf("pixtral-12b-2409", "mistral-small-latest"), 4,
        "Free \"Experiment\" key: console.mistral.ai")
    /** Any other OpenAI-compatible service with vision: the user gives the base URL and a model name. */
    val CUSTOM = Provider("custom", "Other (OpenAI-compatible)", ApiStyle.OPENAI, "", emptyList(), 3,
        "Enter the base URL (ending in /v1) and a vision model name below")

    val ALL = listOf(GEMINI, GROQ, NVIDIA, OPENROUTER, MISTRAL, CUSTOM)

    fun byId(id: String): Provider = ALL.firstOrNull { it.id == id } ?: GEMINI

    /** Hosts the app may talk to: every built-in provider plus the host of the user's custom URL. */
    fun allowedHosts(customBaseUrl: String): Set<String> =
        (ALL.mapNotNull { hostOf(it.baseUrl) } + listOfNotNull(hostOf(customBaseUrl))).toSet()

    fun hostOf(url: String): String? =
        Regex("""^https://([A-Za-z0-9.-]+)(?::\d+)?(?:/|$)""").find(url.trim())?.groupValues?.get(1)?.lowercase()

    private val VISION_HINT = Regex("""vision|vl\b|-vl-|scout|maverick|pixtral|gemma-3|llama-4|multimodal|omni|4o|gemini""", RegexOption.IGNORE_CASE)

    /**
     * Model order for an OpenAI-style provider: the user's names win; else the defaults that the provider really
     * lists; else anything in the list that looks like a vision model; else the defaults untouched (list unknown).
     */
    fun chooseModels(p: Provider, configured: List<String>, available: List<String>, customModel: String = ""): List<String> {
        if (configured.isNotEmpty()) return configured
        if (p.id == CUSTOM.id) return listOfNotNull(customModel.trim().takeIf { it.isNotEmpty() })
        if (available.isEmpty()) return p.models
        val kept = p.models.filter { it in available }
        if (kept.isNotEmpty()) return kept
        val guess = available.filter { VISION_HINT.containsMatchIn(it) && !it.contains("embed", true) }.take(4)
        return guess.ifEmpty { p.models }
    }
}

/**
 * A "lane key" is what the scheduler carries around for one stored key. Gemini keys stay as the bare key (so their
 * saved health keeps working); every other provider is `provider|key`. Real keys never contain `|`.
 */
object KeyToken {
    fun make(providerId: String, key: String) = if (providerId == Providers.GEMINI.id) key else "$providerId|$key"
    fun providerId(token: String) = token.substringBefore('|', Providers.GEMINI.id).takeIf { '|' in token } ?: Providers.GEMINI.id
    fun provider(token: String) = Providers.byId(providerId(token))
    fun secret(token: String) = if ('|' in token) token.substringAfter('|') else token
}
