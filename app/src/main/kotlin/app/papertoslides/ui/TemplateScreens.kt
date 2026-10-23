@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.papertoslides.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.papertoslides.core.geometry.RectIn
import app.papertoslides.core.geometry.SLIDE_H
import app.papertoslides.core.geometry.SLIDE_W
import app.papertoslides.core.geometry.SlideLayout
import app.papertoslides.core.template.DeckTheme
import app.papertoslides.core.template.ElementKind
import app.papertoslides.core.template.SlideTemplate
import app.papertoslides.core.template.TemplateElement
import java.util.UUID
import kotlin.math.roundToInt

val SWATCHES = listOf("#1F3A93", "#0B6E4F", "#B3261E", "#6A1B9A", "#E65100", "#00838F", "#212121", "#37474F", "#795548", "#FFC107", "#F5F5F5", "#FFFFFF")

@Composable
private fun Bar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) = TopAppBar(
    title = { Text(title, maxLines = 1) },
    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
    actions = { actions() },
)

/** The saved slide designs. */
@Composable
fun TemplatesScreen(vm: MainViewModel, onBack: () -> Unit, onEdit: (String?) -> Unit) {
    val list by vm.templates.collectAsState()
    val st by vm.settings.collectAsState()
    var confirm by remember { mutableStateOf<SlideTemplate?>(null) }
    Scaffold(topBar = { Bar("Slide templates", onBack) }, bottomBar = {
        Button(onClick = { onEdit(null) }, modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 56.dp)) { Text("New template") }
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("A template puts your institute's name, strips and logo on every slide and fixes where the question goes. It is saved for all future decks.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            items(list, key = { it.id }) { t ->
                val images = rememberImages(t.elements.mapNotNull { it.image }.map { vm.templateImage(it) })
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        SlidePreview(t, DeckTheme.LIGHT, layout = st.layout, images = images, description = "Preview of template ${t.name}")
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(t.name, style = MaterialTheme.typography.titleMedium)
                                if (st.templateId == t.id) Text("Used for new decks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            if (st.templateId != t.id) TextButton(onClick = { vm.updateSettings { it.copy(templateId = t.id) } }) { Text("Use") }
                            if (t.id != SlideTemplate.PLAIN.id) {
                                TextButton(onClick = { onEdit(t.id) }) { Text("Edit") }
                                TextButton(onClick = { confirm = t }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }
    confirm?.let { t ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Delete ${t.name}?") },
            text = { Text("Decks already made keep their design.") },
            confirmButton = { TextButton(onClick = { vm.deleteTemplate(t.id); confirm = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep") } })
    }
}

private const val SNAP = 0.05

private fun snap(v: Double) = (v / SNAP).roundToInt() * SNAP

/** Clamp to the slide, snap to a 0.05 in grid and to the slide edges. */
private fun fit(r: RectIn, minW: Double = 0.3, minH: Double = 0.15): RectIn {
    var w = snap(r.w).coerceIn(minW, SLIDE_W); var h = snap(r.h).coerceIn(minH, SLIDE_H)
    var x = snap(r.x).coerceIn(0.0, SLIDE_W - w); var y = snap(r.y).coerceIn(0.0, SLIDE_H - h)
    if (x < 0.12) x = 0.0
    if (y < 0.12) y = 0.0
    if (SLIDE_W - (x + w) < 0.12) { if (x == 0.0) w = SLIDE_W else x = SLIDE_W - w }
    if (SLIDE_H - (y + h) < 0.12) { if (y == 0.0) h = SLIDE_H else y = SLIDE_H - h }
    return RectIn(x, y, w, h)
}

/**
 * Template designer: drag the elements and the question-area placeholder on a to-scale slide, resize them with
 * the corner handle, fine-tune with the arrows and sliders. "Quick setup" builds the common case in one step.
 */
@Composable
fun TemplateEditorScreen(vm: MainViewModel, id: String?, onDone: () -> Unit) {
    val start = remember(id) {
        id?.let { vm.template(it) }?.takeIf { it.id != SlideTemplate.PLAIN.id }
            ?: SlideTemplate("t" + UUID.randomUUID().toString().take(8), "My institute")
    }
    var t by remember { mutableStateOf(start) }
    val undo = remember { mutableStateListOf<SlideTemplate>() }
    var selected by remember { mutableStateOf<String?>("area") }
    var dark by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf(id == null) }
    var useForNew by remember { mutableStateOf(id == null) }
    val st by vm.settings.collectAsState()
    fun edit(n: SlideTemplate) { undo += t; if (undo.size > 40) undo.removeAt(0); t = n }
    fun editEl(eid: String, f: (TemplateElement) -> TemplateElement) = edit(t.copy(elements = t.elements.map { if (it.id == eid) f(it) else it }))
    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importLogo(uri, t.id) { name ->
            val e = TemplateElement("logo" + System.currentTimeMillis() % 100000, ElementKind.IMAGE, RectIn(SLIDE_W - 1.1, 0.0, 1.0, 0.9), image = name, fill = null)
            edit(t.copy(elements = t.elements + e)); selected = e.id
        }
    }
    val images = rememberImages(t.elements.mapNotNull { it.image }.map { vm.templateImage(it) })

    Scaffold(topBar = {
        Bar(if (id == null) "New template" else "Edit template", onDone) {
            IconButton(onClick = { if (undo.isNotEmpty()) { t = undo.removeAt(undo.size - 1) } }, enabled = undo.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
            }
            TextButton(onClick = { vm.saveTemplate(t, useForNew); onDone() }) { Text("Save") }
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            OutlinedTextField(t.name, { t = t.copy(name = it.take(40)) }, label = { Text("Template name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            EditorCanvas(t, dark, selected, images, layout = st.layout, onSelect = { selected = it },
                onMove = { eid, r -> if (eid == "area") edit(t.copy(questionArea = fit(r, 3.0, 2.0))) else editEl(eid) { it.copy(rect = fit(r)) } })
            Text("Drag to move. Drag the round handle to resize. The blue box is where questions go (${if (st.layout == SlideLayout.TOP) "top half" else "top-right quarter"} of it).",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
            if (t.overlaps().isNotEmpty()) Text("Some elements overlap the question area and may cover a question.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { quick = true }, label = { Text("Quick setup") })
                AssistChip(onClick = {
                    val e = TemplateElement("s" + System.currentTimeMillis() % 100000, ElementKind.STRIP, RectIn(0.0, 0.0, SLIDE_W, 0.6), "Institute name", 24.0)
                    edit(t.copy(elements = t.elements + e)); selected = e.id
                }, label = { Text("+ Top strip") })
                AssistChip(onClick = {
                    val e = TemplateElement("s" + System.currentTimeMillis() % 100000, ElementKind.STRIP, RectIn(0.0, SLIDE_H - 0.45, SLIDE_W, 0.45), "Address · Phone", 14.0, bold = false)
                    edit(t.copy(elements = t.elements + e)); selected = e.id
                }, label = { Text("+ Bottom strip") })
                AssistChip(onClick = {
                    val e = TemplateElement("x" + System.currentTimeMillis() % 100000, ElementKind.TEXT, RectIn(0.3, 0.8, 4.0, 0.5), "Text", 18.0, bold = false, textColor = "#1F3A93", fill = null, align = "left")
                    edit(t.copy(elements = t.elements + e)); selected = e.id
                }, label = { Text("+ Text") })
                AssistChip(onClick = { pickLogo.launch("image/*") }, label = { Text("+ Logo") })
                FilterChip(selected = dark, onClick = { dark = !dark }, label = { Text("Dark preview") })
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            val sel = t.elements.firstOrNull { it.id == selected }
            if (selected == "area" || sel == null) AreaPanel(t) { edit(t.copy(questionArea = fit(it, 3.0, 2.0))) }
            else ElementPanel(sel, onChange = { n -> editEl(sel.id) { n.copy(rect = fit(n.rect)) } }, onDelete = {
                edit(t.copy(elements = t.elements.filter { it.id != sel.id })); selected = "area"
            })
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Background", style = MaterialTheme.typography.titleSmall)
            Text("Light slides", style = MaterialTheme.typography.bodySmall)
            Swatches(t.lightBg, listOf("#FFFFFF", "#FFFDF5", "#F4F7FB", "#F5F5F5")) { c -> if (c != null) edit(t.copy(lightBg = c)) }
            Text("Dark slides", style = MaterialTheme.typography.bodySmall)
            Swatches(t.darkBg, listOf("#16181D", "#000000", "#0D1B2A", "#1B2B22")) { c -> if (c != null) edit(t.copy(darkBg = c)) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { useForNew = !useForNew }) {
                Text("Use this template for new decks", Modifier.weight(1f)); Switch(useForNew, null)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (quick) QuickSetupDialog(onDismiss = { quick = false }) { name, line2, colour, right ->
        val q = SlideTemplate.quickSetup(t.id, t.name, name, line2, colour, rightMarginIn = right)
        edit(q.copy(elements = q.elements + t.elements.filter { it.kind == ElementKind.IMAGE }, lightBg = t.lightBg, darkBg = t.darkBg))
        quick = false; selected = "area"
    }
}

@Composable
private fun EditorCanvas(
    t: SlideTemplate, dark: Boolean, selected: String?, images: Map<String, androidx.compose.ui.graphics.ImageBitmap>, layout: SlideLayout,
    onSelect: (String) -> Unit, onMove: (String, RectIn) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio((SLIDE_W / SLIDE_H).toFloat())) {
        val density = LocalDensity.current
        val kPx = with(density) { maxWidth.toPx() } / SLIDE_W.toFloat()
        SlidePreview(t, if (dark) DeckTheme.DARK else DeckTheme.LIGHT, layout = layout, images = images, description = "Slide being designed")
        // live rectangles (the dragged state is local so the drag is smooth; committed on drag end)
        val all = listOf("area" to t.questionArea) + t.elements.map { it.id to it.rect }
        for ((eid, rect0) in all) key(eid) {
            var live by remember(eid, rect0) { mutableStateOf(rect0) }
            val isSel = eid == selected
            val label = if (eid == "area") "Question area" else t.elements.firstOrNull { it.id == eid }?.let { it.text.ifBlank { if (it.kind == ElementKind.IMAGE) "Logo" else "Text" } } ?: ""
            Box(
                Modifier
                    .offset { IntOffset((live.x * kPx).roundToInt(), (live.y * kPx).roundToInt()) }
                    .size(with(density) { (live.w * kPx).toFloat().toDp() }, with(density) { (live.h * kPx).toFloat().toDp() })
                    .border(BorderStroke(if (isSel) 2.dp else 1.dp, if (eid == "area") Color(0xFF2754C5) else if (isSel) Color(0xFFE65100) else Color.Transparent))
                    .semantics { contentDescription = "$label. Drag to move." }
                    .pointerInput(eid, rect0) {
                        detectDragGestures(
                            onDragStart = { onSelect(eid) },
                            onDragEnd = { onMove(eid, live) },
                            onDragCancel = { live = rect0 },
                        ) { change, d ->
                            change.consume()
                            live = live.copy(x = live.x + d.x / kPx, y = live.y + d.y / kPx)
                        }
                    }
                    .clickable { onSelect(eid) },
            ) {
                if (eid == "area") Text("Question area", fontSize = 10.sp, color = Color(0xFF2754C5), modifier = Modifier.padding(3.dp).alpha(0.9f))
            }
            if (isSel) {
                // resize handle at the bottom-right corner: 40 dp touch target
                val hs = 40.dp
                Box(
                    Modifier
                        .offset { IntOffset(((live.x + live.w) * kPx).roundToInt() - with(density) { (hs / 2).roundToPx() }, ((live.y + live.h) * kPx).roundToInt() - with(density) { (hs / 2).roundToPx() }) }
                        .size(hs)
                        .semantics { contentDescription = "Resize $label" }
                        .pointerInput(eid, rect0) {
                            detectDragGestures(onDragEnd = { onMove(eid, live) }, onDragCancel = { live = rect0 }) { change, d ->
                                change.consume()
                                live = live.copy(w = (live.w + d.x / kPx).coerceAtLeast(0.3), h = (live.h + d.y / kPx).coerceAtLeast(0.15))
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(16.dp).background(Color(0xFFE65100), CircleShape).border(2.dp, Color.White, CircleShape)) }
            }
        }
    }
}

/** Precise moves for small screens. */
@Composable
private fun Nudge(r: RectIn, onChange: (RectIn) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val s = 0.05
        for ((label, f) in listOf<Pair<String, (RectIn) -> RectIn>>(
            "←" to { it.copy(x = it.x - s) }, "→" to { it.copy(x = it.x + s) }, "↑" to { it.copy(y = it.y - s) }, "↓" to { it.copy(y = it.y + s) },
            "W−" to { it.copy(w = it.w - s) }, "W+" to { it.copy(w = it.w + s) }, "H−" to { it.copy(h = it.h - s) }, "H+" to { it.copy(h = it.h + s) },
        )) OutlinedButton(onClick = { onChange(f(r)) }, modifier = Modifier.heightIn(min = 44.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) { Text(label) }
    }
    Text("x %.2f  y %.2f  w %.2f  h %.2f in".format(r.x, r.y, r.w, r.h), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AreaPanel(t: SlideTemplate, onChange: (RectIn) -> Unit) {
    val a = t.questionArea
    Text("Question area (margins)", style = MaterialTheme.typography.titleSmall)
    @Composable fun margin(label: String, v: Double, max: Double, set: (Double) -> RectIn) {
        Text("$label: %.2f in".format(v), style = MaterialTheme.typography.bodySmall)
        Slider(v.toFloat(), { onChange(set(snap(it.toDouble()))) }, valueRange = 0f..max.toFloat())
    }
    margin("Top", a.y, 3.0) { a.copy(y = it, h = a.h + a.y - it) }
    margin("Bottom", SLIDE_H - a.y - a.h, 3.0) { a.copy(h = SLIDE_H - a.y - it) }
    margin("Left", a.x, 4.0) { a.copy(x = it, w = a.w + a.x - it) }
    margin("Right", SLIDE_W - a.x - a.w, 4.0) { a.copy(w = SLIDE_W - a.x - it) }
    Nudge(a, onChange)
}

@Composable
private fun ElementPanel(e: TemplateElement, onChange: (TemplateElement) -> Unit, onDelete: () -> Unit) {
    Text(when (e.kind) { ElementKind.STRIP -> "Strip"; ElementKind.TEXT -> "Text"; ElementKind.IMAGE -> "Logo" }, style = MaterialTheme.typography.titleSmall)
    if (e.kind != ElementKind.IMAGE) {
        OutlinedTextField(e.text, { onChange(e.copy(text = it.take(200))) }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth())
        Text("Size: ${e.fontPt.roundToInt()} pt", style = MaterialTheme.typography.bodySmall)
        Slider(e.fontPt.toFloat(), { onChange(e.copy(fontPt = it.roundToInt().toDouble())) }, valueRange = 8f..48f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = e.bold, onClick = { onChange(e.copy(bold = !e.bold)) }, label = { Text("Bold", fontWeight = FontWeight.Bold) })
            for ((a, l) in listOf("left" to "Left", "center" to "Centre", "right" to "Right")) FilterChip(selected = e.align == a, onClick = { onChange(e.copy(align = a)) }, label = { Text(l) })
        }
        Text("Text colour", style = MaterialTheme.typography.bodySmall)
        Swatches(e.textColor, SWATCHES) { c -> if (c != null) onChange(e.copy(textColor = c)) }
        Text("Strip colour", style = MaterialTheme.typography.bodySmall)
        Swatches(e.fill, SWATCHES, allowNone = true) { onChange(e.copy(fill = it)) }
    }
    Nudge(e.rect) { onChange(e.copy(rect = it)) }
    TextButton(onClick = onDelete) { Text("Remove this element", color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun Swatches(current: String?, colours: List<String>, allowNone: Boolean = false, onPick: (String?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (allowNone) Box(Modifier.size(40.dp).border(if (current == null) 3.dp else 1.dp, MaterialTheme.colorScheme.primary, CircleShape).clickable { onPick(null) }
            .semantics { contentDescription = "No colour" }, contentAlignment = Alignment.Center) { Text("∅") }
        for (c in colours) {
            Box(Modifier.size(40.dp).background(hexColor(c), CircleShape)
                .border(if (current.equals(c, true)) 3.dp else 1.dp, if (current.equals(c, true)) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                .clickable { onPick(c) }.semantics { contentDescription = "Colour $c" })
        }
    }
}

/** The client's case in one step: institute strip on top, contact strip at the bottom, space on the right. */
@Composable
private fun QuickSetupDialog(onDismiss: () -> Unit, onApply: (String, String, String, Double) -> Unit) {
    var name by remember { mutableStateOf("") }
    var line2 by remember { mutableStateOf("") }
    var colour by remember { mutableStateOf(SWATCHES[0]) }
    var right by remember { mutableStateOf(1.0f) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Quick setup") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Institute name (top strip)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(line2, { line2 = it.take(120) }, label = { Text("Bottom strip (address, phone…)") }, modifier = Modifier.fillMaxWidth())
            Text("Strip colour", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            Swatches(colour, SWATCHES.take(9)) { colour = it ?: colour }
            Text("Empty space on the right: %.1f in".format(right), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            Slider(right, { right = (it * 10).roundToInt() / 10f }, valueRange = 0f..4f)
            Text("You can drag and resize everything afterwards.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Start)
        }
    }, confirmButton = { TextButton(onClick = { onApply(name, line2, colour, right.toDouble()) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Skip") } })
}
