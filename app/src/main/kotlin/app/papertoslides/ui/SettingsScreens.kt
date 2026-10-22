@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.papertoslides.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import app.papertoslides.core.provider.Providers
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
    var providerId by remember { mutableStateOf(Providers.GEMINI.id) }
    val provider = Providers.byId(providerId)
    var customUrl by remember(st.customBaseUrl) { mutableStateOf(st.customBaseUrl) }
    var modelText by remember(providerId, st.customModel, st.providerModels) {
        mutableStateOf(if (providerId == Providers.CUSTOM.id) st.customModel else st.providerModels[providerId].orEmpty())
    }
    Scaffold(topBar = { BackBar("AI keys", onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Keys are only needed for scans and photos the phone cannot read by itself. They are stored encrypted on this phone and sent only to the service you pick. You can add keys from several services; they all work together.",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Text("Service", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in Providers.ALL) FilterChip(selected = p.id == providerId, onClick = { providerId = p.id }, label = { Text(p.label) })
            }
            Text(provider.keyHelp, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            if (provider.id == Providers.CUSTOM.id) {
                OutlinedTextField(customUrl, { customUrl = it }, label = { Text("Base URL, e.g. https://api.together.xyz/v1") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (provider.id != Providers.GEMINI.id) {
                OutlinedTextField(modelText, { modelText = it },
                    label = { Text(if (provider.id == Providers.CUSTOM.id) "Vision model name" else "Model override (optional)") },
                    supportingText = { if (provider.id != Providers.CUSTOM.id) Text("Leave empty to use: " + provider.models.take(2).joinToString()) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = {
                    vm.updateSettings { s0 ->
                        if (provider.id == Providers.CUSTOM.id) s0.copy(customBaseUrl = customUrl.trim(), customModel = modelText.trim())
                        else s0.copy(providerModels = s0.providerModels + (provider.id to modelText.trim()))
                    }
                }, modifier = Modifier.padding(vertical = 4.dp)) { Text("Save") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                label = { Text("Paste key(s), one per line") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(), minLines = 2,
            )
            Button(onClick = {
                vm.addKeys(input, providerId); input = ""
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                if (Build.VERSION.SDK_INT >= 28) runCatching { cm.clearPrimaryClip() } else runCatching { cm.setPrimaryClip(ClipData.newPlainText("", "")) }
            }, enabled = input.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 52.dp)) { Text("Add") }
            msg?.let { Text(it, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(16.dp))
            if (keys.isEmpty()) Text("No keys yet. Pick a service above and paste its key.", style = MaterialTheme.typography.bodyMedium)
            for (k in keys) {
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${Providers.byId(k.view.provider).label} ${k.view.masked}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
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
                    Text("• Free keys allow a small number of requests per day. Gemini's limit resets at midnight Pacific time (about 12:30 – 1:30 pm in India); other services have their own limits.", style = MaterialTheme.typography.bodySmall)
                    Text("• Groq, NVIDIA, OpenRouter and Mistral models read pages less accurately than Gemini. Check the slides marked Check. NVIDIA reads one page per request.", style = MaterialTheme.typography.bodySmall)
                    Text("• Several keys work at the same time, but keys from the same Google project share one limit. Use keys you are allowed to use (your own, your school's, or a paid one).", style = MaterialTheme.typography.bodySmall)
                    Text("• On free tiers the service (Google, Groq, NVIDIA, …) may keep or use the page images to improve its products. Do not send papers you are not allowed to share.", style = MaterialTheme.typography.bodySmall)
                    Text("• Pages per request: ${st.slicePages}. Change it in Settings.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onSelfTest: () -> Unit) {
    val st by vm.settings.collectAsState()
    var models by remember(st.models) { mutableStateOf(st.models) }
    Scaffold(topBar = { BackBar("Settings", onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pages per AI request: ${st.slicePages}", style = MaterialTheme.typography.titleSmall)
            Slider(st.slicePages.toFloat(), { v -> vm.updateSettings { it.copy(slicePages = v.toInt().coerceIn(1, 4)) } }, valueRange = 1f..4f, steps = 2)
            Text("More pages per request saves daily quota; fewer pages is a little more accurate.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Requests per minute per key: ${st.rpmPerKey}", style = MaterialTheme.typography.titleSmall)
            Slider(st.rpmPerKey.toFloat(), { v -> vm.updateSettings { it.copy(rpmPerKey = v.toInt().coerceIn(1, 30)) } }, valueRange = 1f..30f)
            HorizontalDivider()
            Text("Page limit: ${st.pageCap}", style = MaterialTheme.typography.titleSmall)
            Slider(st.pageCap.toFloat(), { v -> vm.updateSettings { it.copy(pageCap = (v.toInt() / 10 * 10).coerceIn(10, 500)) } }, valueRange = 10f..500f)
            HorizontalDivider()
            OutlinedTextField(models, { models = it }, label = { Text("Gemini models only (optional, comma separated)") },
                supportingText = { Text("Leave empty to pick automatically from your key's model list. Found: " + st.discovered.keys.filter { "flash" in it }.take(6).joinToString().ifEmpty { "none yet (check a key)" }) },
                modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { vm.updateSettings { it.copy(models = models.trim()) } }, enabled = models.trim() != st.models) { Text("Save models") }
            HorizontalDivider()
            Button(onClick = onSelfTest, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Self-test") }
            Text("Runs the built-in test papers on this phone and shows what works. No key is used.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("PaperDeck ${BuildConfig.VERSION_NAME}. No account, no ads, no tracking. Your papers stay on this phone; only pages the phone cannot read are sent, with your own key, to the AI service you chose (Gemini, Groq, NVIDIA, …).",
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
