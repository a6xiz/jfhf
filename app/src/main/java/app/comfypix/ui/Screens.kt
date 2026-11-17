@file:OptIn(ExperimentalMaterial3Api::class)

package app.comfypix.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.comfypix.AppViewModel
import app.comfypix.Conn
import app.comfypix.ResultImage
import app.comfypix.data.AuthMode
import app.comfypix.data.Wf
import app.comfypix.data.WorkflowDoc
import app.comfypix.data.decodeSampled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// ============================================================================ bottom bars

@Composable
fun NavBar(vm: AppViewModel) {
    NavigationBar {
        val items = listOf("🎨" to "Create", "🖼️" to "Results", "📂" to "Flows", "⚙️" to "Connect")
        items.forEachIndexed { i, item ->
            NavigationBarItem(
                selected = vm.tab == i,
                onClick = { vm.tab = i },
                icon = { Text(item.first, fontSize = 20.sp) },
                label = { Text(item.second) },
            )
        }
    }
}

@Composable
fun GenerateBar(vm: AppViewModel) {
    val run = vm.run
    Surface(
        shadowElevation = 8.dp,
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (run.running) {
                val bar = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                if (run.progress >= 0f) LinearProgressIndicator(progress = run.progress, modifier = bar)
                else LinearProgressIndicator(modifier = bar)
                Spacer(Modifier.height(6.dp))
                Text(run.status, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { vm.stop() },
                    colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                ) { Text("⏹  Stop", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            } else {
                Button(
                    onClick = { vm.generate() },
                    colors = ButtonDefaults.buttonColors(containerColor = Sun, contentColor = Navy),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                ) { Text("✨  Generate", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp) }
            }
        }
    }
}

// ============================================================================ create

@Composable
fun CreateScreen(vm: AppViewModel) {
    val doc = vm.current
    val an = vm.analysis
    val rev = vm.rev
    Column(Modifier.fillMaxSize()) {
        Header(vm, doc?.name ?: "ComfyPix") {
            if (doc != null) {
                IconButton(onClick = { vm.loadSchemas(true) }) { Text("🔄", fontSize = 20.sp) }
                IconButton(onClick = { vm.openLayoutEditor(doc.id) }) { Text("🎚️", fontSize = 20.sp) }
            }
        }
        if (doc == null || an == null) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📂", fontSize = 56.sp)
                Spacer(Modifier.height(12.dp))
                Text("No workflow yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Import one from the Flows tab.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.tab = 2 }, shape = RoundedCornerShape(20.dp)) { Text("Open Flows") }
            }
            return@Column
        }
        val shown = remember(doc.id, an, rev) {
            an.groups.map { g -> Pair(g, g.fields.filter { doc.isVisible(it) }) }.filter { it.second.isNotEmpty() }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { StatusBanners(vm) }
            items(shown, key = { it.first.nodeId }) { pair ->
                val g = pair.first
                Bubble {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(groupTitle(g), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        pair.second.forEach { f -> FieldEditor(vm, f) }
                    }
                }
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        "Every input is hidden. Tap 🎚️ to choose what shows up here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun StatusBanners(vm: AppViewModel) {
    val rev = vm.rev
    val doc = vm.current ?: return
    val c = vm.conn
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (c is Conn.Fail) Banner("Can’t reach ComfyUI. " + c.msg, "Fix") { vm.tab = 3 }
        if (vm.schemaNote.isNotEmpty() && c !is Conn.Fail) Banner(vm.schemaNote, "Retry") { vm.loadSchemas(true) }
        FilterChip(
            selected = doc.randomSeed,
            onClick = { vm.toggleRandomSeed() },
            label = { Text("🎲  New seed every run") },
        )
    }
}

// ============================================================================ results

@Composable
fun ResultsScreen(vm: AppViewModel) {
    val run = vm.run
    var viewing by remember { mutableStateOf<ResultImage?>(null) }
    Column(Modifier.fillMaxSize()) {
        Header(vm, "Results") {
            if (vm.results.isNotEmpty()) TextButton(onClick = { vm.clearResults() }) { Text("Clear") }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (run.running || run.error != null || run.status.isNotEmpty() || run.notes.isNotEmpty()) {
                item {
                    Bubble {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (run.status.isNotEmpty()) Text(run.status, fontWeight = FontWeight.Bold)
                            if (run.error != null) Text(run.error, color = Coral, style = MaterialTheme.typography.bodyMedium)
                            run.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            val prev = vm.preview
            if (run.running && prev != null) {
                item {
                    Image(
                        prev, null,
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)),
                        contentScale = ContentScale.FillWidth,
                    )
                }
            }
            if (vm.results.isEmpty() && !run.running) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🖼️", fontSize = 56.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Your pictures will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val cols = if (vm.results.size == 1) 1 else 2
            items(vm.results.chunked(cols)) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { r -> Thumb(r, Modifier.weight(1f)) { viewing = r } }
                    if (row.size < cols) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    viewing?.let { ViewerDialog(vm, it) { viewing = null } }
}

@Composable
fun Thumb(r: ResultImage, modifier: Modifier, onClick: () -> Unit) {
    val bmp by produceState<ImageBitmap?>(null, r.id) {
        value = withContext(Dispatchers.Default) { decodeSampled(r.bytes, 700) }
    }
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Text("🖼️", fontSize = 28.sp)
    }
}

@Composable
fun ViewerDialog(vm: AppViewModel, r: ResultImage, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bmp by produceState<ImageBitmap?>(null, r.id) {
        value = withContext(Dispatchers.Default) { decodeSampled(r.bytes, 2600) }
    }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val b = bmp
                    if (b != null) {
                        Image(
                            b, null,
                            Modifier.fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        scale = (scale * zoom).coerceIn(1f, 6f)
                                        offset = if (scale <= 1f) Offset.Zero else offset + pan
                                    }
                                }
                                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text("Loading…", color = Color.White)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        scope.launch {
                            val u = vm.saveToGallery(r)
                            vm.message = if (u != null) "Saved to Pictures/ComfyPix" else "Couldn’t save the image"
                        }
                    }, Modifier.weight(1f)) { Text("💾 Save") }
                    FilledTonalButton(onClick = {
                        scope.launch {
                            val u = vm.saveToGallery(r)
                            if (u != null) {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = r.mime
                                    putExtra(Intent.EXTRA_STREAM, u)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ctx.startActivity(Intent.createChooser(send, "Share image"))
                            }
                        }
                    }, Modifier.weight(1f)) { Text("📤 Share") }
                    FilledTonalButton(onClick = {
                        vm.results.remove(r)
                        onClose()
                    }, Modifier.weight(1f)) { Text("🗑️") }
                    OutlinedButton(onClick = onClose, Modifier.weight(1f)) { Text("Close") }
                }
            }
        }
    }
}

