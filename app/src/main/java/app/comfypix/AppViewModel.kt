package app.comfypix

import android.app.Application
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.comfypix.data.Analysis
import app.comfypix.data.ComfyClient
import app.comfypix.data.ComfyException
import app.comfypix.data.ConnectionSettings
import app.comfypix.data.NodeSchema
import app.comfypix.data.OutFile
import app.comfypix.data.PromptRejected
import app.comfypix.data.STARTER_WORKFLOW
import app.comfypix.data.SettingsStore
import app.comfypix.data.UiConverter
import app.comfypix.data.Wf
import app.comfypix.data.WorkflowDoc
import app.comfypix.data.WorkflowStore
import app.comfypix.data.decodeSampled
import app.comfypix.data.describePromptError
import app.comfypix.data.entryError
import app.comfypix.data.friendlyError
import app.comfypix.data.isImageName
import app.comfypix.data.keyList
import app.comfypix.data.normalized
import app.comfypix.data.parseImageRef
import app.comfypix.data.parseNodeSchema
import app.comfypix.data.parseOutputs
import app.comfypix.data.parseTexts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

sealed interface Conn {
    object Unknown : Conn
    object Checking : Conn
    data class Ok(val info: String) : Conn
    data class Fail(val msg: String) : Conn
}

data class RunUi(
    val running: Boolean = false,
    val status: String = "",
    val progress: Float = -1f,
    val error: String? = null,
    val promptId: String? = null,
    val notes: List<String> = emptyList(),
)

class ResultImage(val id: Long, val bytes: ByteArray, val file: OutFile, val workflow: String) {
    var savedUri: Uri? = null
    val mime: String
        get() = when (file.filename.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/png"
        }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val wfStore = WorkflowStore(app)
    private val uiPrefs = app.getSharedPreferences("comfypix_ui", 0)

    var settings by mutableStateOf(settingsStore.load())
        private set
    var conn: Conn by mutableStateOf<Conn>(Conn.Unknown)
        private set

    val workflows = mutableStateListOf<WorkflowDoc>()
    var currentId by mutableStateOf<String?>(null)
        private set

    /** Bumped on every edit so screens re-read the (non-observable) workflow JSON. */
    var rev by mutableIntStateOf(0)
        private set

    /** Bumped only when values change from outside a text box (dice, import, switching workflow). */
    var extRev by mutableIntStateOf(0)
        private set

    val schemas = mutableStateMapOf<String, NodeSchema>()
    var schemaNote by mutableStateOf("")
        private set
    var analysis by mutableStateOf<Analysis?>(null)
        private set

    var tab by mutableIntStateOf(0)
    var layoutEditorFor by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf("")
        private set
    var importNotes by mutableStateOf<List<String>>(emptyList())

    var run by mutableStateOf(RunUi())
        private set
    var preview by mutableStateOf<ImageBitmap?>(null)
        private set
    val results = mutableStateListOf<ResultImage>()

    val current: WorkflowDoc? get() = workflows.firstOrNull { it.id == currentId }

    private var client = ComfyClient(settings)
    private var socket: WebSocket? = null
    private var socketGen = 0
    private var socketWanted = false
    private var runJob: Job? = null
    private var saveJob: Job? = null
    private var schemaJob: Job? = null
    private var runningApi: JSONObject? = null
    private var nextResultId = 1L
    private val thumbCache = HashMap<String, ImageBitmap>()

    init {
        workflows.addAll(wfStore.list())
        if (workflows.isEmpty()) {
            val d = WorkflowDoc(newId(), "Text to image", JSONObject(STARTER_WORKFLOW))
            wfStore.save(d)
            workflows.add(d)
        }
        currentId = uiPrefs.getString("current", null)?.takeIf { id -> workflows.any { it.id == id } }
            ?: workflows.first().id
        recomputeAnalysis()
        checkConnection()
        startSocket()
    }

    private fun newId() = UUID.randomUUID().toString().take(8)

    // ------------------------------------------------------------------ connection

