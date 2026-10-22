@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.papertoslides.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.papertoslides.core.geometry.SlideLayout
import app.papertoslides.core.project.Item
import app.papertoslides.core.project.Project
import app.papertoslides.core.project.QaReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ReviewScreen(vm: MainViewModel, id: String, onBack: () -> Unit) {
    LaunchedEffect(id) { vm.openReview(id) }
    val st by vm.review.collectAsState()
    var open by remember { mutableStateOf<Item?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val p = st.project?.takeIf { it.id == id }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(p?.name ?: "Slides", maxLines = 1) }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            })
        },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (p != null) Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(12.dp)) {
                    st.busy?.let { Text(it, style = MaterialTheme.typography.bodySmall); LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val r = vm.saveToDownloads(vm.deckFile(p), MIME_PPTX)
                                    snack.showSnackbar(r.fold({ "Saved to Downloads as $it" }, { it.message ?: "Could not save" }))
                                }
                            },
                            enabled = st.busy == null, modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        ) { Text("Save to Downloads") }
                        OutlinedButton(
                            onClick = { ctx.startActivity(Intent.createChooser(vm.shareIntent(vm.deckFile(p), MIME_PPTX), "Share slides")) },
                            enabled = st.busy == null, modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        ) { Text("Share") }
                    }
                }
            }
        },
    ) { pad ->
        if (p == null) { CenterNote(st.error ?: "Opening…"); return@Scaffold }
        LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.fillMaxSize().padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) { Summary(vm, p, st.busy == null, onQa = {
                ctx.startActivity(Intent.createChooser(vm.shareIntent(vm.qaFile(p), "text/plain"), "Share QA report"))
            }) }
            st.error?.let { e -> item(span = { GridItemSpan(maxLineSpan) }) { Text(e, color = MaterialTheme.colorScheme.error) } }
            items(p.items, key = { it.id + st.version }) { it -> QuestionCard(vm, p, it, p.items.indexOf(it) + 1) { open = it } }
        }
    }
    open?.let { item -> Preview(vm, p ?: return@let, item, onClose = { open = null }) }
}

@Composable
private fun Summary(vm: MainViewModel, p: Project, enabled: Boolean, onQa: () -> Unit) {
    Column {
        val flagged = p.items.count { it.flags.any { f -> f in WARN_FLAGS } }
        Text("${p.items.size} questions" + if (flagged > 0) " · $flagged to check" else "", style = MaterialTheme.typography.titleMedium)
        val ig = p.integrity
        if (ig != null && !ig.ok) Text("Numbering check: " + ig.reasons.joinToString("; "), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium)
        Text("Read on the phone: ${p.pages.count { it.tier?.name?.startsWith("T0") == true || it.tier?.name?.startsWith("T1") == true }} of ${p.pages.size} pages · AI pages: ${p.stats.apiPages}",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Layout", style = MaterialTheme.typography.titleSmall)
            for (l in SlideLayout.entries) FilterChip(selected = p.settings.layout == l, enabled = enabled, onClick = { if (p.settings.layout != l) vm.setLayout(l) },
                label = { Text(if (l == SlideLayout.TOP) "Top" else "Top right") }, modifier = Modifier.heightIn(min = 48.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = p.settings.blankAfter, enabled = enabled, onClick = { vm.setBlankAll(!p.settings.blankAfter) },
                label = { Text("Empty slide after each") }, modifier = Modifier.heightIn(min = 48.dp))
            TextButton(onClick = onQa, modifier = Modifier.heightIn(min = 48.dp)) { Text("QA report") }
        }
    }
}

@Composable
private fun thumb(vm: MainViewModel, p: Project, it: Item, version: Any): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(null, p.rev, it.id, version) {
        value = withContext(Dispatchers.IO) {
            vm.thumbnail(p, it.id)?.let { f -> decodeSampled(f, 480)?.asImageBitmap() }
        }
    }
    return bmp
}

private fun decodeSampled(f: File, maxSide: Int): android.graphics.Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
    return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
}

@Composable
private fun QuestionCard(vm: MainViewModel, p: Project, it: Item, n: Int, onOpen: () -> Unit) {
    val img = thumb(vm, p, it, p.settings.layout)
    Card(Modifier.fillMaxWidth().clickable(onClickLabel = "Preview question ${it.label}", onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().height(110.dp).background(androidx.compose.ui.graphics.Color.White), contentAlignment = Alignment.TopStart) {
            if (img != null) Image(img, contentDescription = "Question ${it.label}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
        }
        Column(Modifier.padding(8.dp)) {
            Text("Slide $n · Q ${it.label}", style = MaterialTheme.typography.labelLarge)
            val flags = it.flags.filter { f -> f in FLAG_LABELS }
            if (flags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (f in flags) Text(FLAG_LABELS[f]!!, style = MaterialTheme.typography.labelSmall,
                    color = if (f in WARN_FLAGS) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.background(if (f in WARN_FLAGS) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.shapes.small).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun Preview(vm: MainViewModel, p: Project, item: Item, onClose: () -> Unit) {
    val img = thumb(vm, p, item, p.settings.layout)
    val idx = p.items.indexOfFirst { it.id == item.id }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Question ${item.label}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SlideDrawing(p.settings.layout, picture = img, description = "Slide preview of question ${item.label}")
                val flags = item.flags.mapNotNull { QaReport.FLAG_TEXT[it] }
                if (flags.isNotEmpty()) Text("Note: " + flags.joinToString(", "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                item.displayPt?.let { Text("Text size on the slide: about ${it.toInt()} pt", style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.move(item.id, -1); onClose() }, enabled = idx > 0) { Text("Move up") }
                    OutlinedButton(onClick = { vm.move(item.id, +1); onClose() }, enabled = idx < p.items.size - 1) { Text("Move down") }
                }
                TextButton(onClick = { vm.toggleBlank(item.id); onClose() }) {
                    Text(if (p.blankAfter(item)) "Remove the empty slide after this one" else "Add an empty slide after this one")
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = { TextButton(onClick = { vm.deleteItem(item.id); onClose() }) { Text("Delete slide", color = MaterialTheme.colorScheme.error) } },
    )
}

const val MIME_PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
