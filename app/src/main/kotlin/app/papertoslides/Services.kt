package app.papertoslides

import android.content.Context
import app.papertoslides.core.gemini.HttpTransport
import app.papertoslides.data.ProjectStore
import app.papertoslides.data.SettingsStore
import app.papertoslides.job.Exporter
import app.papertoslides.platform.OkHttpTransport
import app.papertoslides.platform.Sources
import app.papertoslides.security.KeyStoreRepo
import java.io.File

/** Manual dependency injection: one place that builds the app's services. */
class Services private constructor(ctx: Context) {
    val settings = SettingsStore(ctx.filesDir)
    val keys = KeyStoreRepo(File(ctx.filesDir, "vault"))
    val projects = ProjectStore(File(ctx.filesDir, "projects"))
    val http: HttpTransport = OkHttpTransport()
    val exporter = Exporter(ctx, projects)

    companion object {
        @Volatile private var instance: Services? = null
        fun get(ctx: Context): Services = instance ?: synchronized(this) {
            instance ?: Services(ctx.applicationContext).also { Sources.init(ctx); instance = it }
        }
    }
}
