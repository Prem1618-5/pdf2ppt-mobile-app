@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.papertoslides.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.papertoslides.core.geometry.SlideLayout
import app.papertoslides.job.JobProgress
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(vm: MainViewModel, onProgress: () -> Unit, onReview: (String) -> Unit, onKeys: () -> Unit, onSettings: () -> Unit, onTemplates: () -> Unit) {
    val projects by vm.projects.collectAsState()
    val job by vm.job.collectAsState()
    val preparing by vm.preparing.collectAsState()
    val draft by vm.draft.collectAsState()
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.prepare(uris) }
    val running = job?.finished == false

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Paper to Slides") }, actions = {
                IconButton(onClick = onTemplates) { Icon(Icons.Filled.Dashboard, contentDescription = "Slide templates") }
                IconButton(onClick = onKeys) { Icon(Icons.Filled.Key, contentDescription = "Gemini keys") }
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
            })
        },
        bottomBar = {
            // primary action at the bottom: thumb reach
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                Button(
                    onClick = { pick.launch(arrayOf("application/pdf", "image/*")) },
                    enabled = !running && !preparing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                ) {
                    if (preparing) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text("Copying files…") }
                    else { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Choose paper", style = MaterialTheme.typography.titleMedium) }
                }
                Text("PDF or photos of pages. Pick several in order.", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.Center)
            }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            job?.let { j ->
                item { JobCard(j, onOpen = { if (j.finished && j.error == null && !j.cancelled) { vm.clearFinishedJob(); onReview(j.projectId) } else onProgress() }) }
            }
            if (projects.isEmpty() && job == null) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Slideshow, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text("Turn a question paper into slides", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text("One question per slide, with an empty slide after each for your working. Most papers are read on the phone, offline.",
                            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else if (projects.isNotEmpty()) {
                item { Text("Recent decks", style = MaterialTheme.typography.titleMedium) }
            }
            items(projects, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${p.name}") { onReview(p.id) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                            Text("${p.questions} questions" + (if (p.flagged > 0) " · ${p.flagged} to check" else "") + " · " +
                                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(p.createdAt)), style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { confirmDelete = p.id }) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${p.name}") }
                    }
                }
            }
        }
    }
    confirmDelete?.let { id ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Delete this deck?") },
            text = { Text("The slides and the copy of the paper on this phone are removed.") },
            confirmButton = { TextButton(onClick = { vm.deleteProject(id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } })
    }
    draft?.let { d -> OptionsSheet(vm, d, onStart = onProgress, onTemplates = onTemplates) }
}

