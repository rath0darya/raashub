package com.raashub

import android.content.Intent
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.BitmapFactory
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import org.torproject.jni.TorService
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.util.concurrent.atomic.AtomicInteger

private data class OnionEntry(val url: String, val entity: String, val source: String, val category: String = "other") {
    val host: String get() = runCatching { Uri.parse(url).host ?: url }.getOrDefault(url)
}
private data class OnionGroup(val name: String, val entries: List<OnionEntry>) {
    val urls: List<String> get() = entries.map { it.url }.distinct()
    val categories: Set<String> get() = entries.map { it.category }.toSet()
}
private fun groupSources(sources: List<OnionEntry>): List<OnionGroup> =
    sources.groupBy { it.entity.trim().ifBlank { it.host } }
        .map { (name, entries) -> OnionGroup(name, entries.distinctBy { it.url.lowercase() }.sortedBy { it.host.lowercase() }) }
        .sortedBy { it.name.lowercase() }

private data class HealthResult(
    val status: String,
    val detail: String,
    val checkedAt: Long,
    val responseCode: Int? = null,
    val httpVersion: String = "—",
    val finalUrl: String = "",
    val title: String = "",
    val contentType: String = "",
    val server: String = "",
    val etag: String = "",
    val lastModified: String = "",
    val responseDate: String = "",
    val setCookiePresent: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val error: String = ""
)
private data class HealthReport(val torAvailable: Boolean, val proxyPort: Int?, val message: String, val results: Map<String, HealthResult>)

private const val LATEST_LIVE_CHECK_PREFS = "raashub_latest_live_check_v2"
private const val LATEST_LIVE_CHECK_KEY = "latest_report"

private fun saveLatestHealthReport(context: android.content.Context, report: HealthReport) {
    runCatching {
        val root = org.json.JSONObject()
            .put("torAvailable", report.torAvailable)
            .put("proxyPort", report.proxyPort ?: org.json.JSONObject.NULL)
            .put("message", report.message)
        val results = org.json.JSONArray()
        report.results.forEach { (url, result) ->
            val headers = org.json.JSONObject()
            result.headers.forEach { (key, value) -> headers.put(key, value) }
            results.put(org.json.JSONObject()
                .put("url", url).put("status", result.status).put("detail", result.detail)
                .put("checkedAt", result.checkedAt).put("responseCode", result.responseCode ?: org.json.JSONObject.NULL)
                .put("httpVersion", result.httpVersion).put("finalUrl", result.finalUrl)
                .put("title", result.title).put("contentType", result.contentType).put("server", result.server)
                .put("etag", result.etag).put("lastModified", result.lastModified).put("responseDate", result.responseDate)
                .put("setCookiePresent", result.setCookiePresent).put("headers", headers).put("error", result.error))
        }
        root.put("results", results)
        context.getSharedPreferences(LATEST_LIVE_CHECK_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(LATEST_LIVE_CHECK_KEY, root.toString()).apply()
    }
}

private fun loadLatestHealthReport(context: android.content.Context): HealthReport? = runCatching {
    val raw = context.getSharedPreferences(LATEST_LIVE_CHECK_PREFS, android.content.Context.MODE_PRIVATE)
        .getString(LATEST_LIVE_CHECK_KEY, null) ?: return null
    val root = org.json.JSONObject(raw)
    val results = linkedMapOf<String, HealthResult>()
    val array = root.optJSONArray("results") ?: org.json.JSONArray()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val headersObject = item.optJSONObject("headers") ?: org.json.JSONObject()
        val headers = linkedMapOf<String, String>()
        headersObject.keys().forEach { key -> headers[key] = headersObject.optString(key) }
        val code = if (item.isNull("responseCode")) null else item.optInt("responseCode")
        results[item.optString("url")] = HealthResult(
            status = item.optString("status", "Unverified"), detail = item.optString("detail"),
            checkedAt = item.optLong("checkedAt"), responseCode = code,
            httpVersion = item.optString("httpVersion", "—"), finalUrl = item.optString("finalUrl"),
            title = item.optString("title"), contentType = item.optString("contentType"),
            server = item.optString("server"), etag = item.optString("etag"),
            lastModified = item.optString("lastModified"), responseDate = item.optString("responseDate"),
            setCookiePresent = item.optBoolean("setCookiePresent"), headers = headers, error = item.optString("error"))
    }
    val proxy = if (root.isNull("proxyPort")) null else root.optInt("proxyPort")
    HealthReport(root.optBoolean("torAvailable"), proxy, root.optString("message"), results)
}.getOrNull()

