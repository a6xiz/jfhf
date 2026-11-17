package app.comfypix.data

import android.content.Context
import org.json.JSONObject
import java.io.File

class WorkflowStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "workflows").apply { mkdirs() }

    fun list(): List<WorkflowDoc> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { f -> runCatching { WorkflowDoc.fromJson(JSONObject(f.readText())) }.getOrNull() }
            .sortedBy { it.createdAt }

    fun save(doc: WorkflowDoc) {
        File(dir, doc.id + ".json").writeText(doc.toJson().toString())
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }
}
