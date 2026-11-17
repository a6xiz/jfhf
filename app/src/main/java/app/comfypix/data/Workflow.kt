package app.comfypix.data

import org.json.JSONArray
import org.json.JSONObject

fun JSONObject.keyList(): List<String> = keys().asSequence().map { it.toString() }.toList()

fun JSONArray.strings(): List<String> = (0 until length()).map { opt(it).toString() }

val WIDGET_TYPES = setOf("INT", "FLOAT", "STRING", "BOOLEAN", "COMBO")
val SEED_NAMES = setOf("seed", "noise_seed")

// ---------------------------------------------------------------------------
// Node definitions (from /object_info)
// ---------------------------------------------------------------------------

data class InputSpec(
    val name: String,
    val type: String,
    val options: List<String>? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val multiline: Boolean = false,
    val imageUpload: Boolean = false,
    val controlAfterGenerate: Boolean = false,
    val forceInput: Boolean = false,
) {
    val isWidget: Boolean get() = !forceInput && (options != null || type in WIDGET_TYPES)
}

data class NodeSchema(
    val cls: String,
    val display: String,
    val inputs: LinkedHashMap<String, InputSpec>,
    val isOutput: Boolean,
)

private fun JSONObject?.num(k: String): Double? =
    if (this != null && has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null

/**
 * Handles BOTH combo formats ComfyUI has used:
 *   old: [["a.safetensors", "b.safetensors"], {...}]
 *   new: ["COMBO", {"options": ["a.safetensors", ...], ...}]
 * Not understanding the second one is what makes model pickers come up empty.
 */
fun parseSpec(name: String, arr: JSONArray): InputSpec {
    val first = arr.opt(0)
    val o = arr.optJSONObject(1)
    var type = "UNKNOWN"
    var options: List<String>? = null
    if (first is JSONArray) {
        type = "COMBO"
        options = first.strings()
    } else if (first is String) {
        if (first == "COMBO") {
            type = "COMBO"
            options = o?.optJSONArray("options")?.strings() ?: emptyList()
        } else {
            type = first
        }
    }
    return InputSpec(
        name = name,
        type = type,
        options = options,
        min = o.num("min"),
        max = o.num("max"),
        step = o.num("step"),
        multiline = o?.optBoolean("multiline", false) ?: false,
        imageUpload = o?.optBoolean("image_upload", false) ?: false,
        controlAfterGenerate = o?.optBoolean("control_after_generate", false) ?: false,
        forceInput = o?.optBoolean("forceInput", false) ?: false,
    )
}

fun parseNodeSchema(cls: String, o: JSONObject): NodeSchema {
    val inputs = LinkedHashMap<String, InputSpec>()
    val inp = o.optJSONObject("input")
    val order = o.optJSONObject("input_order")
    for (section in listOf("required", "optional")) {
        val sec = inp?.optJSONObject(section) ?: continue
        val names = order?.optJSONArray(section)?.strings() ?: sec.keyList()
        for (n in names) {
            val arr = sec.optJSONArray(n) ?: continue
            inputs[n] = parseSpec(n, arr)
        }
    }
    return NodeSchema(cls, o.optString("display_name", cls).ifBlank { cls }, inputs, o.optBoolean("output_node", false))
}

// ---------------------------------------------------------------------------
// Fields / categories (the "what is what" detection)
// ---------------------------------------------------------------------------

enum class Kind { COMBO, INT, FLOAT, STRING, BOOL, IMAGE }

enum class Cat(val rank: Int, val label: String, val emoji: String) {
    IMAGE_IN(0, "Input image", "🖼️"),
    POSITIVE(1, "Prompt", "✨"),
    NEGATIVE(2, "Negative prompt", "🚫"),
    MODEL(3, "Model", "🧩"),
    SIZE(4, "Size", "📐"),
    SAMPLER(5, "Sampler", "🎛️"),
    OTHER(6, "Other", "🔧"),
    OUTPUT(7, "Output", "📤"),
}

data class Field(
    val nodeId: String,
    val name: String,
    val kind: Kind,
    val spec: InputSpec?,
    val cat: Cat,
    val nodeTitle: String,
    val cls: String,
    val defaultVisible: Boolean,
) {
    val key: String get() = "$nodeId|$name"
}

data class NodeGroup(val nodeId: String, val title: String, val cls: String, val cat: Cat, val fields: List<Field>)

data class OutputNode(val nodeId: String, val title: String, val cls: String)

class Analysis(val groups: List<NodeGroup>, val outputs: List<OutputNode>)

object Wf {
    fun nodeIds(api: JSONObject): List<String> =
        api.keyList().sortedWith(compareBy<String>({ it.toLongOrNull() ?: Long.MAX_VALUE }, { it }))

    fun title(api: JSONObject, id: String): String {
        val n = api.optJSONObject(id) ?: return id
        val t = n.optJSONObject("_meta")?.optString("title").orEmpty()
        return t.ifBlank { n.optString("class_type", id) }
    }

    fun classes(api: JSONObject): Set<String> =
        api.keyList().mapNotNull { api.optJSONObject(it)?.optString("class_type")?.takeIf { c -> c.isNotBlank() } }.toSet()

    /** An API-format link looks like ["4", 0]. */
    fun isLink(api: JSONObject, v: Any?): Boolean =
        v is JSONArray && v.length() == 2 && v.opt(1) is Number && api.has(v.opt(0).toString())

    fun looksLikeApi(o: JSONObject): Boolean =
        o.length() > 0 && o.keyList().all { o.optJSONObject(it)?.has("class_type") == true }

    fun valueText(v: Any?): String = when (v) {
        null -> ""
        is JSONArray, is JSONObject -> v.toString()
        else -> v.toString()
    }

    fun isOutputNode(cls: String, schema: NodeSchema?): Boolean =
        schema?.isOutput == true || cls.startsWith("Save") || cls.startsWith("Preview") ||
            cls.contains("SaveImage") || cls.contains("PreviewImage")

    /** Which text-encode nodes feed a sampler's positive / negative input. */
    fun findPromptRoles(api: JSONObject): Map<String, Cat> {
        val roles = HashMap<String, Cat>()

        fun walk(id: String, role: String, cat: Cat, seen: MutableSet<String>, depth: Int) {
            if (depth > 12 || !seen.add(id)) return
            val node = api.optJSONObject(id) ?: return
            val ins = node.optJSONObject("inputs") ?: return
            if (node.optString("class_type").contains("TextEncode")) {
                if (!roles.containsKey(id)) roles[id] = cat
                return
            }
            val named = ins.has("positive") && ins.has("negative")
            for (k in ins.keyList()) {
                if (named && k != role) continue
                val v = ins.opt(k)
                if (isLink(api, v)) walk((v as JSONArray).opt(0).toString(), role, cat, seen, depth + 1)
            }
        }

        for ((role, cat) in listOf("positive" to Cat.POSITIVE, "negative" to Cat.NEGATIVE)) {
            for (id in nodeIds(api)) {
                val ins = api.optJSONObject(id)?.optJSONObject("inputs") ?: continue
                if (!ins.has(role)) continue
                val v = ins.opt(role)
                if (isLink(api, v)) walk((v as JSONArray).opt(0).toString(), role, cat, HashSet(), 0)
            }
        }
        return roles
    }

    fun categorize(cls: String, inputs: JSONObject, schema: NodeSchema?, role: Cat?): Cat {
        if (role != null) return role
        return when {
            cls.startsWith("LoadImage") || schema?.inputs?.values?.any { it.imageUpload } == true -> Cat.IMAGE_IN
            cls.contains("TextEncode") -> Cat.POSITIVE
            cls.contains("Loader") -> Cat.MODEL
            cls.contains("KSampler") || cls.contains("SamplerCustom") || (inputs.has("steps") && inputs.has("cfg")) -> Cat.SAMPLER
            cls.contains("Latent") && inputs.has("width") && inputs.has("height") -> Cat.SIZE
            isOutputNode(cls, schema) -> Cat.OUTPUT
            else -> Cat.OTHER
        }
    }

    private val FLOAT_HINTS = setOf(
        "cfg", "denoise", "guidance", "strength", "strength_model", "strength_clip",
        "scale_by", "weight", "start_percent", "end_percent",
    )

    private val COMMON = setOf("seed", "noise_seed", "steps", "cfg", "guidance", "denoise", "sampler_name", "scheduler")

    fun defaultVisible(cat: Cat, kind: Kind, name: String, spec: InputSpec?, cls: String): Boolean = when (cat) {
        Cat.IMAGE_IN -> kind == Kind.IMAGE
        Cat.POSITIVE, Cat.NEGATIVE -> kind == Kind.STRING
        Cat.MODEL, Cat.SIZE, Cat.SAMPLER -> true
        Cat.OUTPUT -> false
        Cat.OTHER -> name in COMMON || cls.startsWith("Primitive") || (kind == Kind.STRING && spec?.multiline == true)
    }

    fun kindOf(spec: InputSpec?, v: Any?, cls: String, name: String): Kind? {
        if (spec != null) {
            if (spec.imageUpload) return Kind.IMAGE
            if (spec.options != null) return Kind.COMBO
            when (spec.type) {
                "INT" -> return Kind.INT
                "FLOAT" -> return Kind.FLOAT
                "STRING" -> return Kind.STRING
                "BOOLEAN" -> return Kind.BOOL
            }
        }
        if (cls.startsWith("LoadImage") && name == "image") return Kind.IMAGE
        return when (v) {
            is Boolean -> Kind.BOOL
            // no node definition available: 8 in "cfg" is still a decimal setting
            is Int, is Long -> if (name in FLOAT_HINTS) Kind.FLOAT else Kind.INT
            is Double -> Kind.FLOAT
            is String -> Kind.STRING
            else -> null
        }
    }

    fun analyze(api: JSONObject, schemas: Map<String, NodeSchema>): Analysis {
        val roles = findPromptRoles(api)
        val groups = ArrayList<NodeGroup>()
        val outs = ArrayList<OutputNode>()
        for (id in nodeIds(api)) {
            val node = api.optJSONObject(id) ?: continue
            val cls = node.optString("class_type")
            val inputs = node.optJSONObject("inputs") ?: JSONObject()
            val schema = schemas[cls]
            val title = title(api, id)
            val cat = categorize(cls, inputs, schema, roles[id])
            val fields = ArrayList<Field>()
            for (name in inputs.keyList()) {
                val v = inputs.opt(name)
                if (isLink(api, v)) continue
                val spec = schema?.inputs?.get(name)
                val kind = kindOf(spec, v, cls, name) ?: continue
                fields.add(Field(id, name, kind, spec, cat, title, cls, defaultVisible(cat, kind, name, spec, cls)))
            }
            if (fields.isNotEmpty()) groups.add(NodeGroup(id, title, cls, cat, fields))
            if (isOutputNode(cls, schema)) outs.add(OutputNode(id, title, cls))
        }
        groups.sortWith(compareBy<NodeGroup> { it.cat.rank })
        return Analysis(groups, outs)
    }
}

// ---------------------------------------------------------------------------
// Saved workflow + its layout choices
// ---------------------------------------------------------------------------

class WorkflowDoc(
    val id: String,
    val name: String,
    val api: JSONObject,
    val visibility: MutableMap<String, Boolean> = LinkedHashMap(),
    val outputVis: MutableMap<String, Boolean> = LinkedHashMap(),
    var randomSeed: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun isVisible(f: Field): Boolean = visibility[f.key] ?: f.defaultVisible

    fun renamed(newName: String) = WorkflowDoc(id, newName, api, visibility, outputVis, randomSeed, createdAt)

    fun toJson(): JSONObject {
        val vis = JSONObject()
        visibility.forEach { (k, v) -> vis.put(k, v) }
        val outs = JSONObject()
        outputVis.forEach { (k, v) -> outs.put(k, v) }
        return JSONObject()
            .put("id", id).put("name", name).put("api", api)
            .put("randomSeed", randomSeed).put("createdAt", createdAt)
            .put("visibility", vis).put("outputVis", outs)
    }

    companion object {
        fun fromJson(o: JSONObject): WorkflowDoc {
            val vis = LinkedHashMap<String, Boolean>()
            o.optJSONObject("visibility")?.let { j -> for (k in j.keyList()) vis[k] = j.optBoolean(k, true) }
            val outs = LinkedHashMap<String, Boolean>()
            o.optJSONObject("outputVis")?.let { j -> for (k in j.keyList()) outs[k] = j.optBoolean(k, true) }
            return WorkflowDoc(
                id = o.getString("id"),
                name = o.optString("name", "Workflow"),
                api = o.getJSONObject("api"),
                visibility = vis,
                outputVis = outs,
                randomSeed = o.optBoolean("randomSeed", true),
                createdAt = o.optLong("createdAt", 0L),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Results from /history
// ---------------------------------------------------------------------------

data class OutFile(val nodeId: String, val filename: String, val subfolder: String, val type: String)

fun parseOutputs(entry: JSONObject): List<OutFile> {
    val outs = entry.optJSONObject("outputs") ?: return emptyList()
    val res = ArrayList<OutFile>()
    for (nid in outs.keyList()) {
        val o = outs.optJSONObject(nid) ?: continue
        for (k in o.keyList()) {
            val arr = o.optJSONArray(k) ?: continue
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                val fn = f.optString("filename")
                if (fn.isEmpty()) continue
                res.add(OutFile(nid, fn, f.optString("subfolder"), f.optString("type", "output")))
            }
        }
    }
    return res
}

fun parseTexts(entry: JSONObject): List<String> {
    val outs = entry.optJSONObject("outputs") ?: return emptyList()
    val res = ArrayList<String>()
    for (nid in outs.keyList()) {
        val o = outs.optJSONObject(nid) ?: continue
        val arr = o.optJSONArray("text") ?: continue
        for (i in 0 until arr.length()) {
            val s = arr.opt(i)
            if (s is String && s.isNotBlank()) res.add(s)
        }
    }
    return res
}

fun entryError(entry: JSONObject): String? {
    val st = entry.optJSONObject("status") ?: return null
    if (st.optString("status_str") != "error") return null
    val msgs = st.optJSONArray("messages") ?: return "Execution failed"
    for (i in 0 until msgs.length()) {
        val m = msgs.optJSONArray(i) ?: continue
        if (m.optString(0) == "execution_error") {
            val d = m.optJSONObject(1) ?: continue
            return d.optString("node_type") + ": " + d.optString("exception_message").trim()
        }
    }
    return "Execution failed"
}

/** Turns the server's 400 body into something readable. */
fun describePromptError(body: String, api: JSONObject): String {
    val sb = StringBuilder()
    try {
        val j = JSONObject(body)
        val e = j.opt("error")
        if (e is JSONObject) {
            sb.append(e.optString("message"))
            val d = e.optString("details")
            if (d.isNotBlank()) sb.append(": ").append(d)
        } else if (e is String) {
            sb.append(e)
        }
        val ne = j.optJSONObject("node_errors")
        if (ne != null) {
            for (id in ne.keyList()) {
                val errs = ne.optJSONObject(id)?.optJSONArray("errors") ?: continue
                for (i in 0 until errs.length()) {
                    val er = errs.optJSONObject(i) ?: continue
                    sb.append("\n• ").append(Wf.title(api, id)).append(" (#").append(id).append("): ").append(er.optString("message"))
                    val det = er.optString("details")
                    if (det.isNotBlank()) sb.append(" – ").append(det)
                }
            }
        }
    } catch (ex: Exception) {
        sb.append(body.take(400))
    }
    return sb.toString().ifBlank { body.take(400) }
}

/** "folder/name.png [input]" -> (name.png, folder, input) */
fun parseImageRef(v: String): Triple<String, String, String> {
    var s = v.trim()
    var type = "input"
    val m = Regex("^(.*) \\[(input|output|temp)\\]$").find(s)
    if (m != null) {
        s = m.groupValues[1]
        type = m.groupValues[2]
    }
    val i = s.lastIndexOf('/')
    return if (i >= 0) Triple(s.substring(i + 1), s.substring(0, i), type) else Triple(s, "", type)
}

// ---------------------------------------------------------------------------
// Editor-format (.json saved from the ComfyUI canvas) -> API format
// ---------------------------------------------------------------------------

object UiConverter {
    private val VIRTUAL = setOf("Note", "MarkdownNote", "Reroute", "PrimitiveNode")

    class Result(val api: JSONObject, val warnings: List<String>)

    fun isUi(root: JSONObject): Boolean = root.opt("nodes") is JSONArray

    fun classes(root: JSONObject): Set<String> {
        val res = LinkedHashSet<String>()
        val nodes = root.optJSONArray("nodes") ?: return res
        for (i in 0 until nodes.length()) {
            val t = nodes.optJSONObject(i)?.optString("type").orEmpty()
            if (t.isNotEmpty() && t !in VIRTUAL) res.add(t)
        }
        return res
    }

    fun convert(root: JSONObject, schemas: Map<String, NodeSchema>): Result {
        val warnings = ArrayList<String>()
        val arr = root.optJSONArray("nodes") ?: throw IllegalArgumentException("This file has no nodes")
        val nodes = LinkedHashMap<String, JSONObject>()
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i) ?: continue
            nodes[n.opt("id").toString()] = n
        }
        val links = HashMap<String, Pair<String, Int>>()
        val la = root.optJSONArray("links")
        if (la != null) {
            for (i in 0 until la.length()) {
                val l = la.opt(i)
                if (l is JSONArray && l.length() >= 3) {
                    links[l.opt(0).toString()] = Pair(l.opt(1).toString(), l.optInt(2))
                } else if (l is JSONObject) {
                    links[l.opt("id").toString()] = Pair(l.opt("origin_id").toString(), l.optInt("origin_slot"))
                }
            }
        }

        fun linkOf(n: JSONObject, idx: Int): String? {
            val inp = n.optJSONArray("inputs")?.optJSONObject(idx) ?: return null
            return if (inp.isNull("link")) null else inp.opt("link").toString()
        }

        // Follows a link through Reroutes / Primitives / bypassed nodes to a real source.
        fun resolve(linkId: String, depth: Int): Any? {
            if (depth > 30) return null
            val pair = links[linkId] ?: return null
            val origin = pair.first
            val slot = pair.second
            val n = nodes[origin] ?: return null
            val type = n.optString("type")
            val mode = n.optInt("mode", 0)
            return when {
                mode == 2 -> null
                type == "Reroute" -> linkOf(n, 0)?.let { resolve(it, depth + 1) }
                type == "PrimitiveNode" -> n.optJSONArray("widgets_values")?.opt(0)?.takeIf { it != JSONObject.NULL }
                mode == 4 -> {
                    val outType = n.optJSONArray("outputs")?.optJSONObject(slot)?.optString("type").orEmpty()
                    val ins = n.optJSONArray("inputs")
                    var match: String? = null
                    var first: String? = null
                    if (ins != null) {
                        for (i in 0 until ins.length()) {
                            val inp = ins.optJSONObject(i) ?: continue
                            if (inp.isNull("link")) continue
                            val l = inp.opt("link").toString()
                            if (first == null) first = l
                            if (match == null && inp.optString("type") == outType) match = l
                        }
                    }
                    (match ?: first)?.let { resolve(it, depth + 1) }
                }
                else -> JSONArray().put(origin).put(slot)
            }
        }

        val api = JSONObject()
        val warnedClasses = HashSet<String>()
        for ((id, n) in nodes) {
            val type = n.optString("type")
            val mode = n.optInt("mode", 0)
            if (type in VIRTUAL || mode == 2 || mode == 4) continue
            val schema = schemas[type]
            if (schema == null && warnedClasses.add(type)) {
                warnings.add("“$type” isn’t installed on this server (or is a group/subgraph), so its settings were skipped.")
            }
            val ins = JSONObject()
            val linked = HashSet<String>()
            val uiIns = n.optJSONArray("inputs")
            if (uiIns != null) {
                for (i in 0 until uiIns.length()) {
                    val inp = uiIns.optJSONObject(i) ?: continue
                    if (inp.isNull("link")) continue
                    val r = resolve(inp.opt("link").toString(), 0) ?: continue
                    val name = inp.optString("name")
                    ins.put(name, r)
                    linked.add(name)
                }
            }
            val wv = n.opt("widgets_values")
            if (wv is JSONObject) {
                for (k in wv.keyList()) {
                    if (k !in linked && !wv.isNull(k)) ins.put(k, wv.opt(k))
                }
            } else if (wv is JSONArray && schema != null) {
                assignWidgets(schema, wv, linked, ins)
            }
            val title = n.optString("title").ifBlank { schema?.display ?: type }
            api.put(id, JSONObject().put("class_type", type).put("inputs", ins).put("_meta", JSONObject().put("title", title)))
        }
        if (root.optJSONObject("definitions")?.has("subgraphs") == true) {
            warnings.add("This workflow contains subgraphs, which aren’t supported yet. Export it with “Save (API Format)” instead.")
        }
        return Result(api, warnings)
    }

    /** widgets_values is positional. Rebuild names from the node definition. */
    private fun assignWidgets(schema: NodeSchema, wv: JSONArray, linked: Set<String>, out: JSONObject) {
        val specs = schema.inputs.values.filter { it.isWidget }
        fun hasControl(s: InputSpec) = s.controlAfterGenerate || (s.type == "INT" && s.name in SEED_NAMES)
        val ctrl = specs.count { hasControl(it) }
        val upload = specs.count { it.imageUpload }
        // seeds carry an extra "randomize/fixed" value; skip it only when the counts say it's there
        val useCtrl = ctrl > 0 && wv.length() != specs.size + upload
        var idx = 0
        for (s in specs) {
            if (idx >= wv.length()) break
            val v = wv.opt(idx)
            idx++
            if (useCtrl && hasControl(s)) idx++
            if (s.name !in linked && v != null && v != JSONObject.NULL) out.put(s.name, v)
        }
    }
}