    fun saveSettings(s: ConnectionSettings) {
        val n = s.normalized()
        settingsStore.save(n)
        settings = n
        stopSocket()
        client = ComfyClient(n)
        thumbCache.clear()
        schemas.clear()
        recomputeAnalysis()
        startSocket()
        checkConnection()
        message = "Settings saved"
    }

    fun checkConnection() {
        conn = Conn.Checking
        viewModelScope.launch {
            conn = try {
                Conn.Ok(describeStats(client.systemStats()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Conn.Fail(friendlyError(e))
            }
            if (conn is Conn.Ok) loadSchemas(false)
        }
    }

    /** Test the (possibly unsaved) settings from the form. */
    suspend fun test(c: ConnectionSettings): Pair<Boolean, String> {
        return try {
            val cl = ComfyClient(c.normalized())
            val info = describeStats(cl.systemStats())
            val models = try {
                cl.objectInfo("CheckpointLoaderSimple")
                    ?.let { parseNodeSchema("CheckpointLoaderSimple", it).inputs["ckpt_name"]?.options?.size }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val extra = if (models != null) " · $models checkpoints visible" else ""
            Pair(true, info + extra)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Pair(false, friendlyError(e))
        }
    }

    private fun describeStats(st: JSONObject): String {
        val ver = st.optJSONObject("system")?.optString("comfyui_version").orEmpty()
        val dev = st.optJSONArray("devices")?.optJSONObject(0)?.optString("name").orEmpty().substringBefore(" : ")
        return listOf("Connected", if (ver.isNotBlank()) "ComfyUI $ver" else "", dev)
            .filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun startSocket() {
        stopSocket()
        if (!settings.useWebSocket) return
        socketWanted = true
        openSocket(++socketGen)
    }

    private fun openSocket(gen: Int) {
        socket = client.openSocket(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                viewModelScope.launch { handleWs(text) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleBinary(bytes)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                reconnect(gen)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                reconnect(gen)
            }
        })
    }

    private fun reconnect(gen: Int) {
        if (!socketWanted || gen != socketGen) return
        viewModelScope.launch {
            delay(3000)
            if (socketWanted && gen == socketGen) openSocket(gen)
        }
    }

    private fun stopSocket() {
        socketWanted = false
        socketGen++
        socket?.cancel()
        socket = null
    }

    private fun nodeTitle(id: String): String = runningApi?.let { Wf.title(it, id) } ?: id

    private fun handleWs(text: String) {
        if (!run.running) return
        val j = try { JSONObject(text) } catch (e: Exception) { return }
        val d = j.optJSONObject("data") ?: return
        val pid = d.optString("prompt_id")
        val mine = run.promptId
        if (mine != null && pid.isNotEmpty() && pid != mine) return
        when (j.optString("type")) {
            "execution_start" -> run = run.copy(status = "Started…", progress = -1f)
            "execution_cached" -> run = run.copy(status = "Reusing cached steps…")
            "executing" -> if (!d.isNull("node")) {
                run = run.copy(status = "Running " + nodeTitle(d.optString("node")) + "…", progress = -1f)
            }
            "progress" -> {
                val v = d.optInt("value")
                val m = d.optInt("max", 1).coerceAtLeast(1)
                run = run.copy(progress = v.toFloat() / m, status = nodeTitle(d.optString("node")) + "  " + v + "/" + m)
            }
            "execution_error" -> run = run.copy(
                error = d.optString("node_type") + ": " + d.optString("exception_message").trim(),
            )
            "execution_interrupted" -> run = run.copy(status = "Interrupted")
        }
    }

    /** Live preview frames: 4 bytes event type, then (type 1) 4 bytes image format + data. */
    private fun handleBinary(bytes: ByteString) {
        if (!run.running) return
        val b = bytes.toByteArray()
        if (b.size < 9) return
        fun int(o: Int) = ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
            ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)
        val off = when (int(0)) {
            1 -> 8
            3 -> 8 + int(4)
            else -> return
        }
        if (off <= 0 || off >= b.size) return
        val bmp = try {
            BitmapFactory.decodeByteArray(b, off, b.size - off)?.asImageBitmap()
        } catch (e: Throwable) {
            null
        } ?: return
        viewModelScope.launch { if (run.running) preview = bmp }
    }

    // ------------------------------------------------------------------ node definitions

    private suspend fun fetchSchemas(classes: Collection<String>): Map<String, NodeSchema> = coroutineScope {
        classes.map { c ->
            async {
                try {
                    client.objectInfo(c)?.let { Pair(c, parseNodeSchema(c, it)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull().toMap()
    }

    fun loadSchemas(force: Boolean = false) {
        val doc = current ?: return
        val all = Wf.classes(doc.api)
        val todo = if (force) all.toList() else all.filter { it !in schemas }
        if (todo.isEmpty()) {
            recomputeAnalysis()
            return
        }
        schemaJob?.cancel()
        schemaJob = viewModelScope.launch {
            schemaNote = ""
            val got = fetchSchemas(todo)
            got.forEach { (k, v) -> schemas[k] = v }
            val missing = todo.filter { it !in got }
            schemaNote = when {
                missing.isEmpty() -> ""
                got.isEmpty() -> "Couldn’t load the model lists from the server. Check the connection, then retry."
                else -> "Not installed on the server: " + missing.joinToString()
            }
            recomputeAnalysis()
        }
    }

    private fun recomputeAnalysis() {
        analysis = current?.let { Wf.analyze(it.api, schemas.toMap()) }
        rev++
    }

    // ------------------------------------------------------------------ workflows

    fun selectWorkflow(id: String) {
        currentId = id
        uiPrefs.edit().putString("current", id).apply()
        extRev++
        recomputeAnalysis()
        loadSchemas(false)
    }

    fun openLayoutEditor(id: String) {
        if (currentId != id) selectWorkflow(id)
        layoutEditorFor = id
    }

    fun deleteWorkflow(id: String) {
        wfStore.delete(id)
        workflows.removeAll { it.id == id }
        if (currentId == id) {
            val next = workflows.firstOrNull()
            if (next != null) {
                selectWorkflow(next.id)
            } else {
                currentId = null
                analysis = null
            }
        }
    }

    fun renameWorkflow(id: String, name: String) {
        val i = workflows.indexOfFirst { it.id == id }
        if (i < 0 || name.isBlank()) return
        val n = workflows[i].renamed(name.trim())
        workflows[i] = n
        wfStore.save(n)
    }

    private fun saveSoon(doc: WorkflowDoc) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500)
            withContext(Dispatchers.IO) { wfStore.save(doc) }
        }
    }

    fun setField(nodeId: String, name: String, value: Any?, external: Boolean = false) {
        val doc = current ?: return
        doc.api.optJSONObject(nodeId)?.optJSONObject("inputs")?.put(name, value)
        rev++
        if (external) extRev++
        saveSoon(doc)
    }

    /** Keeps numeric combos numeric. */
    fun setCombo(nodeId: String, name: String, picked: String) {
        val old = current?.api?.optJSONObject(nodeId)?.optJSONObject("inputs")?.opt(name)
        val v: Any = if (old is Number) (picked.toLongOrNull() ?: picked.toDoubleOrNull() ?: picked) else picked
        setField(nodeId, name, v, external = true)
    }

    fun setVisible(key: String, visible: Boolean) {
        val doc = current ?: return
        doc.visibility[key] = visible
        rev++
        saveSoon(doc)
    }

    fun setOutputVisible(nodeId: String, visible: Boolean) {
        val doc = current ?: return
        doc.outputVis[nodeId] = visible
        rev++
        saveSoon(doc)
    }

    fun resetLayout() {
        val doc = current ?: return
        doc.visibility.clear()
        doc.outputVis.clear()
        rev++
        saveSoon(doc)
        message = "Layout reset to automatic"
    }

    fun toggleRandomSeed() {
        val doc = current ?: return
        doc.randomSeed = !doc.randomSeed
        rev++
        saveSoon(doc)
    }

    fun newSeed(nodeId: String, name: String) {
        setField(nodeId, name, Random.nextLong(0, 1L shl 50), external = true)
    }

    // ------------------------------------------------------------------ import

    fun importFromText(text: String, nameHint: String) {
        viewModelScope.launch { doImport(text, nameHint) }
    }

    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            try {
                val cr = getApplication<Application>().contentResolver
                val (text, name) = withContext(Dispatchers.IO) {
                    val t = cr.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw ComfyException("Couldn’t read that file.")
                    val n = cr.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                    } ?: "Workflow"
                    Pair(t, n)
                }
                doImport(text, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = friendlyError(e)
            }
        }
    }

    suspend fun listServerWorkflows(): Result<List<String>> = try {
        Result.success(client.listWorkflows())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(ComfyException(friendlyError(e)))
    }

    fun importFromServer(path: String) {
        viewModelScope.launch {
            try {
                busy = "Downloading…"
                val t = client.getWorkflow(path)
                doImport(t, path.substringAfterLast('/').removeSuffix(".json"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = friendlyError(e)
            } finally {
                busy = ""
            }
        }
    }

    private suspend fun doImport(text: String, nameHint: String) {
        busy = "Importing…"
        try {
            var root = try {
                JSONObject(text.trim())
            } catch (e: Exception) {
                throw ComfyException("That doesn’t look like a workflow JSON file.")
            }
            val inner = root.optJSONObject("prompt")
            if (inner != null && Wf.looksLikeApi(inner)) root = inner

            val api: JSONObject
            var notes: List<String> = emptyList()
            if (UiConverter.isUi(root)) {
                val classes = UiConverter.classes(root)
                val fetched = fetchSchemas(classes.filter { it !in schemas })
                if (fetched.isEmpty() && classes.any { it !in schemas }) {
                    throw ComfyException(
                        "To open an editor-format workflow I need your server (it describes each node’s settings). " +
                            "Connect first, or export the file with “Save (API Format)”.",
                    )
                }
                val res = UiConverter.convert(root, schemas.toMap() + fetched)
                api = res.api
                notes = res.warnings
                fetched.forEach { (k, v) -> schemas[k] = v }
            } else if (Wf.looksLikeApi(root)) {
                api = root
            } else {
                throw ComfyException("Unrecognized workflow format. Use the JSON saved from ComfyUI.")
            }
            if (api.length() == 0) throw ComfyException("The workflow is empty.")

            val doc = WorkflowDoc(newId(), nameHint.removeSuffix(".json").ifBlank { "Workflow" }, api)
            withContext(Dispatchers.IO) { wfStore.save(doc) }
            workflows.add(doc)
            importNotes = notes
            selectWorkflow(doc.id)
            tab = 0
            message = "Imported “" + doc.name + "”"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = e.message ?: "Import failed"
        } finally {
            busy = ""
        }
    }

    // ------------------------------------------------------------------ images

    fun uploadImage(uri: Uri, nodeId: String, input: String) {
        viewModelScope.launch {
            try {
                val cr = getApplication<Application>().contentResolver
                val mime = cr.getType(uri) ?: "image/png"
                val name = cr.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                } ?: ("upload_" + System.currentTimeMillis() + ".png")
                val bytes = withContext(Dispatchers.IO) { cr.openInputStream(uri)?.use { it.readBytes() } }
                    ?: throw ComfyException("Couldn’t read that image.")
                message = "Uploading…"
                val res = client.upload(bytes, name, mime)
                val n = res.optString("name", name)
                val sub = res.optString("subfolder")
                thumbCache.remove(n)
                setField(nodeId, input, if (sub.isNotEmpty()) "$sub/$n" else n, external = true)
                message = "Uploaded $n"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Upload failed: " + friendlyError(e)
            }
        }
    }

    /** Small preview of an image that already lives in the server's input folder. */
    suspend fun inputThumb(ref: String): ImageBitmap? {
        if (ref.isBlank()) return null
        thumbCache[ref]?.let { return it }
        return try {
            val (file, sub, type) = parseImageRef(ref)
            val bytes = client.view(file, sub, type)
            val bmp = withContext(Dispatchers.Default) { decodeSampled(bytes, 300) }
            if (bmp != null) thumbCache[ref] = bmp
            bmp
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ generate

    private fun randomizeSeeds(doc: WorkflowDoc) {
        val api = doc.api
        for (id in Wf.nodeIds(api)) {
            val inputs = api.optJSONObject(id)?.optJSONObject("inputs") ?: continue
            for (name in inputs.keyList()) {
                if ((name == "seed" || name == "noise_seed") && inputs.opt(name) is Number) {
                    inputs.put(name, Random.nextLong(0, 1L shl 50))
                }
            }
        }
        rev++
        extRev++
    }

    fun generate() {
        val doc = current ?: return
        if (run.running) return
        tab = 1
        runJob = viewModelScope.launch {
            run = RunUi(running = true, status = "Sending to ComfyUI…")
            preview = null
            try {
                if (doc.randomSeed) randomizeSeeds(doc)
                withContext(Dispatchers.IO) { wfStore.save(doc) }
                val payload = JSONObject(doc.api.toString())
                runningApi = payload
                val pid = try {
                    client.queuePrompt(payload)
                } catch (e: PromptRejected) {
                    throw ComfyException(describePromptError(e.body, payload))
                }
                run = run.copy(promptId = pid, status = "Queued…")
                val entry = waitForHistory(pid)
                run = run.copy(status = "Downloading results…", progress = -1f)

                val files = parseOutputs(entry).filter { doc.outputVis[it.nodeId] ?: true }
                val batch = ArrayList<ResultImage>()
                val others = ArrayList<String>()
                for (f in files) {
                    if (isImageName(f.filename)) {
                        val bytes = client.view(f.filename, f.subfolder, f.type)
                        batch.add(ResultImage(nextResultId++, bytes, f, doc.name))
                    } else {
                        others.add(f.filename + " (not an image; open it from the ComfyUI page)")
                    }
                }
                results.addAll(0, batch)
                while (results.size > settings.maxResults) results.removeAt(results.size - 1)
                others.addAll(parseTexts(entry))
                val err = entryError(entry) ?: run.error
                preview = null
                run = RunUi(
                    status = when {
                        err != null -> "Failed"
                        files.isEmpty() -> "Finished, but no outputs are switched on. Check the layout editor."
                        else -> "Done"
                    },
                    error = err,
                    notes = others,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                preview = null
                run = RunUi(status = "Failed", error = friendlyError(e))
            }
        }
    }

    private suspend fun waitForHistory(pid: String): JSONObject {
        var fails = 0
        while (true) {
            try {
                client.history(pid)?.let { return it }
                fails = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fails++
                if (fails > 15) throw ComfyException("Lost connection to the server. " + friendlyError(e))
            }
            delay(settings.pollIntervalMs.toLong().coerceAtLeast(300))
        }
    }

    fun stop() {
        val pid = run.promptId
        runJob?.cancel()
        preview = null
        run = RunUi(status = "Stopped")
        viewModelScope.launch {
            try {
                if (pid != null) client.deleteQueued(pid)
                client.interrupt()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // best effort
            }
        }
    }

    fun clearResults() {
        results.clear()
    }

    suspend fun saveToGallery(r: ResultImage): Uri? = withContext(Dispatchers.IO) {
        r.savedUri?.let { return@withContext it }
        try {
            val cr = getApplication<Application>().contentResolver
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, r.file.filename)
                put(MediaStore.MediaColumns.MIME_TYPE, r.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ComfyPix")
            }
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            if (uri != null) {
                cr.openOutputStream(uri)?.use { it.write(r.bytes) }
                r.savedUri = uri
            }
            uri
        } catch (e: Exception) {
            null
        }
    }

    override fun onCleared() {
        stopSocket()
        current?.let { wfStore.save(it) }
        super.onCleared()
    }
}