private fun formatIst(timestamp: Long): String =
    java.text.SimpleDateFormat("dd MMM yyyy, hh:mm:ss a 'IST'", java.util.Locale.ENGLISH).apply {
        timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
    }.format(java.util.Date(timestamp))

private fun httpDateToIst(value: String): String = runCatching {
    val formats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss z",
        "EEE, dd MMM yyyy HH:mm:ss 'GMT'",
        "EEE, dd MMM yyyy HH:mm:ss Z"
    )
    val date = formats.firstNotNullOfOrNull { pattern ->
        runCatching {
            java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("GMT")
            }.parse(value)
        }.getOrNull()
    } ?: return value
    formatIst(date.time)
}.getOrDefault(value)

private suspend fun checkOnionSources(sources: List<OnionEntry>, onProgress: (Int, Int) -> Unit): HealthReport {
    val onionSources = sources.filter { it.host.endsWith(".onion", ignoreCase = true) }.distinctBy { it.url.lowercase() }
    if (onionSources.isEmpty()) return HealthReport(false, null, "No onion URLs available to check.", emptyMap())

    val proxyPort = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + 90000L
        while (System.currentTimeMillis() < deadline) {
            val port = findTorSocksProxy()
            if (port != null && isTorCircuitReady(port)) return@withContext port
            Thread.sleep(1500)
        }
        null
    }
    if (proxyPort == null) return HealthReport(false, null, "Tor proxy or Tor circuit did not become ready within 90 seconds. Check your internet connection and retry.", emptyMap())

    // Keep Tor concurrency conservative, then retry timed-out services with progressively
    // longer waits. Some onion services take minutes to establish a circuit or send headers.
    val semaphore = Semaphore(3)
    val completed = AtomicInteger(0)
    withContext(Dispatchers.Main) { onProgress(0, onionSources.size) }
    val results = kotlinx.coroutines.coroutineScope {
        onionSources.map { entry ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        var result = checkOneOnionAttempt(entry.url, proxyPort, 45000)
                        if (result.status == "Unverified" && result.error.contains("Timed out", ignoreCase = true)) {
                            result = checkOneOnionAttempt(entry.url, proxyPort, 90000)
                        }
                        if (result.status == "Unverified" && result.error.contains("Timed out", ignoreCase = true)) {
                            // Final slow-service pass: allow up to three minutes for the Tor
                            // connection, TLS handshake and HTTP response headers to complete.
                            result = checkOneOnionAttempt(entry.url, proxyPort, 180000)
                        }
                        entry.url to result
                    } finally {
                        val done = completed.incrementAndGet()
                        withContext(Dispatchers.Main) { onProgress(done, onionSources.size) }
                    }
                }
            }
        }.awaitAll().toMap()
    }
    val responding = results.values.count { it.status == "Online" || it.status == "Responding" }
    val timedOut = results.values.count { it.status == "Unverified" && it.error.contains("Timed out", ignoreCase = true) }
    return HealthReport(
        true, proxyPort,
        "Checked all ${results.size} onion URLs. $responding responded; $timedOut still timed out after retries; ${results.size - responding - timedOut} had other errors. Slow services received retries up to 3 minutes each.",
        results
    )
}

private fun isTorCircuitReady(proxyPort: Int): Boolean {
    return try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", proxyPort), 1200)
            socket.soTimeout = 3500
            val out = socket.getOutputStream()
            out.write(byteArrayOf(5, 1, 0)); out.flush()
            val greeting = ByteArray(2); readFully(socket, greeting)
            if (greeting[0].toInt() != 5 || greeting[1].toInt() != 0) return false
            val host = "check.torproject.org".toByteArray(StandardCharsets.US_ASCII)
            out.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()))
            out.write(host)
            out.write(byteArrayOf(0, 80)); out.flush()
            val reply = ByteArray(4); readFully(socket, reply)
            if (reply[0].toInt() != 5 || reply[1].toInt() != 0) return false
            when (reply[3].toInt() and 0xff) {
                1 -> skipFully(socket, 4)
                3 -> { val length = socket.getInputStream().read(); if (length < 0) return false; skipFully(socket, length) }
                4 -> skipFully(socket, 16)
                else -> return false
            }
            skipFully(socket, 2)
            val request = "HEAD / HTTP/1.1\r\nHost: check.torproject.org\r\nConnection: close\r\n\r\n"
            out.write(request.toByteArray(StandardCharsets.ISO_8859_1)); out.flush()
            val status = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1)).readLine()
            status?.startsWith("HTTP/") == true
        }
    } catch (_: Exception) { false }
}

