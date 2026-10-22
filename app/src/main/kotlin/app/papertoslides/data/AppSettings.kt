package app.papertoslides.data

import app.papertoslides.core.geometry.SlideLayout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Every editable default in one place. Never holds an API key. */
@Serializable
data class AppSettings(
    val layout: SlideLayout = SlideLayout.TOP,
    val blankAfter: Boolean = true,
    val removeWatermark: Boolean = true,
    val lang: String = "en",
    /** Pages per Gemini request (owner default 2, range 1..4). */
    val slicePages: Int = 2,
    /** Requests per minute per key. */
    val rpmPerKey: Int = 8,
    /** User's model order, comma separated; empty = discover from models.list. */
    val models: String = "",
    val pageCap: Int = 150,
    val dpi: Int = 250,
    val dataNoticeSeen: Boolean = false,
    /** Base URL (https, ending in /v1) and vision model for the "Other (OpenAI-compatible)" provider. */
    val customBaseUrl: String = "",
    val customModel: String = "",
    /** Per-provider model override (comma separated), keyed by provider id. Empty = defaults checked against /models. */
    val providerModels: Map<String, String> = emptyMap(),
    /** Cached models.list result (names + output limits) and when it was fetched. */
    val discovered: Map<String, Int?> = emptyMap(),
    val discoveredAt: Long = 0,
)

class SettingsStore(dir: File) {
    private val file = File(dir, "settings.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized fun get(): AppSettings = runCatching { json.decodeFromString<AppSettings>(file.readText()) }.getOrElse { AppSettings() }

    @Synchronized fun update(f: (AppSettings) -> AppSettings): AppSettings {
        val n = f(get())
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp"); tmp.writeText(json.encodeToString(n)); tmp.renameTo(file)
        return n
    }
}
