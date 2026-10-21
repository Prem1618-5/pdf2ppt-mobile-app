package app.papertoslides.job

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CompletableDeferred

/**
 * Low power, first in line (context §5.5): a wake lock only while a job runs (with a timeout, released in finally),
 * pause CPU stages on SEVERE thermal status, fewer workers on MODERATE / battery saver. Event-driven, no polling.
 */
class Governor(private val ctx: Context, private val onNotice: (String?) -> Unit) : AutoCloseable {
    private val pm = ctx.getSystemService(PowerManager::class.java)
    private val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PaperToSlides:job").apply { setReferenceCounted(false) }
    @Volatile private var thermal = 0
    @Volatile private var cool: CompletableDeferred<Unit>? = null
    private val listener: Any? = if (Build.VERSION.SDK_INT >= 29) PowerManager.OnThermalStatusChangedListener { s -> onThermal(s) } else null

    init {
        wake.acquire(3 * 60 * 60 * 1000L)
        if (Build.VERSION.SDK_INT >= 29) {
            pm.addThermalStatusListener(listener as PowerManager.OnThermalStatusChangedListener)
            thermal = pm.currentThermalStatus
        }
    }

    private fun onThermal(s: Int) {
        thermal = s
        if (Build.VERSION.SDK_INT >= 29 && s < PowerManager.THERMAL_STATUS_SEVERE) {
            cool?.complete(Unit); cool = null
            onNotice(null)
        }
    }

    /** Max CPU workers right now: clamp(cores - 1, 1, 3); 1 when warm or in battery saver. */
    fun cpuWorkers(): Int {
        val base = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 3)
        val moderate = Build.VERSION.SDK_INT >= 29 && thermal >= PowerManager.THERMAL_STATUS_MODERATE
        return if (moderate || pm.isPowerSaveMode) 1 else base
    }

    val powerSave get() = pm.isPowerSaveMode

    /** Before each heavy step: wait while the phone is too hot. */
    suspend fun gate() {
        if (Build.VERSION.SDK_INT < 29 || thermal < PowerManager.THERMAL_STATUS_SEVERE) return
        onNotice("Phone is hot. Paused to cool down…")
        val d = cool ?: CompletableDeferred<Unit>().also { cool = it }
        d.await()
    }

    override fun close() {
        if (Build.VERSION.SDK_INT >= 29) runCatching { pm.removeThermalStatusListener(listener as PowerManager.OnThermalStatusChangedListener) }
        cool?.complete(Unit)
        if (wake.isHeld) wake.release()
    }

    companion object {
        /** Battery < 15 % and not charging: warn before starting. */
        fun lowBattery(ctx: Context): Boolean {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            return level >= 0 && level * 100 / scale < 15 && !plugged
        }
    }
}