// ============================================================================ flows (workflows)

@Composable
fun WorkflowsScreen(vm: AppViewModel) {
    val clip = LocalClipboardManager.current
    var showServer by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<WorkflowDoc?>(null) }
    var deleting by remember { mutableStateOf<WorkflowDoc?>(null) }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFromUri(uri)
    }
    Column(Modifier.fillMaxSize()) {
        Header(vm, "Workflows")
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Bubble {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Import a workflow", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { fileLauncher.launch(arrayOf("*/*")) }, Modifier.weight(1f)) { Text("📄 File") }
                            FilledTonalButton(onClick = {
                                val t = clip.getText()?.text
                                if (t.isNullOrBlank()) vm.message = "The clipboard is empty" else vm.importFromText(t, "Pasted workflow")
                            }, Modifier.weight(1f)) { Text("📋 Paste") }
                            FilledTonalButton(onClick = { showServer = true }, Modifier.weight(1f)) { Text("🌐 Server") }
                        }
                        Text(
                            "Works with “Save (API Format)” files and with normal saved workflows. " +
                                "I detect the prompt, model, size, sampler and image inputs for you. " +
                                "Change what shows with the 🎚️ button.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (vm.busy.isNotEmpty()) Text(vm.busy, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (vm.importNotes.isNotEmpty()) {
                item {
                    Bubble(color = Sun.copy(alpha = 0.22f)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Heads up", fontWeight = FontWeight.Bold)
                            vm.importNotes.take(6).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                            TextButton(onClick = { vm.importNotes = emptyList() }) { Text("Dismiss") }
                        }
                    }
                }
            }
            items(vm.workflows, key = { it.id }) { w ->
                val active = w.id == vm.currentId
                Bubble(
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    onClick = {
                        vm.selectWorkflow(w.id)
                        vm.tab = 0
                    },
                ) {
                    Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                        Text(
                            (if (active) "✅  " else "") + w.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(w.api.length().toString() + " nodes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            TextButton(onClick = { vm.openLayoutEditor(w.id) }) { Text("🎚️ Layout") }
                            TextButton(onClick = { renaming = w }) { Text("Rename") }
                            TextButton(onClick = { deleting = w }) { Text("Delete", color = Coral) }
                        }
                    }
                }
            }
        }
    }
    if (showServer) ServerImportDialog(vm) { showServer = false }
    renaming?.let { w ->
        var name by remember(w.id) { mutableStateOf(w.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename workflow") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, shape = RoundedCornerShape(16.dp)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameWorkflow(w.id, name)
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { w ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “" + w.name + "”?") },
            text = { Text("This removes it from the app only. Nothing on your server changes.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteWorkflow(w.id)
                    deleting = null
                }) { Text("Delete", color = Coral) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ServerImportDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var state by remember { mutableStateOf<Result<List<String>>?>(null) }
    LaunchedEffect(Unit) { state = vm.listServerWorkflows() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Workflows on your server") },
        text = {
            val s = state
            val list = s?.getOrNull()
            when {
                s == null -> Text("Loading…")
                s.isFailure -> Text("Couldn’t list workflows. " + (s.exceptionOrNull()?.message ?: ""))
                list.isNullOrEmpty() -> Text("No saved workflows found in ComfyUI’s workflows folder.")
                else -> LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(list) { n ->
                        Text(
                            n.removeSuffix(".json"),
                            Modifier.fillMaxWidth().clickable {
                                vm.importFromServer(n)
                                onDismiss()
                            }.padding(vertical = 14.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ============================================================================ layout editor

@Composable
fun LayoutEditorScreen(vm: AppViewModel) {
    val rev = vm.rev
    val doc = vm.current
    val an = vm.analysis
    var q by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.layoutEditorFor = null }) { Text("←  Back", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.resetLayout() }) { Text("Reset to auto") }
        }
        if (doc == null || an == null) return@Column
        Text(
            "What shows on the Create screen",
            Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
        )
        val total = an.groups.sumOf { it.fields.size }
        val on = an.groups.sumOf { g -> g.fields.count { doc.isVisible(it) } }
        Text(
            "$on of $total inputs shown. I picked the usual ones; switch any on or off.",
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            placeholder = { Text("Search nodes or inputs") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(18.dp),
        )
        val groups = an.groups.filter { g ->
            q.isBlank() || g.title.contains(q, true) || g.cls.contains(q, true) || g.fields.any { it.name.contains(q, true) }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(groups, key = { it.nodeId }) { g ->
                Bubble {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
                        Text(g.cat.emoji + "  " + g.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "#" + g.nodeId + " " + g.cls,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = { g.fields.forEach { vm.setVisible(it.key, true) } }) { Text("All") }
                            TextButton(onClick = { g.fields.forEach { vm.setVisible(it.key, false) } }) { Text("None") }
                        }
                        g.fields.forEach { f ->
                            val cur = doc.api.optJSONObject(f.nodeId)?.optJSONObject("inputs")?.opt(f.name)
                            Row(
                                Modifier.fillMaxWidth().clickable { vm.setVisible(f.key, !doc.isVisible(f)) }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(pretty(f.name))
                                    Text(
                                        Wf.valueText(cur).replace('\n', ' '),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Switch(checked = doc.isVisible(f), onCheckedChange = { vm.setVisible(f.key, it) })
                            }
                        }
                    }
                }
            }
            item {
                Text("Outputs", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Choose which results are shown after a run.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (an.outputs.isEmpty()) {
                item { Text("No output nodes found, so every image the server returns is shown.") }
            }
            items(an.outputs, key = { "out" + it.nodeId }) { o ->
                val shown = doc.outputVis[o.nodeId] ?: true
                Bubble {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("📤  " + o.title, fontWeight = FontWeight.Bold)
                            Text("#" + o.nodeId + " " + o.cls, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = shown, onCheckedChange = { vm.setOutputVisible(o.nodeId, it) })
                    }
                }
            }
        }
    }
}

// ============================================================================ connection settings

@Composable
fun NumField(label: String, value: Int, resetKey: Int, suffix: String = "", modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    var t by remember(resetKey) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = t,
        onValueChange = { s ->
            t = s.filter { it.isDigit() }.take(9)
            t.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
    )
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val saved = vm.settings
    var draft by remember(saved) { mutableStateOf(saved) }
    var resetKey by remember { mutableIntStateOf(0) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var showPass by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dirty = draft != saved
    val field = RoundedCornerShape(16.dp)
    LaunchedEffect(saved) { resetKey++ }

    Column(Modifier.fillMaxSize()) {
        Header(vm, "Connection")
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Section("🌐  Server") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Use HTTPS", Modifier.weight(1f))
                        Switch(checked = draft.https, onCheckedChange = { draft = draft.copy(https = it) })
                    }
                    OutlinedTextField(
                        value = draft.host,
                        onValueChange = { draft = draft.copy(host = it) },
                        label = { Text("Host or IP address") },
                        supportingText = { Text("Example: 192.168.1.20. Start ComfyUI with --listen so your phone can reach it.") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                        shape = field,
                    )
                    NumField("Port", draft.port, resetKey, modifier = Modifier.fillMaxWidth()) { draft = draft.copy(port = it) }
                    OutlinedTextField(
                        value = draft.basePath,
                        onValueChange = { draft = draft.copy(basePath = it) },
                        label = { Text("Base path (optional)") },
                        supportingText = { Text("Only if ComfyUI sits behind a reverse proxy, e.g. /comfy") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = field,
                    )
                }
            }
            item {
                Section("🔑  Sign-in") {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AuthMode.values().forEach { m ->
                            FilterChip(selected = draft.auth == m, onClick = { draft = draft.copy(auth = m) }, label = { Text(m.label) })
                        }
                    }
                    when (draft.auth) {
                        AuthMode.NONE -> Text("No login. Fine for your home network.", style = MaterialTheme.typography.bodySmall)
                        AuthMode.BASIC -> {
                            OutlinedTextField(
                                value = draft.username,
                                onValueChange = { draft = draft.copy(username = it) },
                                label = { Text("Username") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = field,
                            )
                            OutlinedTextField(
                                value = draft.password,
                                onValueChange = { draft = draft.copy(password = it) },
                                label = { Text("Password") },
                                singleLine = true,
                                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = { TextButton(onClick = { showPass = !showPass }) { Text(if (showPass) "Hide" else "Show") } },
                                modifier = Modifier.fillMaxWidth(),
                                shape = field,
                            )
                        }
                        AuthMode.BEARER -> OutlinedTextField(
                            value = draft.token,
                            onValueChange = { draft = draft.copy(token = it) },
                            label = { Text("Token") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = field,
                        )
                        AuthMode.HEADER -> {
                            OutlinedTextField(
                                value = draft.headerName,
                                onValueChange = { draft = draft.copy(headerName = it) },
                                label = { Text("Header name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = field,
                            )
                            OutlinedTextField(
                                value = draft.headerValue,
                                onValueChange = { draft = draft.copy(headerValue = it) },
                                label = { Text("Header value") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = field,
                            )
                        }
                    }
                }
            }
            item {
                Section("📡  Live updates") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Use WebSocket")
                            Text(
                                "Shows live progress and preview images while generating. Results still arrive if it is off.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = draft.useWebSocket, onCheckedChange = { draft = draft.copy(useWebSocket = it) })
                    }
                    NumField("Check for results every", draft.pollIntervalMs, resetKey, "ms", Modifier.fillMaxWidth()) {
                        draft = draft.copy(pollIntervalMs = it)
                    }
                }
            }
            item {
                Section("⏱️  Timeouts and security") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumField("Connect", draft.connectTimeoutSec, resetKey, "s", Modifier.weight(1f)) { draft = draft.copy(connectTimeoutSec = it) }
                        NumField("Read", draft.readTimeoutSec, resetKey, "s", Modifier.weight(1f)) { draft = draft.copy(readTimeoutSec = it) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Accept self-signed certificates")
                            Text(
                                "Only for your own server on HTTPS.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = draft.trustAllCerts, onCheckedChange = { draft = draft.copy(trustAllCerts = it) })
                    }
                }
            }
            item {
                Section("🧾  Advanced") {
                    OutlinedTextField(
                        value = draft.clientId,
                        onValueChange = { draft = draft.copy(clientId = it.trim()) },
                        label = { Text("Client ID") },
                        supportingText = { Text("Identifies this phone to ComfyUI.") },
                        trailingIcon = { TextButton(onClick = { draft = draft.copy(clientId = UUID.randomUUID().toString()) }) { Text("New") } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = field,
                    )
                    NumField("Pictures kept in Results", draft.maxResults, resetKey, modifier = Modifier.fillMaxWidth()) {
                        draft = draft.copy(maxResults = it)
                    }
                }
            }
            item {
                Bubble {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    testing = true
                                    testResult = vm.test(draft)
                                    testing = false
                                }
                            },
                            enabled = !testing && draft.host.isNotBlank(),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (testing) "Testing…" else "🔌  Test these settings") }
                        val r = testResult
                        if (r != null) {
                            Text(
                                (if (r.first) "✅  " else "❌  ") + r.second,
                                color = if (r.first) Mint else Coral,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
        Surface(shadowElevation = 8.dp, tonalElevation = 3.dp, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    if (dirty) "You have unsaved changes" else "All changes saved",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (dirty) Coral else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            draft = saved
                            resetKey++
                            testResult = null
                        },
                        enabled = dirty,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.weight(1f).height(50.dp),
                    ) { Text("Discard") }
                    Button(
                        onClick = {
                            if (draft.host.isBlank()) vm.message = "Enter a host or IP address first" else vm.saveSettings(draft)
                        },
                        enabled = dirty,
                        colors = ButtonDefaults.buttonColors(containerColor = Sun, contentColor = Navy),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.weight(2f).height(50.dp),
                    ) { Text("💾  Save settings", fontWeight = FontWeight.ExtraBold) }
                }
            }
        }
    }
}