private fun findTorSocksProxy(): Int? {
    for (port in listOf(9050, 9150)) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 700)
                socket.soTimeout = 700
                socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                socket.getOutputStream().flush()
                val response = ByteArray(2)
                readFully(socket, response)
                if (response[0].toInt() == 5 && response[1].toInt() == 0) return port
            }
        } catch (_: Exception) { /* Not a usable local SOCKS5 proxy. */ }
    }
    return null
}

private fun checkOneOnionAttempt(urlText: String, proxyPort: Int, readTimeoutMs: Int): HealthResult {
    val checkedAt = System.currentTimeMillis()
    return try {
        val uri = Uri.parse(urlText)
        val host = uri.host ?: throw IOException("Invalid source URL")
        if (!host.endsWith(".onion", ignoreCase = true)) throw IOException("Health checks are restricted to onion hosts")
        val scheme = (uri.scheme ?: "http").lowercase()
        if (scheme != "http" && scheme != "https") throw IOException("Unsupported URL scheme")
        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
        val raw = Socket()
        raw.connect(InetSocketAddress("127.0.0.1", proxyPort), 5000)
        raw.soTimeout = readTimeoutMs
        try {
            val out = raw.getOutputStream()
            out.write(byteArrayOf(5, 1, 0)); out.flush()
            val greeting = ByteArray(2); readFully(raw, greeting)
            if (greeting[0].toInt() != 5 || greeting[1].toInt() != 0) throw IOException("SOCKS5 negotiation failed")
            val hostBytes = host.toByteArray(StandardCharsets.US_ASCII)
            if (hostBytes.size > 255) throw IOException("Invalid onion hostname")
            out.write(byteArrayOf(5, 1, 0, 3, hostBytes.size.toByte()))
            out.write(hostBytes)
            out.write(byteArrayOf((port shr 8).toByte(), port.toByte())); out.flush()
            val reply = ByteArray(4); readFully(raw, reply)
            if (reply[0].toInt() != 5 || reply[1].toInt() != 0) throw IOException("Tor SOCKS reply ${reply[1].toInt() and 0xff}")
            when (reply[3].toInt() and 0xff) {
                1 -> skipFully(raw, 4)
                3 -> { val length = raw.getInputStream().read(); if (length < 0) throw IOException("Incomplete SOCKS reply"); skipFully(raw, length) }
                4 -> skipFully(raw, 16)
                else -> throw IOException("Invalid SOCKS address type")
            }
            skipFully(raw, 2)
            val active: Socket = if (scheme == "https") {
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
                ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                ssl.startHandshake()
                ssl
            } else raw
            active.use { connection ->
                connection.soTimeout = readTimeoutMs
                val path = (uri.encodedPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.encodedQuery?.let { "?$it" } ?: "")
                val request = "GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: RaaSHub-HealthCheck/1.0\r\nRange: bytes=0-32767\r\nConnection: close\r\n\r\n"
                connection.getOutputStream().write(request.toByteArray(StandardCharsets.ISO_8859_1))
                connection.getOutputStream().flush()
                val reader = BufferedReader(InputStreamReader(connection.getInputStream(), StandardCharsets.ISO_8859_1))
                val statusLine = reader.readLine() ?: throw IOException("Empty HTTP response")
                val match = Regex("^HTTP/(\\d(?:\\.\\d)?)\\s+(\\d{3})").find(statusLine)
                    ?: throw IOException("Unrecognised HTTP response")
                val httpVersion = "HTTP/${match.groupValues[1]}"
                val code = match.groupValues[2].toInt()
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val split = line.indexOf(':')
                    if (split > 0) {
                        val name = line.substring(0, split).trim().lowercase()
                        val value = line.substring(split + 1).trim()
                        headers[name] = if (headers.containsKey(name)) headers.getValue(name) + ", " + value else value
                    }
                }
                // Fast health-check mode reads response headers only. Avoid downloading
                // page bodies from hundreds of slow onion services during status scans.
                val title = ""
                val contentType = headers["content-type"].orEmpty()
                val server = headers["server"].orEmpty()
                val detail = "HTTP $code • ${if (code in 200..399) "responded" else "server responded"}"
                val status = when {
                    code in 200..399 -> "Online"
                    code in 400..499 -> "Responding"
                    else -> "Server error"
                }
                HealthResult(
                    status = status, detail = detail, checkedAt = checkedAt, responseCode = code,
                    httpVersion = httpVersion, finalUrl = urlText, title = title,
                    contentType = contentType, server = server, etag = headers["etag"].orEmpty(),
                    lastModified = headers["last-modified"].orEmpty(), responseDate = headers["date"].orEmpty(),
                    setCookiePresent = headers.containsKey("set-cookie"), headers = headers
                )
            }
        } finally { runCatching { raw.close() } }
    } catch (e: Exception) {
        val reason = when (e) {
            is java.net.SocketTimeoutException -> "Timed out through Tor"
            is javax.net.ssl.SSLException -> "TLS/HTTPS handshake failed"
            is java.net.ConnectException -> "Connection refused or unreachable through Tor"
            else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        }
        HealthResult("Unverified", reason, checkedAt, finalUrl = urlText, error = reason)
    }
}

