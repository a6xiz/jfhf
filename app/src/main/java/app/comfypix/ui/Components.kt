@file:OptIn(ExperimentalMaterial3Api::class)

package app.comfypix.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.comfypix.AppViewModel
import app.comfypix.Conn
import app.comfypix.data.Cat
import app.comfypix.data.Field
import app.comfypix.data.Kind
import app.comfypix.data.NodeGroup

fun pretty(n: String): String = n.replace('_', ' ').replaceFirstChar { it.uppercase() }

fun fmtNum(v: Any?, isInt: Boolean): String = when {
    v == null -> ""
    isInt && v is Number -> v.toLong().toString()
    v is Double -> java.math.BigDecimal(v.toString()).toPlainString()
    else -> v.toString()
}

// ----------------------------------------------------------------------------- layout bits

@Composable
fun Bubble(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = color,
            tonalElevation = 1.dp,
            shadowElevation = 2.dp,
        ) { Column(content = content) }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = color,
            tonalElevation = 1.dp,
            shadowElevation = 2.dp,
        ) { Column(content = content) }
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Bubble {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
fun Header(vm: AppViewModel, title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ConnLine(vm)
        }
        actions()
    }
}

@Composable
fun ConnLine(vm: AppViewModel) {
    val c = vm.conn
    val (color, text) = when (c) {
        is Conn.Ok -> Pair(Mint, c.info)
        is Conn.Checking -> Pair(Color.Gray, "Checking connection…")
        is Conn.Fail -> Pair(Coral, "Not connected. " + c.msg)
        is Conn.Unknown -> Pair(Color.Gray, "Not checked yet")
    }
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable { if (c is Conn.Fail) vm.tab = 3 else vm.checkConnection() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun Banner(text: String, action: String, onAction: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = Coral.copy(alpha = 0.16f)) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onAction) { Text(action, fontWeight = FontWeight.Bold) }
        }
    }
}

// ----------------------------------------------------------------------------- picker

@Composable
fun PickerDialog(title: String, options: List<String>, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val shown = remember(options, current, q) {
        val base = if (current.isNotEmpty() && current !in options) listOf(current) + options else options
        if (q.isBlank()) base else base.filter { it.contains(q.trim(), ignoreCase = true) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 12.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = q,
                    onValueChange = { q = it },
                    placeholder = { Text("Search " + options.size + " options") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(shown) { o ->
                        val sel = o == current
                        val missing = sel && o !in options
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { onPick(o) }
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(o, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            if (sel) Text(if (missing) "⚠ not on server" else "✓", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
            }
        }
    }
}

// ----------------------------------------------------------------------------- field editors

fun groupTitle(g: NodeGroup): String {
    val generic = g.title.isBlank() || g.title == g.cls || g.title.startsWith("CLIP Text Encode")
    val t = if ((g.cat == Cat.POSITIVE || g.cat == Cat.NEGATIVE) && generic) g.cat.label else g.title
    return g.cat.emoji + "  " + t
}

@Composable
fun FieldEditor(vm: AppViewModel, f: Field) {
    val rev = vm.rev // re-read the workflow JSON after any edit
    val doc = vm.current ?: return
    val value: Any? = doc.api.optJSONObject(f.nodeId)?.optJSONObject("inputs")?.opt(f.name)
    when (f.kind) {
        Kind.STRING -> StringField(vm, f, value?.toString().orEmpty())
        Kind.INT, Kind.FLOAT -> NumberField(vm, f, value)
        Kind.BOOL -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(pretty(f.name), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = value == true, onCheckedChange = { vm.setField(f.nodeId, f.name, it) })
        }
        Kind.COMBO -> ComboField(vm, f, value?.toString().orEmpty())
        Kind.IMAGE -> ImageField(vm, f, value?.toString().orEmpty())
    }
}

@Composable
fun StringField(vm: AppViewModel, f: Field, value: String, hint: String? = null) {
    var text by remember(vm.currentId, f.key, vm.extRev) { mutableStateOf(value) }
    val multiline = f.spec?.multiline == true || f.name == "text" || f.name == "prompt" || value.contains('\n') || value.length > 60
    val isPrompt = f.cat == Cat.POSITIVE || f.cat == Cat.NEGATIVE
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            vm.setField(f.nodeId, f.name, it)
        },
        label = { Text(if (isPrompt && f.name == "text") f.cat.label else pretty(f.name)) },
        supportingText = if (hint != null) ({ Text(hint) }) else null,
        modifier = Modifier.fillMaxWidth(),
        minLines = if (multiline) 3 else 1,
        maxLines = if (multiline) 10 else 3,
        shape = RoundedCornerShape(16.dp),
    )
}

