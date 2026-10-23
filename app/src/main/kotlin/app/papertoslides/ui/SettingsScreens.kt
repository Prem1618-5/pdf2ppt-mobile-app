@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.papertoslides.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.papertoslides.BuildConfig

@Composable
private fun BackBar(title: String, onBack: () -> Unit) = TopAppBar(title = { Text(title) }, navigationIcon = {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
})

/** Keys: FLAG_SECURE (no screenshots / recents thumbnail), masked keys only, clipboard cleared after paste. */
@Composable
fun KeysScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val w = (ctx as? Activity)?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val keys by vm.keys.collectAsState()
    val msg by vm.keyMessage.collectAsState()
    val st by vm.settings.collectAsState()
    var input by remember { mutableStateOf("") }
    Scaffold(topBar = { BackBar("Gemini keys", onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Keys are only needed for scans and photos the phone cannot read by itself. They are stored encrypted on this phone and sent only to Google.",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                label = { Text("Paste key(s), one per line") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(), minLines = 2,
            )
            Button(onClick = {
                vm.addKeys(input); input = ""
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                if (Build.VERSION.SDK_INT >= 28) runCatching { cm.clearPrimaryClip() } else runCatching { cm.setPrimaryClip(ClipData.newPlainText("", "")) }
            }, enabled = input.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 52.dp)) { Text("Add") }
            msg?.let { Text(it, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(16.dp))
            if (keys.isEmpty()) Text("No keys yet. Get a free key at aistudio.google.com (Get API key).", style = MaterialTheme.typography.bodyMedium)
            for (k in keys) {
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Key ${k.view.masked}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                            Text(when (k.state) { "invalid" -> "Not working"; "exhausted" -> "Out of free quota"; else -> "Ready" } + (k.note?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = if (k.state == "ok") MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { vm.checkKey(k.view.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check") }
                        TextButton(onClick = { vm.removeKey(k.view.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Good to know", style = MaterialTheme.typography.titleSmall)
                    Text("• Free keys allow a small number of requests per day per model. The daily limit resets at midnight Pacific time (about 12:30 – 1:30 pm in India).", style = MaterialTheme.typography.bodySmall)
                    Text("• Several keys work at the same time, but keys from the same Google project share one limit. Use keys you are allowed to use (your own, your school's, or a paid one).", style = MaterialTheme.typography.bodySmall)
                    Text("• On the free tier Google may use the page images to improve its products. Do not send papers you are not allowed to share.", style = MaterialTheme.typography.bodySmall)
                    Text("• Pages per request: ${st.slicePages}. Change it in Settings.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onSelfTest: () -> Unit, onTemplates: () -> Unit) {
    val st by vm.settings.collectAsState()
    var models by remember(st.models) { mutableStateOf(st.models) }
    Scaffold(topBar = { BackBar("Settings", onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTemplates, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Slide templates (institute name, strips, logo)") }
            HorizontalDivider()
            Text("Comprehension passages", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(selected = st.passageMode == app.papertoslides.core.project.PassageMode.REPEAT, onClick = { vm.updateSettings { it.copy(passageMode = app.papertoslides.core.project.PassageMode.REPEAT) } },
                    label = { Text("With every question") }, modifier = Modifier.heightIn(min = 48.dp))
                androidx.compose.material3.FilterChip(selected = st.passageMode == app.papertoslides.core.project.PassageMode.ONCE, onClick = { vm.updateSettings { it.copy(passageMode = app.papertoslides.core.project.PassageMode.ONCE) } },
                    label = { Text("Once, own slide") }, modifier = Modifier.heightIn(min = 48.dp))
            }
            Text("With every question: the passage and the question share the slide (it may use the full height). If it does not fit, the passage goes on its own slide automatically.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Long questions may use the full height", Modifier.weight(1f))
                androidx.compose.material3.Switch(st.expandLong, { v -> vm.updateSettings { it.copy(expandLong = v) } })
            }
            Text("Smallest text on a slide: ${st.minPt} pt", style = MaterialTheme.typography.titleSmall)
            Slider(st.minPt.toFloat(), { v -> vm.updateSettings { it.copy(minPt = v.toInt().coerceIn(12, 24)) } }, valueRange = 12f..24f, steps = 11)
            Text("Questions that would need smaller text are shown as a picture instead.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Pages per Gemini request: ${st.slicePages}", style = MaterialTheme.typography.titleSmall)
            Slider(st.slicePages.toFloat(), { v -> vm.updateSettings { it.copy(slicePages = v.toInt().coerceIn(1, 4)) } }, valueRange = 1f..4f, steps = 2)
            Text("More pages per request saves daily quota; fewer pages is a little more accurate.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Requests per minute per key: ${st.rpmPerKey}", style = MaterialTheme.typography.titleSmall)
            Slider(st.rpmPerKey.toFloat(), { v -> vm.updateSettings { it.copy(rpmPerKey = v.toInt().coerceIn(1, 30)) } }, valueRange = 1f..30f)
            HorizontalDivider()
            Text("Page limit: ${st.pageCap}", style = MaterialTheme.typography.titleSmall)
            Slider(st.pageCap.toFloat(), { v -> vm.updateSettings { it.copy(pageCap = (v.toInt() / 10 * 10).coerceIn(10, 500)) } }, valueRange = 10f..500f)
            HorizontalDivider()
            OutlinedTextField(models, { models = it }, label = { Text("Gemini models (optional, comma separated)") },
                supportingText = { Text("Leave empty to pick automatically from your key's model list. Found: " + st.discovered.keys.filter { "flash" in it }.take(6).joinToString().ifEmpty { "none yet (check a key)" }) },
                modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { vm.updateSettings { it.copy(models = models.trim()) } }, enabled = models.trim() != st.models) { Text("Save models") }
            HorizontalDivider()
            Button(onClick = onSelfTest, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Self-test") }
            Text("Runs the built-in test papers on this phone and shows what works. No key is used.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Paper to Slides ${BuildConfig.VERSION_NAME}. No account, no ads, no tracking. Your papers stay on this phone; only pages the phone cannot read are sent to Google Gemini with your own key.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun SelfTestScreen(vm: MainViewModel, onBack: () -> Unit) {
    val r by vm.selfTest.collectAsState()
    val ctx = LocalContext.current
    Scaffold(topBar = { BackBar("Self-test", onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            val rep = r
            Button(onClick = { vm.runSelfTest() }, enabled = rep?.running != true, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (rep == null) "Run self-test" else if (rep.running) "Running…" else "Run again")
            }
            if (rep?.running == true) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(); Spacer(Modifier.height(0.dp)); Text("  This takes about a minute.")
            }
            if (rep != null) {
                Text("${rep.passed} / ${rep.cases.size} passed · peak memory ${rep.peakMemMb} MB", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                for (c in rep.cases) {
                    Text((if (c.pass) "PASS  " else "FAIL  ") + c.name, style = MaterialTheme.typography.titleSmall,
                        color = if (c.pass) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                    Text("${c.detail} · ${c.ms} ms", style = MaterialTheme.typography.bodySmall)
                }
                if (!rep.running) OutlinedButton(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Self-test", rep.text()))
                }, modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp)) { Text("Copy report") }
                Text(rep.header, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