private fun readFully(socket: Socket, buffer: ByteArray) {
    var offset = 0
    while (offset < buffer.size) {
        val count = socket.getInputStream().read(buffer, offset, buffer.size - offset)
        if (count < 0) throw IOException("Unexpected end of SOCKS response")
        offset += count
    }
}

private fun skipFully(socket: Socket, amount: Int) {
    var remaining = amount
    while (remaining > 0) {
        val skipped = socket.getInputStream().skip(remaining.toLong()).toInt()
        if (skipped > 0) remaining -= skipped
        else { if (socket.getInputStream().read() < 0) throw IOException("Unexpected end of SOCKS response"); remaining-- }
    }
}

class MainActivity : ComponentActivity() {
    private var pendingTorUrl: String? = null
    private var torBound = false
    private val notificationChannelId = "raashub_live_monitoring"
    private val monitoringNotificationId = 4107
    private val torConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { }
        override fun onServiceDisconnected(name: ComponentName?) { torBound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createMonitoringNotificationChannel()
        runCatching {
            torBound = bindService(Intent(this, TorService::class.java), torConnection, BIND_AUTO_CREATE)
        }
        setContent { HubApp(readSources()) }
    }

    private fun createMonitoringNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(notificationChannelId, "Live monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress and completion updates for RaaSHub live URL checks"
                setShowBadge(true)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4108)
        }
    }

    fun showMonitoringNotification(completed: Int, total: Int, finished: Boolean, message: String = "") {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val title = if (finished) "Live monitoring completed" else "RaaSHub live monitoring"
        val content = if (finished) message.ifBlank { "Checked $total onion URLs." } else "Checking URLs: $completed of $total (${if (total > 0) completed * 100 / total else 0}%)"
        val logo = runCatching { BitmapFactory.decodeResource(resources, R.drawable.raashub_logo) }.getOrNull()
        val notification = NotificationCompat.Builder(this, notificationChannelId)
            .setSmallIcon(R.drawable.raashub_notification)
            .setLargeIcon(logo)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOnlyAlertOnce(true)
            .setOngoing(!finished)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(total.coerceAtLeast(1), completed.coerceIn(0, total.coerceAtLeast(1)), !finished && total <= 0)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(monitoringNotificationId, notification) }
    }

    fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 26) Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
        else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }
    }

    fun openOnionUrl(url: String) {
        if (!Uri.parse(url).host.orEmpty().endsWith(".onion", ignoreCase = true)) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            return
        }
        pendingTorUrl = url
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("org.torproject.torbrowser")
            })
            pendingTorUrl = null
        } catch (_: ActivityNotFoundException) {
            val marketIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://details?id=org.torproject.torbrowser")
            )
            try {
                startActivity(marketIntent)
            } catch (_: Exception) {
                runCatching {
                    startActivity(Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=org.torproject.torbrowser")
                    ))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val pending = pendingTorUrl ?: return
        val installed = try {
            packageManager.getPackageInfo("org.torproject.torbrowser", 0)
            true
        } catch (_: Exception) {
            false
        }
        if (installed) {
            pendingTorUrl = null
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pending)).apply {
                    setPackage("org.torproject.torbrowser")
                })
            }
        }
    }

    override fun onDestroy() {
        if (torBound) runCatching { unbindService(torConnection) }
        torBound = false
        super.onDestroy()
    }

    private fun readSources(): List<OnionEntry> = runCatching {
        val array = JSONArray(assets.open("onion_sources.json").bufferedReader().use { it.readText() })
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                var url = item.optString("url").trim()
                if (url.isBlank()) continue
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) url = "http://$url"
                add(OnionEntry(url, item.optString("entity", "Unnamed source"), item.optString("source", "Source list"), item.optString("category", "other").lowercase()))
            }
        }.distinctBy { it.url.lowercase() }.sortedBy { it.host.lowercase() }
    }.getOrDefault(emptyList())
}