@Composable
fun NumberField(vm: AppViewModel, f: Field, value: Any?) {
    val isInt = f.kind == Kind.INT
    var text by remember(vm.currentId, f.key, vm.extRev) { mutableStateOf(fmtNum(value, isInt)) }
    val parsed: Double? = if (isInt) text.toLongOrNull()?.toDouble() else text.toDoubleOrNull()
    val min = f.spec?.min
    val max = f.spec?.max
    val outOfRange = parsed != null && ((min != null && parsed < min) || (max != null && parsed > max))
    val isSeed = f.name == "seed" || f.name == "noise_seed"
    val showSlider = min != null && max != null && max > min && (max - min) <= 100 && !isSeed

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { s ->
                    text = s
                    if (isInt) {
                        s.toLongOrNull()?.let { vm.setField(f.nodeId, f.name, it) }
                    } else {
                        s.toDoubleOrNull()?.let { vm.setField(f.nodeId, f.name, it) }
                    }
                },
                label = { Text(pretty(f.name)) },
                singleLine = true,
                isError = parsed == null || outOfRange,
                supportingText = if (outOfRange) ({ Text("Allowed: $min to $max") }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = if (isInt) KeyboardType.Number else KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
            )
            if (isSeed) {
                TextButton(onClick = { vm.newSeed(f.nodeId, f.name) }) { Text("🎲", fontSize = 24.sp) }
            }
        }
        if (showSlider && min != null && max != null) {
            val step = f.spec?.step ?: 0.0
            Slider(
                value = (parsed ?: min).toFloat().coerceIn(min.toFloat(), max.toFloat()),
                onValueChange = { x ->
                    var v = x.toDouble()
                    if (isInt) v = Math.round(v).toDouble() else if (step > 0) v = Math.round(v / step) * step
                    v = Math.round(v * 1000) / 1000.0
                    vm.setField(f.nodeId, f.name, if (isInt) v.toLong() else v, external = true)
                },
                valueRange = min.toFloat()..max.toFloat(),
                steps = if (isInt) ((max - min).toInt() - 1).coerceAtLeast(0) else 0,
            )
        }
        if (f.name == "width" || f.name == "height") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(512, 768, 1024, 1536).forEach { s ->
                    AssistChip(onClick = { vm.setField(f.nodeId, f.name, s.toLong(), external = true) }, label = { Text("$s") })
                }
            }
        }
    }
}

@Composable
fun ComboField(vm: AppViewModel, f: Field, value: String) {
    val options = f.spec?.options
    var open by remember { mutableStateOf(false) }
    if (options.isNullOrEmpty()) {
        // The list couldn't be loaded: never leave the person stuck, let them type the name.
        StringField(vm, f, value, hint = "Options not loaded. Type the exact name, or tap 🔄 above to reload.")
        return
    }
    val missing = value.isNotEmpty() && value !in options
    Surface(
        onClick = { open = true },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(pretty(f.name), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    value.ifEmpty { "Select…" },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("▾", fontSize = 18.sp)
            }
            if (missing) {
                Text("⚠ Not found on the server. Tap to choose another.", style = MaterialTheme.typography.labelMedium, color = Coral)
            }
        }
    }
    if (open) {
        PickerDialog(
            title = pretty(f.name),
            options = options,
            current = value,
            onPick = {
                vm.setCombo(f.nodeId, f.name, it)
                open = false
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
fun ImageField(vm: AppViewModel, f: Field, current: String) {
    val options = f.spec?.options.orEmpty()
    var open by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.uploadImage(uri, f.nodeId, f.name)
    }
    val thumb by produceState<ImageBitmap?>(null, current, vm.conn) { value = vm.inputThumb(current) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(92.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val t = thumb
            if (t != null) Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Text("🖼️", fontSize = 28.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(current.ifEmpty { "No image" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pick.launch("image/*") }, shape = RoundedCornerShape(14.dp)) { Text("Upload") }
                if (options.isNotEmpty()) {
                    OutlinedButton(onClick = { open = true }, shape = RoundedCornerShape(14.dp)) { Text("On server") }
                }
            }
        }
    }
    if (open) {
        PickerDialog(
            title = "Images on the server",
            options = options,
            current = current,
            onPick = {
                vm.setCombo(f.nodeId, f.name, it)
                open = false
            },
            onDismiss = { open = false },
        )
    }
}
