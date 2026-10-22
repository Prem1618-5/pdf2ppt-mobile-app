package app.papertoslides

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.papertoslides.ui.HomeScreen
import app.papertoslides.ui.KeysScreen
import app.papertoslides.ui.MainViewModel
import app.papertoslides.ui.PaperTheme
import app.papertoslides.ui.ProgressScreen
import app.papertoslides.ui.ReviewScreen
import app.papertoslides.ui.SelfTestScreen
import app.papertoslides.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PaperTheme {
                val vm: MainViewModel = viewModel()
                val nav = rememberNavController()
                // Android 13+: ask once; if refused, jobs still run (just without the progress notification)
                val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                val job by vm.job.collectAsState()
                LaunchedEffect(job?.projectId) {
                    if (job != null && Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                fun home() { vm.refresh(); nav.popBackStack("home", inclusive = false) }
                NavHost(nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(vm, onProgress = { nav.navigate("progress") }, onReview = { nav.navigate("review/$it") },
                            onKeys = { nav.navigate("keys") }, onSettings = { nav.navigate("settings") })
                    }
                    composable("progress") {
                        ProgressScreen(vm, onDone = { id -> nav.navigate("review/$id") { popUpTo("home") } }, onHome = ::home)
                    }
                    composable("review/{id}") { e -> ReviewScreen(vm, e.arguments?.getString("id") ?: "", onBack = ::home) }
                    composable("keys") { KeysScreen(vm, onBack = { vm.refresh(); nav.popBackStack() }) }
                    composable("settings") { SettingsScreen(vm, onBack = { nav.popBackStack() }, onSelfTest = { nav.navigate("selftest") }) }
                    composable("selftest") { SelfTestScreen(vm, onBack = { nav.popBackStack() }) }
                }
            }
        }
    }
}