private enum class Tab(val title: String) { HOME("Home"), SOURCES("Sources"), ACTIVITY("Activity"), SETTINGS("Settings") }

@Composable
private fun HubApp(sources: List<OnionEntry>) {
    val context = LocalContext.current
    val themePrefs = remember { context.getSharedPreferences("raashub_appearance_prefs", android.content.Context.MODE_PRIVATE) }
    var darkMode by remember { mutableStateOf(themePrefs.getBoolean("dark_mode", true)) }
    val notificationPrefs = remember { context.getSharedPreferences("raashub_notification_prefs", android.content.Context.MODE_PRIVATE) }
    var showNotificationPrompt by remember { mutableStateOf(Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !notificationPrefs.getBoolean("prompt_shown", false)) }
    val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (darkMode) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkMode) darkColorScheme(primary = Color(0xFF6CE8FF), secondary = Color(0xFFB39BFF), background = Color(0xFF0A1020), surface = Color(0xFF141D32))
    else lightColorScheme(primary = Color(0xFF006A83), secondary = Color(0xFF6553A5))
    var tab by remember { mutableStateOf(Tab.HOME) }
    var search by remember { mutableStateOf("") }
    var sourceFilter by remember { mutableStateOf("all") }
    var selected by remember { mutableStateOf<OnionGroup?>(null) }
    var checking by remember { mutableStateOf(false) }
    var healthReport by remember { mutableStateOf(loadLatestHealthReport(context)) }
    var progressDone by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }
    var lastBackPressed by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val runHealthChecks: () -> Unit = {
        if (!checking) {
            checking = true
            progressDone = 0
            progressTotal = sources.count { it.host.endsWith(".onion", true) }
            (context as? MainActivity)?.showMonitoringNotification(0, progressTotal, false)
            scope.launch {
                val completedReport = checkOnionSources(sources) { done, total ->
                    progressDone = done
                    progressTotal = total
                    (context as? MainActivity)?.showMonitoringNotification(done, total, false)
                }
                healthReport = completedReport
                saveLatestHealthReport(context, completedReport)
                checking = false
                (context as? MainActivity)?.showMonitoringNotification(
                    progressTotal, progressTotal, true,
                    completedReport.message
                )
            }
        }
    }
    BackHandler(enabled = true) {
        when {
            selected != null -> selected = null
            tab != Tab.HOME -> { tab = Tab.HOME; search = "" }
            System.currentTimeMillis() - lastBackPressed < 2000L -> (context as? Activity)?.finish()
            else -> { lastBackPressed = System.currentTimeMillis(); Toast.makeText(context, "Press back again to exit RaaSHub", Toast.LENGTH_SHORT).show() }
        }
    }
    MaterialTheme(colorScheme = scheme) {
        if (showNotificationPrompt) {
            AlertDialog(
                onDismissRequest = {
                    showNotificationPrompt = false
                    notificationPrefs.edit().putBoolean("prompt_shown", true).apply()
                },
                icon = { Icon(Icons.Default.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary) },
                title = { Text("Enable RaaSHub notifications?") },
                text = { Text("Allow notifications to see live-monitoring progress, percentage updates, and when checks finish. Android will show its permission dialog next.") },
                confirmButton = {
                    TextButton(onClick = {
                        showNotificationPrompt = false
                        notificationPrefs.edit().putBoolean("prompt_shown", true).apply()
                        (context as? MainActivity)?.requestNotificationPermissionIfNeeded()
                    }) { Text("Allow notifications") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showNotificationPrompt = false
                        notificationPrefs.edit().putBoolean("prompt_shown", true).apply()
                    }) { Text("Not now") }
                }
            )
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    Text("Developed With ♥️ AnonVox", Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    NavigationBar {
                    Tab.entries.forEach { t ->
                        val icon = when (t) { Tab.HOME -> Icons.Default.Dashboard; Tab.SOURCES -> Icons.Default.Hub; Tab.ACTIVITY -> Icons.Default.Timeline; Tab.SETTINGS -> Icons.Default.Settings }
                        NavigationBarItem(selected = tab == t, onClick = { tab = t; selected = null }, icon = { Icon(icon, t.title) }, label = { Text(t.title) })
                    }
                    }
                }
            }
        ) { inset ->
            Column(Modifier.fillMaxSize().padding(inset)) {
                Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.tertiaryContainer))).padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.Image(painter = painterResource(id = R.drawable.raashub_logo), contentDescription = "RaaSHub logo", modifier = Modifier.size((LocalConfiguration.current.screenWidthDp * 0.12f).coerceIn(40f, 54f).dp).clip(RoundedCornerShape(14.dp)).background(Color.Black), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("RaaSHub", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                            Text("CYBER THREAT INTELLIGENCE", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, letterSpacing = 1.2.sp)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Know your threat landscape.", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
                when {
                    selected != null -> Detail(selected!!, healthReport?.results.orEmpty(), onBack = { selected = null }, onCopy = { url ->
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("RaaSHub onion URL", url))
                    }, onOpen = { url -> if (context is MainActivity) context.openOnionUrl(url) else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("org.torproject.torbrowser")) } })
                    tab == Tab.HOME -> Home(sources, checking, healthReport, runHealthChecks) { tab = Tab.SOURCES }
                    tab == Tab.SOURCES -> Sources(sources, search, { search = it }, sourceFilter, { sourceFilter = it }) { selected = it }
                    tab == Tab.ACTIVITY -> Activity(sources, checking, healthReport, progressDone, progressTotal, runHealthChecks)
                    else -> Settings(healthReport, sources.size, darkMode, { enabled -> darkMode = enabled; themePrefs.edit().putBoolean("dark_mode", enabled).apply() }) { (context as? MainActivity)?.openNotificationSettings() } 
                }
            }
        }
    }
}