@Composable
private fun JobCard(j: JobProgress, onOpen: () -> Unit) {
    val colors = if (j.error != null) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen), colors = colors) {
        Column(Modifier.padding(16.dp)) {
            Text(j.name, style = MaterialTheme.typography.titleMedium)
            Text(when {
                j.error != null -> j.error
                j.cancelled -> "Cancelled"
                j.finished -> "Ready. Tap to see your slides."
                else -> "${j.stage}…" + if (j.total > 0) " ${j.done}/${j.total}" else ""
            }, style = MaterialTheme.typography.bodyMedium)
            if (!j.finished) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

/** Options: the two layouts as cards that draw the slide, toggles, one primary button. */
@Composable
fun OptionsSheet(vm: MainViewModel, d: Draft, onStart: () -> Unit, onTemplates: () -> Unit) {
    val st by vm.settings.collectAsState()
    val templates by vm.templates.collectAsState()
    var theme by rememberSaveable { mutableStateOf(st.theme) }
    var style by rememberSaveable { mutableStateOf(st.style) }
    var templateId by rememberSaveable { mutableStateOf(st.templateId) }
    val template = templates.firstOrNull { it.id == templateId } ?: app.papertoslides.core.template.SlideTemplate.PLAIN
    val logos = rememberImages(template.elements.mapNotNull { it.image }.map { vm.templateImage(it) })
    var layout by rememberSaveable { mutableStateOf(st.layout) }
    var blank by rememberSaveable { mutableStateOf(st.blankAfter) }
    var watermark by rememberSaveable { mutableStateOf(st.removeWatermark) }
    var lang by rememberSaveable { mutableStateOf(st.lang) }
    var overCap by rememberSaveable { mutableStateOf(false) }
    val hasKeys = remember { vm.hasKeys() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val lowBattery = remember { app.papertoslides.job.Governor.lowBattery(ctx) }
    ModalBottomSheet(onDismissRequest = { vm.dismissDraft() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text(d.name, style = MaterialTheme.typography.titleLarge)
            Text("${d.files.size} file${if (d.files.size > 1) "s" else ""} · ${d.pages} page${if (d.pages != 1) "s" else ""}", style = MaterialTheme.typography.bodyMedium)
            d.problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(16.dp))
            Text("Where should the question go?", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (l in SlideLayout.entries) {
                    val sel = layout == l
                    Card(
                        Modifier.weight(1f).selectable(sel, role = Role.RadioButton) { layout = l },
                        border = BorderStroke(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            SlidePreview(template, theme, layout = l, images = logos, description = if (l == SlideLayout.TOP) "Top half of the slide" else "Top right quarter of the slide")
                            Spacer(Modifier.height(6.dp))
                            Text(if (l == SlideLayout.TOP) "Top" else "Top right", fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                            Text(if (l == SlideLayout.TOP) "Top half, full width" else "Top-right quarter", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Slide colours", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((t, label) in listOf(app.papertoslides.core.template.DeckTheme.LIGHT to "Light", app.papertoslides.core.template.DeckTheme.DARK to "Dark")) {
                    FilterChip(selected = theme == t, onClick = { theme = t }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            Text("Template", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in templates) FilterChip(selected = templateId == t.id, onClick = { templateId = t.id }, label = { Text(t.name, maxLines = 1) }, modifier = Modifier.heightIn(min = 48.dp))
                TextButton(onClick = onTemplates, modifier = Modifier.heightIn(min = 48.dp)) { Text("Design a template…") }
            }
            Text("Question on the slide", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            for ((v, label, hint) in listOf(
                Triple(app.papertoslides.core.project.QuestionStyle.AUTO, "Editable text (recommended)", "Text you can edit in PowerPoint, figures cut from the paper. Falls back to a picture when the text cannot be read reliably (complex maths, unclear scans)."),
                Triple(app.papertoslides.core.project.QuestionStyle.PICTURE, "Exact picture of the paper", "Pixel-exact; in dark slides the picture is recoloured."),
            )) {
                Row(Modifier.fillMaxWidth().selectable(style == v, role = Role.RadioButton) { style = v }.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    androidx.compose.material3.RadioButton(style == v, null)
                    Column(Modifier.padding(start = 8.dp)) { Text(label); Text(hint, style = MaterialTheme.typography.bodySmall) }
                }
            }
            ToggleRow("Empty slide after each question", blank) { blank = it }
            ToggleRow("Remove watermarks", watermark) { watermark = it }
            Text("Language of the paper", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((code, label) in listOf("en" to "English", "hi" to "Hindi", "both" to "Both")) {
                    FilterChip(selected = lang == code, onClick = { lang = code }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            if (d.pages > st.pageCap) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { overCap = !overCap }) {
                    Checkbox(overCap, { overCap = it })
                    Text("This paper has ${d.pages} pages (limit ${st.pageCap}). Process all of them? It will take longer and use more battery.")
                }
            }
            if (!hasKeys) Text("Works offline for most papers. Add a Gemini key for scans that need it.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            if (lowBattery) Text("Battery is low. Plug in the phone for long papers.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { vm.start(d, layout, blank, watermark, lang, overCap, theme, style, templateId); onStart() },
                enabled = d.pages > 0, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Make slides", style = MaterialTheme.typography.titleMedium) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun ToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(on, role = Role.Switch) { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(on, null)
    }
}

@Composable
fun ProgressScreen(vm: MainViewModel, onDone: (String) -> Unit, onHome: () -> Unit) {
    val j by vm.job.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(j?.finished) { while (j?.finished == false) { delay(1000); now = System.currentTimeMillis() } }
    Scaffold(topBar = { TopAppBar(title = { Text("Making slides") }) }) { pad ->
        val p = j
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            if (p == null) { Text("No job is running."); OutlinedButton(onClick = onHome) { Text("Back") }; return@Column }
            Text(p.name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(24.dp))
            Text(p.stage, style = MaterialTheme.typography.headlineMedium)
            if (p.total > 0 && !p.finished) Text("${p.done} of ${p.total}", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            if (!p.finished) {
                if (p.total > 0) LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth().height(8.dp))
                else LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp))
                val eta = p.etaSeconds(now)
                Text(if (eta != null) "About ${if (eta < 60) "$eta s" else "${eta / 60} min"} left for this step" else "Working out the time…",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                Text("Elapsed ${(now - p.startedAt) / 1000} s. You can leave the app; it keeps going.", style = MaterialTheme.typography.bodySmall)
            }
            p.message?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 8.dp)) }
            if (p.lanes.isNotEmpty()) {
                Text("Gemini keys", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (l in p.lanes) {
                        val until = l.untilMs?.let { u ->
                            if (l.state == "cooling") " ${((u - now) / 1000).coerceAtLeast(0)} s"
                            else " until " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(u))
                        } ?: ""
                        androidx.compose.material3.AssistChip(onClick = {}, label = { Text("${l.masked} · ${l.state}$until") })
                    }
                }
            }
            p.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp)) }
            p.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(24.dp))
            when {
                !p.finished -> OutlinedButton(onClick = { vm.cancelJob() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Cancel") }
                p.error == null && !p.cancelled -> Button(onClick = { vm.clearFinishedJob(); onDone(p.projectId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("See slides") }
                else -> Button(onClick = { vm.clearFinishedJob(); onHome() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Back to start") }
            }
        }
    }
}

@Composable
internal fun CenterNote(text: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp)) }
