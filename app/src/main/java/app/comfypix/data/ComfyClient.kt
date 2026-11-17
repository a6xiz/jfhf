package app.comfypix.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class ComfyException(msg: String) : Exception(msg)

class PromptRejected(val code: Int, val body: String) : Exception("The server rejected the workflow (HTTP $code)")

fun friendlyError(e: Throwable): String = when (e) {
    is ComfyException -> e.message ?: "Error"
    is UnknownHostException -> "Unknown host. Check the address in Connection settings."
    is ConnectException -> "Connection refused. Is ComfyUI running with --listen, and is the port right?"
    is SocketTimeoutException -> "Timed out. The server is unreachable or too slow."
    is SSLException -> "HTTPS problem. Check the HTTPS switch, or allow self-signed certificates."
    is IllegalArgumentException -> "Invalid address. Check host and port."
    else -> e.message ?: e.javaClass.simpleName
}

class ComfyClient(val s: ConnectionSettings) {

    private val http: OkHttpClient = build()
    private val wsHttp: OkHttpClient = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    private fun build(): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(s.connectTimeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(s.readTimeoutSec.toLong(), TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val rb = chain.request().newBuilder()
                when (s.auth) {
                    AuthMode.BASIC -> rb.header("Authorization", Credentials.basic(s.username, s.password))
                    AuthMode.BEARER -> rb.header("Authorization", "Bearer " + s.token)
                    AuthMode.HEADER -> if (s.headerName.isNotBlank()) rb.header(s.headerName.trim(), s.headerValue)
                    AuthMode.NONE -> {}
                }
                chain.proceed(rb.build())
            }
        if (s.trustAllCerts) {
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
            b.sslSocketFactory(ctx.socketFactory, tm)
            b.hostnameVerifier { _, _ -> true }
        }
        return b.build()
    }

    fun url(vararg seg: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val b = HttpUrl.Builder()
            .scheme(if (s.https) "https" else "http")
            .host(s.host.trim())
            .port(s.port)
        s.basePath.split('/').filter { it.isNotBlank() }.forEach { b.addPathSegment(it) }
        seg.forEach { b.addPathSegment(it) }
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private fun get(u: HttpUrl): Request = Request.Builder().url(u).get().build()

    private fun <T> call(req: Request, parse: (String) -> T): T {
        return http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val extra = if (txt.isNotBlank()) ": " + txt.take(200) else ""
                throw ComfyException("HTTP " + r.code + extra)
            }
            parse(txt)
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    suspend fun systemStats(): JSONObject = io { call(get(url("system_stats"))) { JSONObject(it) } }

    /** Node definition for one class, or null when the server doesn't know the node. */
    suspend fun objectInfo(cls: String): JSONObject? =
        io { call(get(url("object_info", cls))) { JSONObject(it).optJSONObject(cls) } }

    suspend fun queuePrompt(wf: JSONObject): String = io {
        val body = JSONObject().put("prompt", wf).put("client_id", s.clientId).toString()
            .toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url(url("prompt")).post(body).build()).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw PromptRejected(r.code, txt)
            JSONObject(txt).getString("prompt_id")
        }
    }

    /** The history entry only exists once a prompt has finished (or failed). */
    suspend fun history(pid: String): JSONObject? =
        io { call(get(url("history", pid))) { JSONObject(it).optJSONObject(pid) } }

    suspend fun interrupt() {
        io { call(Request.Builder().url(url("interrupt")).post("".toRequestBody()).build()) { } }
    }

    suspend fun deleteQueued(pid: String) {
        val body = JSONObject().put("delete", JSONArray().put(pid)).toString()
            .toRequestBody("application/json".toMediaType())
        io { call(Request.Builder().url(url("queue")).post(body).build()) { } }
    }

    suspend fun view(filename: String, subfolder: String, type: String): ByteArray = io {
        val u = url("view", query = mapOf("filename" to filename, "subfolder" to subfolder, "type" to type))
        http.newCall(get(u)).execute().use { r ->
            if (!r.isSuccessful) throw ComfyException("HTTP " + r.code + " while loading " + filename)
            r.body?.bytes() ?: ByteArray(0)
        }
    }

    suspend fun upload(bytes: ByteArray, name: String, mime: String): JSONObject = io {
        val type = runCatching { mime.toMediaType() }.getOrDefault("image/png".toMediaType())
        val mp = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", name, bytes.toRequestBody(type))
            .addFormDataPart("type", "input")
            .addFormDataPart("overwrite", "true")
            .build()
        call(Request.Builder().url(url("upload", "image")).post(mp).build()) { JSONObject(it) }
    }

    /** Workflows saved in the ComfyUI web UI (editor format). */
    suspend fun listWorkflows(): List<String> = io {
        val u = url("userdata", query = mapOf("dir" to "workflows", "recurse" to "true", "full_info" to "false"))
        call(get(u)) { t ->
            val a = JSONArray(t)
            (0 until a.length()).map { a.get(it).toString().replace('\\', '/') }
                .filter { it.endsWith(".json") }
                .sorted()
        }
    }

    suspend fun getWorkflow(path: String): String = io {
        call(get(url("userdata", "workflows/$path"))) { it }
    }

    fun openSocket(listener: WebSocketListener): WebSocket {
        val u = url("ws", query = mapOf("clientId" to s.clientId))
        return wsHttp.newWebSocket(Request.Builder().url(u).build(), listener)
    }
}