@Composable
private fun Home(sources: List<OnionEntry>, checking: Boolean, report: HealthReport?, onRunChecks: () -> Unit, onExplore: () -> Unit) {
    val groups = remember(sources) { groupSources(sources) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("ONION URLS", sources.count { it.host.endsWith(".onion", true) }.toString(), Icons.Default.Hub, Modifier.weight(1f))
                Metric("GROUPS", groups.size.toString(), Icons.Default.Groups, Modifier.weight(1f))
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Intelligence snapshot", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    StateLine("Live onion availability", report?.let { "${it.results.size} checked" } ?: "Not checked", MaterialTheme.colorScheme.onSurfaceVariant)
                    StateLine("Network route", when { checking -> "Checking…"; report?.torAvailable == true -> "Connected"; report != null -> "Unavailable"; else -> "Not checked" }, MaterialTheme.colorScheme.onSurfaceVariant)
                    if (report?.torAvailable == false) Text("Live checks unavailable", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onRunChecks, enabled = !checking && sources.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        if (checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.NetworkCheck, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (checking) "Checking all onion URLs…" else "Check all onion URLs")
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().clickable(onClick = onExplore), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Explore sources", fontWeight = FontWeight.Bold); Text("Browse groups, markets and their onion/clear-web URLs.", fontSize = 12.sp) }
                    Icon(Icons.Default.ArrowForward, null)
                }
            }
        }
        item { Text("Source groups", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        items(groups.take(5)) { SourceCard(it) { onExplore() } }
    }
}

@Composable
private fun Metric(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(15.dp)) {
            Icon(icon, null, tint = Color(0xFF18BFD2), modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(12.dp))
            Text(value, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StateLine(label: String, value: String, tint: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.weight(1f), fontSize = 13.sp)
        Text(value, fontSize = 12.sp, color = tint)
    }
}

