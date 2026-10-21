package app.papertoslides.job

import app.papertoslides.core.keypool.KeyLanes
import app.papertoslides.core.keypool.KeyStat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class JobProgress(
    val projectId: String,
    val name: String,
    val stage: String = "Starting",
    val done: Int = 0,
    val total: Int = 0,
    val message: String? = null,
    val lanes: List<KeyStat> = emptyList(),
    val startedAt: Long = System.currentTimeMillis(),
    val stageStartedAt: Long = System.currentTimeMillis(),
    val finished: Boolean = false,
    val cancelled: Boolean = false,
    val error: String? = null,
    val warnings: List<String> = emptyList(),
    val apiRequests: Int = 0,
) {
    /** Honest ETA for the current stage from its own pace; null until there is enough evidence. */
    fun etaSeconds(now: Long = System.currentTimeMillis()): Long? {
        if (done < 2 || total <= done) return null
        val per = (now - stageStartedAt).toDouble() / done
        return ((total - done) * per / 1000).toLong()
    }
}

/** Live job state for the UI; survives rotation and app switches (process-wide). */
object JobBus {
    private val _state = MutableStateFlow<JobProgress?>(null)
    val state: StateFlow<JobProgress?> = _state
    @Volatile var activeLanes: KeyLanes? = null

    fun set(p: JobProgress?) { _state.value = p }
    fun update(f: JobProgress.() -> JobProgress) { _state.value?.let { _state.value = it.f() } }
}
