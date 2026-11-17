package app.comfypix.data

import android.content.Context
import java.util.UUID

enum class AuthMode(val label: String) {
    NONE("None"),
    BASIC("Username + password"),
    BEARER("Bearer token"),
    HEADER("Custom header"),
}

data class ConnectionSettings(
    val https: Boolean = false,
    val host: String = "192.168.1.100",
    val port: Int = 8188,
    val basePath: String = "",
    val auth: AuthMode = AuthMode.NONE,
    val username: String = "",
    val password: String = "",
    val token: String = "",
    val headerName: String = "",
    val headerValue: String = "",
    val useWebSocket: Boolean = true,
    val pollIntervalMs: Int = 1500,
    val connectTimeoutSec: Int = 10,
    val readTimeoutSec: Int = 60,
    val trustAllCerts: Boolean = false,
    val clientId: String = "",
    val maxResults: Int = 40,
)

/** Cleans up what people paste into the host box (http://1.2.3.4:8188/ etc). */
fun ConnectionSettings.normalized(): ConnectionSettings {
    var h = host.trim()
    var secure = https
    var p = port
    if (h.startsWith("https://", ignoreCase = true)) {
        secure = true
        h = h.substring(8)
    } else if (h.startsWith("http://", ignoreCase = true)) {
        secure = false
        h = h.substring(7)
    }
    h = h.substringBefore('/')
    if (h.count { it == ':' } == 1) {
        val parts = h.split(':')
        h = parts[0]
        parts[1].toIntOrNull()?.let { p = it }
    }
    return copy(
        host = h,
        https = secure,
        port = p,
        basePath = basePath.trim().trim('/'),
        clientId = clientId.ifBlank { UUID.randomUUID().toString() },
        pollIntervalMs = pollIntervalMs.coerceIn(300, 60000),
        connectTimeoutSec = connectTimeoutSec.coerceIn(1, 120),
        readTimeoutSec = readTimeoutSec.coerceIn(5, 600),
        maxResults = maxResults.coerceIn(1, 200),
    )
}

class SettingsStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("comfypix_connection", Context.MODE_PRIVATE)

    fun load(): ConnectionSettings {
        val d = ConnectionSettings()
        var cid = sp.getString("clientId", "") ?: ""
        if (cid.isBlank()) {
            cid = UUID.randomUUID().toString()
            sp.edit().putString("clientId", cid).apply()
        }
        return ConnectionSettings(
            https = sp.getBoolean("https", d.https),
            host = sp.getString("host", d.host) ?: d.host,
            port = sp.getInt("port", d.port),
            basePath = sp.getString("basePath", "") ?: "",
            auth = runCatching { AuthMode.valueOf(sp.getString("auth", "NONE") ?: "NONE") }.getOrDefault(AuthMode.NONE),
            username = sp.getString("username", "") ?: "",
            password = sp.getString("password", "") ?: "",
            token = sp.getString("token", "") ?: "",
            headerName = sp.getString("headerName", "") ?: "",
            headerValue = sp.getString("headerValue", "") ?: "",
            useWebSocket = sp.getBoolean("useWebSocket", d.useWebSocket),
            pollIntervalMs = sp.getInt("pollIntervalMs", d.pollIntervalMs),
            connectTimeoutSec = sp.getInt("connectTimeoutSec", d.connectTimeoutSec),
            readTimeoutSec = sp.getInt("readTimeoutSec", d.readTimeoutSec),
            trustAllCerts = sp.getBoolean("trustAllCerts", d.trustAllCerts),
            clientId = cid,
            maxResults = sp.getInt("maxResults", d.maxResults),
        )
    }

    fun save(s: ConnectionSettings) {
        sp.edit()
            .putBoolean("https", s.https)
            .putString("host", s.host)
            .putInt("port", s.port)
            .putString("basePath", s.basePath)
            .putString("auth", s.auth.name)
            .putString("username", s.username)
            .putString("password", s.password)
            .putString("token", s.token)
            .putString("headerName", s.headerName)
            .putString("headerValue", s.headerValue)
            .putBoolean("useWebSocket", s.useWebSocket)
            .putInt("pollIntervalMs", s.pollIntervalMs)
            .putInt("connectTimeoutSec", s.connectTimeoutSec)
            .putInt("readTimeoutSec", s.readTimeoutSec)
            .putBoolean("trustAllCerts", s.trustAllCerts)
            .putString("clientId", s.clientId)
            .putInt("maxResults", s.maxResults)
            .apply()
    }
}