@Composable
private fun Sources(sources: List<OnionEntry>, query: String, onQuery: (String) -> Unit, sourceFilter: String, onFilter: (String) -> Unit, onSelect: (OnionGroup) -> Unit) {
    val groups = remember(sources) { groupSources(sources) }
    val categories = remember(sources) { sources.map { it.category.lowercase() }.distinct().sorted() }
    val filtered = remember(groups, query, sourceFilter) { groups.filter { group ->
        val isForum = group.name.contains("forum", true) || group.categories.any { it.contains("forum", true) } || group.urls.any { it.contains("forum", true) || Uri.parse(it).host.orEmpty().contains("forum", true) }
        val matchesFilter = when (sourceFilter) {
            "all" -> true
            "forums" -> isForum
            else -> group.categories.any { it.equals(sourceFilter, true) }
        }
        matchesFilter && (query.isBlank() || group.name.contains(query, true) || group.urls.any { it.contains(query, true) })
    } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().padding(top = 12.dp), placeholder = { Text("Search group or URL") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val filterOptions = (listOf("all", "forums") + categories).distinct()
            filterOptions.forEach { key ->
                val label = when (key) {
                    "all" -> "All"
                    "group" -> "Groups"
                    "market" -> "Markets"
                    "forums" -> "Forums"
                    "other" -> "Other"
                    else -> key.replace("-", " ").replaceFirstChar { it.uppercase() }
                }
                FilterChip(selected = sourceFilter == key, onClick = { onFilter(key) }, label = { Text(label) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${filtered.size} groups", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("${filtered.sumOf { it.urls.size }} URLs", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (sources.isEmpty()) "No sources available." else "No matching sources") }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(filtered, key = { it.name }) { group -> SourceCard(group) { onSelect(group) } }
        }
    }
}

@Composable
private fun SourceCard(group: OnionGroup, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(17.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF8B69E8).copy(alpha = .15f)) { Icon(Icons.Default.Language, null, Modifier.padding(9.dp), tint = Color(0xFF9B80FF)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(group.urls.size.toString() + " URLs", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    Text(group.urls.take(2).joinToString("  •  ") { Uri.parse(it).host ?: it }, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

        }
    }
}

@Composable
private fun Detail(group: OnionGroup, health: Map<String, HealthResult>, onBack: () -> Unit, onCopy: (String) -> Unit, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        TextButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null); Text("Back") }
        Text(group.name, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
        Text("${group.urls.size} URLs • ${group.categories.joinToString { it.replaceFirstChar { ch -> ch.uppercase() } }}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(group.entries, key = { it.url }) { entry ->
                val result = health[entry.url]
                val statusLabel = when (result?.status) {
                    "Online" -> "Active"
                    "Responding" -> "Active (HTTP response)"
                    "Server error" -> "Server error"
                    "Unverified" -> "Unverified"
                    else -> "Not checked"
                }
                val statusColor = when (result?.status) {
                    "Online" -> Color(0xFF16B887)
                    "Responding" -> Color(0xFFB88922)
                    "Server error" -> Color(0xFFE58B35)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val host = Uri.parse(entry.url).host ?: entry.url
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(host, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(entry.url, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        StateLine("Available", statusLabel, statusColor)
                        if (result != null) {
                            Text("General", fontWeight = FontWeight.Bold)
                            DetailLine("FQDN", host)
                            DetailLine("Page title", result.title.ifBlank { "Not fetched in headers-only health check" })
                            DetailLine("Type", entry.category.replaceFirstChar { it.uppercase() })
                            DetailLine("Slug / URL", entry.url)
                            DetailLine("Enabled", "Enabled")
                            DetailLine("Updated", formatIst(result.checkedAt))
                            DetailLine("Last scrape", formatIst(result.checkedAt))
                            Text("HTTP Response", fontWeight = FontWeight.Bold)
                            DetailLine("Status", result.responseCode?.toString() ?: "—")
                            DetailLine("Fetched at", formatIst(result.checkedAt))
                            DetailLine("Start URL", entry.url)
                            DetailLine("Final URL", result.finalUrl.ifBlank { entry.url })
                            DetailLine("HTTP version", result.httpVersion)
                            DetailLine("Error", result.error.ifBlank { "—" })
                            Text("Fingerprint", fontWeight = FontWeight.Bold)
                            DetailLine("Content type", result.contentType.ifBlank { "—" })
                            DetailLine("Server", result.server.ifBlank { "—" })
                            DetailLine("Set-Cookie present", result.setCookiePresent.toString())
                            Text("Response Headers", fontWeight = FontWeight.Bold)
                            if (result.headers.isEmpty()) DetailLine("Headers", "Not available")
                            else result.headers.forEach { (name, value) ->
                                DetailLine(name, if (name == "date" || name == "last-modified" || name == "expires") httpDateToIst(value) else value)
                            }
                            Text("Check detail: ${result.detail}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("Run a live check to retrieve HTTP status and response headers. Page content is not downloaded in this fast check.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onCopy(entry.url) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copy") }
                            Button(onClick = { onOpen(entry.url) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(6.dp)); Text("Open") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(118.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" }, Modifier.weight(1f), fontSize = 12.sp)
    }
}

@Composable
private fun Activity(sources: List<OnionEntry>, checking: Boolean, report: HealthReport?, progressDone: Int, progressTotal: Int, onRunChecks: () -> Unit) {
    val groups = remember(sources) { groupSources(sources) }
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Activity", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Health check results", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Card(shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Inventory2, null, tint = Color(0xFF16B8A6), modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column { Text("Source index", fontWeight = FontWeight.SemiBold); Text("${sources.count { it.host.endsWith(".onion", true) }} onion URLs and ${sources.size - sources.count { it.host.endsWith(".onion", true) }} clear-web URLs in ${groups.size} groups", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Card(shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                val pulse = rememberInfiniteTransition(label = "monitoringPulse")
                val rotation by pulse.animateFloat(initialValue = 0f, targetValue = if (checking) 360f else 0f, animationSpec = infiniteRepeatable(animation = tween(1800, easing = LinearEasing), repeatMode = RepeatMode.Restart), label = "monitoringRotation")
                Icon(Icons.Default.PendingActions, null, tint = Color(0xFFB58AFF), modifier = Modifier.size(28.dp).rotate(rotation))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Live monitoring", fontWeight = FontWeight.SemiBold)
                    Text(if (checking) "Health checks are running…" else report?.message?.takeIf { report.torAvailable } ?: "No live checks yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (checking) {
                        val percent = if (progressTotal > 0) progressDone * 100 / progressTotal else 0
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("$percent%", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.weight(1f))
                            Text("$progressDone / $progressTotal URLs", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        LinearProgressIndicator(progress = { if (progressTotal > 0) progressDone.toFloat() / progressTotal else 0f }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape))
                        Spacer(Modifier.height(4.dp))
                        Text("Please keep RaaSHub open for the most reliable check.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onRunChecks, enabled = !checking && sources.isNotEmpty()) {
                        if (checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (checking) "Checking…" else "Run health checks")
                    }
                }
            }
        }
        if (checking && report?.results.isNullOrEmpty()) {
            repeat(3) { SkeletonSourceCard() }
        }
        groups.filter { group -> group.urls.any { report?.results?.containsKey(it) == true } }.forEach { group ->
            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(group.name, fontWeight = FontWeight.SemiBold)
                    group.entries.filter { report?.results?.containsKey(it.url) == true }.forEach { entry ->
                        val result = report!!.results.getValue(entry.url)
                        Text(entry.url, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        StateLine("Result", result.status, when (result.status) {
                            "Online" -> Color(0xFF16B887)
                            "Responding" -> Color(0xFFB88922)
                            "Server error" -> Color(0xFFE58B35)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        })
                        Text(result.detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonSourceCard() {
    val transition = rememberInfiniteTransition(label = "skeletonPulse")
    val alpha by transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.72f,
        animationSpec = infiniteRepeatable(animation = tween(850, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "skeletonAlpha"
    )
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth(0.58f).height(16.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)).graphicsLayer { this.alpha = alpha })
            Box(Modifier.fillMaxWidth(0.92f).height(11.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)).graphicsLayer { this.alpha = alpha })
            Box(Modifier.fillMaxWidth(0.72f).height(11.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)).graphicsLayer { this.alpha = alpha })
        }
    }
}

@Composable
private fun Settings(report: HealthReport?, sourceCount: Int, darkMode: Boolean, onDarkModeChange: (Boolean) -> Unit, onNotificationSettings: () -> Unit) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(Icons.Default.Palette, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Appearance", fontWeight = FontWeight.SemiBold)
                            Text(if (darkMode) "Dark mode is enabled." else "Light mode is enabled.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Choose the app theme; default is dark.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = darkMode, onCheckedChange = onDarkModeChange)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Monitoring notifications", fontWeight = FontWeight.SemiBold)
                            Text("Get start, progress and completion updates while URL checks run.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = onNotificationSettings) { Text("Manage") }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(12.dp))
                        Column { Text("Privacy & routing", fontWeight = FontWeight.SemiBold); Text("Live onion checks route through the local Tor SOCKS proxy. No external CTI backend is configured.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Intelligence sources", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    StateLine("Indexed URLs", sourceCount.toString(), MaterialTheme.colorScheme.primary)
                    StateLine("Last network check", report?.let { if (it.torAvailable) "Completed" else "Unavailable" } ?: "Not run", MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("The source index is bundled with the app and can be browsed offline. Live status requires Tor connectivity.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("About RaaSHub", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("Cyber Threat Intelligence companion", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Version 1.0.0", fontSize = 12.sp)
                    Text("Developed With ♥️ AnonVox", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
