package com.raashub

import android.content.Intent
import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.BitmapFactory
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineDispatcher
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private fun normalizeSourceUrl(raw: String): String? {
    var value = raw.trim().trimEnd('.', ',', ';', ')')
    if (value.isBlank()) return null
    // Repair malformed entries such as https://junk/http://real-service.onion.
    repeat(4) {
        val nested = Regex("(?i)^https?://[^/]+/(https?://.+)$").find(value)
        if (nested != null) value = nested.groupValues[1]
    }
    if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) {
        value = "http://$value"
    }
    if (value.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) return null
    val parsed = Uri.parse(value)
    val host = parsed.host?.lowercase() ?: return null
    if (parsed.scheme?.lowercase() !in setOf("http", "https")) return null
    if (parsed.encodedAuthority?.contains("@") == true) return null
    if (host.endsWith(".onion") && !Regex("^[a-z2-7]{56}\\.onion$").matches(host)) return null
    return value
}

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
private data class WorkerProgress(val id: String, val status: String, val url: String = "")

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
            result.headers.entries.forEach { (key, value) -> headers.put(key, value) }
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
            status = item.optString("status", "Unconfirmed").takeIf { it in setOf("Online", "Redirect", "Protected", "Client error", "Server error", "Informational", "Unconfirmed") } ?: "Unconfirmed",
            detail = item.optString("detail").ifBlank { "Offline" },
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

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun checkOnionSources(sources: List<OnionEntry>, onProgress: (Int, Int) -> Unit, onWorkerUpdate: (List<WorkerProgress>, String) -> Unit, onChunkComplete: (String, Int, Int, Map<String, HealthResult>) -> Unit = { _, _, _, _ -> }): HealthReport {
    val allSources = sources.distinctBy { it.url.trim().lowercase() }
    if (allSources.isEmpty()) return HealthReport(false, null, "No URLs are available to check.", emptyMap())

    // Classify first: clear-web and onion URLs must never share a connection path.
    val onionSources = allSources.filter { it.host.endsWith(".onion", ignoreCase = true) }
    val clearWebSources = allSources.filterNot { it.host.endsWith(".onion", ignoreCase = true) }

    withContext(Dispatchers.Main) {
        onProgress(0, allSources.size)
        onWorkerUpdate(
            listOf(
                WorkerProgress("Clear-web crawler", if (clearWebSources.isEmpty()) "No URLs" else "Starting direct checks", "Direct HTTP/HTTPS; no Tor"),
                WorkerProgress("Onion crawler", if (onionSources.isEmpty()) "No URLs" else "Preparing Tor independently", "Tor SOCKS; onion URLs only")
            ),
            "Two independent crawlers started. Clear-web uses direct internet connections; .onion URLs use Tor only."
        )
    }

    // Tor uses a lower concurrency cap to avoid saturating the local Tor daemon.
    // Clear-web checks continue independently while Tor starts or is unavailable.
    val clearWebSlots = 32
    val onionSlots = 24
    val networkDispatcher = Dispatchers.IO.limitedParallelism(96)
    val completed = AtomicInteger(0)
    val activeUrls = AtomicInteger(0)
    val peakActiveUrls = AtomicInteger(0)
    val results = java.util.concurrent.ConcurrentHashMap<String, HealthResult>()
    val liveWorkers = java.util.concurrent.ConcurrentHashMap<String, WorkerProgress>()
    val liveLog = java.util.concurrent.ConcurrentLinkedDeque<String>()
    val lastWorkerUiUpdate = AtomicLong(0L)
    val lastProgressUiUpdate = AtomicLong(0L)

    suspend fun publishWorker(id: String, status: String, url: String = "", log: String) {
        liveWorkers[id] = WorkerProgress(id, status, url)
        liveLog.addLast("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}  $log")
        while (liveLog.size > 80) liveLog.pollFirst()
        val now = System.currentTimeMillis()
        val urgent = status in setOf("Tor unavailable", "Tor ready", "Completed", "Failed")
        while (true) {
            val previous = lastWorkerUiUpdate.get()
            if (!urgent && now - previous < 250L) break
            if (lastWorkerUiUpdate.compareAndSet(previous, now)) {
                val snapshot = liveWorkers.values.sortedBy { it.id }
                withContext(Dispatchers.Main) { onWorkerUpdate(snapshot, liveLog.joinToString("\n")) }
                break
            }
        }
    }

    suspend fun runCrawler(
        crawlerName: String,
        entries: List<OnionEntry>,
        slotCount: Int,
        proxyPort: Int?
    ) {
        if (entries.isEmpty()) {
            publishWorker(crawlerName, "Idle", log = "$crawlerName has no URLs assigned")
            return
        }
        // Deterministic, non-overlapping chunks: each crawler owns its own URL set.
        // Process at most 100 URLs per chunk. Every chunk is committed before the next starts.
        val chunkSize = 100
        val chunks = entries.distinctBy { it.url.trim().lowercase() }.chunked(chunkSize)
        chunks.forEachIndexed { chunkIndex, chunkEntries ->
            val effectiveSlotCount = minOf(slotCount, chunkEntries.size).coerceAtLeast(1)
            val queue = Channel<OnionEntry>(capacity = effectiveSlotCount * 2)
            coroutineScope {
                val producer = launch {
                    try { chunkEntries.forEach { queue.send(it) } }
                    finally { queue.close() }
                }
                val slots = (0 until effectiveSlotCount).map { slotIndex ->
                    launch {
                        val workerId = "$crawlerName · Chunk ${chunkIndex + 1} · Slot ${slotIndex + 1}"
                        publishWorker(workerId, "Waiting", log = "$workerId ready")
                        for (entry in queue) {
                            val active = activeUrls.incrementAndGet()
                            peakActiveUrls.updateAndGet { peak -> maxOf(peak, active) }
                            publishWorker(workerId, "Checking", entry.url, "$workerId checking ${entry.url}")
                            try {
                                val result = try {
                                    checkUrlSchemes(entry.url, proxyPort, networkDispatcher)
                                } catch (e: Exception) {
                                    val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                                    HealthResult("Unconfirmed", "No response after retry policy", System.currentTimeMillis(), finalUrl = entry.url, error = reason)
                                }
                                results.putIfAbsent(entry.url, result)
                                publishWorker(workerId, result.status, entry.url, "$workerId finished: ${result.status} — ${result.detail}")
                            } finally {
                                activeUrls.decrementAndGet()
                                val done = completed.incrementAndGet()
                                val now = System.currentTimeMillis()
                                val previous = lastProgressUiUpdate.get()
                                if (done == allSources.size || now - previous >= 250L) {
                                    if (lastProgressUiUpdate.compareAndSet(previous, now) || done == allSources.size) {
                                        withContext(Dispatchers.Main) { onProgress(done, allSources.size) }
                                    }
                                }
                            }
                        }
                    }
                }
                producer.join()
                slots.joinAll()
            }
            // Commit this chunk immediately. A process interruption never loses earlier chunks.
            val chunkResults = results.toMap()
            withContext(Dispatchers.Main) {
                onChunkComplete(crawlerName, chunkIndex + 1, chunks.size, chunkResults)
            }
            publishWorker(crawlerName, "Chunk saved", log = "$crawlerName chunk ${chunkIndex + 1}/${chunks.size} saved (${chunkEntries.size} URLs; ${chunkResults.size} unique results total).")
        }
    }

    var onionProxyPort: Int? = null
    kotlinx.coroutines.coroutineScope {
        // This job starts immediately and uses direct sockets for non-onion hosts.
        val clearWebJob = launch {
            runCrawler("Clear-web crawler", clearWebSources, clearWebSlots, null)
        }

        // Tor discovery/circuit readiness runs only for onion URLs and never gates clear-web.
        val onionJob = launch {
            val proxyPort = if (onionSources.isEmpty()) null else withContext(Dispatchers.IO) {
                // A public exit-test host may be blocked even while onion routing works.
                // Only require the local SOCKS listener; let Tor establish the onion circuit.
                val listenerDeadline = System.currentTimeMillis() + 30000L
                var port: Int? = null
                while (port == null && System.currentTimeMillis() < listenerDeadline) {
                    port = findTorSocksProxy()
                    if (port == null) kotlinx.coroutines.delay(500)
                }
                if (port != null) kotlinx.coroutines.delay(2500)
                port
            }
            onionProxyPort = proxyPort
            publishWorker(
                "Onion crawler",
                if (proxyPort == null && onionSources.isNotEmpty()) "Tor unavailable" else "Tor ready",
                if (proxyPort == null) "No Tor route; clear-web continues independently" else "SOCKS port $proxyPort",
                log = if (proxyPort == null && onionSources.isNotEmpty())
                    "No Tor SOCKS listener was found; onion results will be marked Unconfirmed, not Offline. Clear-web crawler is not affected."
                else "Tor SOCKS listener found; onion-only crawler starting."
            )
            runCrawler("Onion crawler", onionSources, onionSlots, proxyPort)
        }

        clearWebJob.join()
        onionJob.join()
    }

    publishWorker("Scan", "Completed", log = "Scan completed: ${completed.get()} URLs processed.")
    val finalResults = results.toMap()
    val responded = finalResults.values.count { it.responseCode != null }
    val offline = finalResults.size - responded
    val successful = finalResults.values.count { result -> result.responseCode?.let { it in 200..299 } == true }
    val message = "Processed ${finalResults.size}/${allSources.size} URLs. Clear-web: ${clearWebSources.size} direct; onion: ${onionSources.size} through Tor only. Concurrency: up to 32 direct + 24 Tor jobs. Each protocol attempt has a 7-second network deadline; the alternate protocol is tried only if the first does not respond. Peak active URL jobs: ${peakActiveUrls.get()}. $responded responded ($successful successful 2xx); $offline had no HTTP response."
    return HealthReport(onionSources.isEmpty() || onionProxyPort != null, onionProxyPort, message, finalResults)
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

private suspend fun checkUrlSchemes(urlText: String, proxyPort: Int?, networkDispatcher: CoroutineDispatcher): HealthResult {
    suspend fun probeWithRetry(target: String): HealthResult {
        var last = HealthResult("Unconfirmed", "No HTTP response", System.currentTimeMillis(), finalUrl = target)
        // Slow hosts and Tor circuits get one longer retry. HTTP responses (including
        // CAPTCHA/403/429 challenges) are real reachability signals and are not retried.
        val deadlines = intArrayOf(15000, 25000)
        deadlines.forEachIndexed { index, timeout ->
            last = kotlinx.coroutines.withContext(networkDispatcher) {
                checkOneOnionAttempt(target, proxyPort, timeout)
            }
            if (last.responseCode != null) return last
            if (index < deadlines.lastIndex) kotlinx.coroutines.delay(900L * (index + 1))
        }
        return last.copy(status = "Unconfirmed", detail = "No HTTP response after retry", error = last.error.ifBlank { "No response after 2 attempts" })
    }
    val uri = Uri.parse(urlText)
    val scheme = (uri.scheme ?: "http").lowercase()
    val alternate = if (scheme == "https") "http" else "https"
    val primaryUrl = uri.buildUpon().scheme(scheme).build().toString()
    val alternateUrl = uri.buildUpon().scheme(alternate).build().toString()

    // A responding primary endpoint is enough for an availability check.
    // Only spend time on the alternate protocol when the declared one fails.
    val primary = probeWithRetry(primaryUrl)
    if (primary.responseCode != null) {
        val headers = linkedMapOf<String, String>().apply {
            putAll(primary.headers)
            put("x-raashub-$scheme-probe", "HTTP ${primary.responseCode}")
            put("x-raashub-$alternate-probe", "Not attempted (primary responded)")
        }
        return primary.copy(headers = headers, detail = "${scheme.uppercase()}: HTTP ${primary.responseCode}")
    }

    val fallback = probeWithRetry(alternateUrl)
    val selected = if (fallback.responseCode != null) fallback else primary
    val headers = linkedMapOf<String, String>().apply {
        putAll(selected.headers)
        put("x-raashub-$scheme-probe", primary.error.ifBlank { primary.status })
        put("x-raashub-$alternate-probe", fallback.responseCode?.let { "HTTP $it" } ?: fallback.error.ifBlank { fallback.status })
    }
    val reachable = fallback.responseCode != null
    return selected.copy(
        detail = if (reachable) "${alternate.uppercase()}: HTTP ${fallback.responseCode}" else
            listOf(primary.error, fallback.error).filter { it.isNotBlank() }.distinct().joinToString("; ").ifBlank { "No HTTP response" },
        finalUrl = selected.finalUrl.ifBlank { if (reachable) alternateUrl else primaryUrl },
        headers = headers,
        status = selected.responseCode?.let { statusForHttpResponse(it, selected.headers) } ?: "Unconfirmed",
        error = if (reachable) "" else listOf(primary.error, fallback.error).filter { it.isNotBlank() }.distinct().joinToString("; ").ifBlank { selected.error }
    )
}

private fun statusForHttpCode(code: Int): String = when (code) {
    in 100..199 -> "Informational"
    in 200..299 -> "Online"
    in 300..399 -> "Redirect"
    in 400..499 -> "Client error"
    in 500..599 -> "Server error"
    else -> "Unconfirmed"
}

private fun statusForHttpResponse(code: Int, headers: Map<String, String>): String {
    val normalized = headers.mapKeys { it.key.lowercase() }.mapValues { it.value.lowercase() }
    val challengeSignal = normalized.keys.any { key ->
        key.contains("captcha") || key.contains("challenge") || key.startsWith("cf-")
    } || normalized["cf-mitigated"] == "challenge" ||
        normalized["server"].orEmpty().contains("cloudflare") ||
        normalized["x-sucuri-block"].orEmpty().isNotBlank()
    return if (code in setOf(401, 403, 429) || (code == 503 && challengeSignal) || challengeSignal) {
        "Protected"
    } else statusForHttpCode(code)
}

private fun checkOneOnionAttempt(urlText: String, proxyPort: Int?, readTimeoutMs: Int): HealthResult {
    val checkedAt = System.currentTimeMillis()
    val isOnion = Uri.parse(urlText).host?.endsWith(".onion", ignoreCase = true) == true
    return try {
        val uri = Uri.parse(urlText)
        val host = uri.host ?: throw IOException("Invalid source URL")
        val scheme = (uri.scheme ?: "http").lowercase()
        if (scheme != "http" && scheme != "https") throw IOException("Unsupported URL scheme")
        if (isOnion && proxyPort == null) throw IOException("Tor proxy unavailable; onion URL not checked")
        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
        val deadlineNanos = System.nanoTime() + readTimeoutMs * 1_000_000L
        val raw = Socket()
        raw.soTimeout = readTimeoutMs
        try {
            if (isOnion) {
                raw.connect(InetSocketAddress("127.0.0.1", proxyPort!!), minOf(1800, readTimeoutMs))
                val out = raw.getOutputStream()
                out.write(byteArrayOf(5, 1, 0)); out.flush()
                val greeting = ByteArray(2); readFullyBeforeDeadline(raw, greeting, deadlineNanos)
                if (greeting[0].toInt() != 5 || greeting[1].toInt() != 0) throw IOException("SOCKS5 negotiation failed")
                val hostBytes = host.toByteArray(StandardCharsets.US_ASCII)
                if (hostBytes.size > 255) throw IOException("Invalid onion hostname")
                out.write(byteArrayOf(5, 1, 0, 3, hostBytes.size.toByte()))
                out.write(hostBytes)
                out.write(byteArrayOf((port shr 8).toByte(), port.toByte())); out.flush()
                val reply = ByteArray(4); readFullyBeforeDeadline(raw, reply, deadlineNanos)
                if (reply[0].toInt() != 5 || reply[1].toInt() != 0) throw IOException("Tor SOCKS reply ${reply[1].toInt() and 0xff}")
                when (reply[3].toInt() and 0xff) {
                    1 -> skipFullyBeforeDeadline(raw, 4, deadlineNanos)
                    3 -> { applyReadDeadline(raw, deadlineNanos); val length = raw.getInputStream().read(); if (length < 0) throw IOException("Incomplete SOCKS reply"); skipFullyBeforeDeadline(raw, length, deadlineNanos) }
                    4 -> skipFullyBeforeDeadline(raw, 16, deadlineNanos)
                    else -> throw IOException("Invalid SOCKS address type")
                }
                skipFullyBeforeDeadline(raw, 2, deadlineNanos)
            } else {
                raw.connect(InetSocketAddress(host, port), minOf(2500, readTimeoutMs))
            }
            val active: Socket = if (scheme == "https") {
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
                ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                applyReadDeadline(ssl, deadlineNanos)
                ssl.startHandshake()
                ssl
            } else raw
            active.use { connection ->
                applyReadDeadline(connection, deadlineNanos)
                val path = (uri.encodedPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.encodedQuery?.let { "?$it" } ?: "")
                val defaultPort = if (scheme == "https") 443 else 80
                val hostHeader = if (port == defaultPort) host else "$host:$port"
                val request = "GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUser-Agent: RaaSHub-HealthCheck/1.1\r\nRange: bytes=0-0\r\nConnection: close\r\n\r\n"
                connection.getOutputStream().write(request.toByteArray(StandardCharsets.ISO_8859_1))
                connection.getOutputStream().flush()
                val reader = BufferedReader(InputStreamReader(connection.getInputStream(), StandardCharsets.ISO_8859_1))
                applyReadDeadline(connection, deadlineNanos)
                val statusLine = reader.readLine() ?: throw IOException("Empty HTTP response")
                val match = Regex("^HTTP/(\\d(?:\\.\\d)?)\\s+(\\d{3})").find(statusLine)
                    ?: throw IOException("Unrecognised HTTP response")
                val httpVersion = "HTTP/${match.groupValues[1]}"
                val code = match.groupValues[2].toInt()
                val headers = linkedMapOf<String, String>()
                var headerBytes = 0
                var headerCount = 0
                while (true) {
                    applyReadDeadline(connection, deadlineNanos)
                    val line = reader.readLine() ?: break
                    headerBytes += line.length + 2
                    headerCount++
                    if (headerBytes > 65536 || headerCount > 100) throw IOException("HTTP response headers exceed the safety limit")
                    if (line.isEmpty()) break
                    val split = line.indexOf(':')
                    if (split > 0) {
                        val name = line.substring(0, split).trim().lowercase()
                        val value = line.substring(split + 1).trim().take(2048)
                        headers[name] = if (headers.containsKey(name)) (headers.getValue(name) + ", " + value).take(2048) else value
                    }
                }
                // Fast health-check mode reads response headers only. Avoid downloading
                // page bodies from hundreds of slow onion services during status scans.
                val title = ""
                val contentType = headers["content-type"].orEmpty()
                val server = headers["server"].orEmpty()
                val detail = "HTTP $code • ${if (code in 200..399) "responded" else "server responded"}"
                val status = statusForHttpResponse(code, headers)
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
        val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        HealthResult("Offline", "No HTTP response", checkedAt, finalUrl = urlText, error = reason)
    }
}

private fun applyReadDeadline(socket: Socket, deadlineNanos: Long) {
    val remainingNanos = deadlineNanos - System.nanoTime()
    if (remainingNanos <= 0L) throw java.net.SocketTimeoutException("Health-check attempt exceeded its time limit")
    val remainingMs = ((remainingNanos + 999_999L) / 1_000_000L).coerceIn(1L, Int.MAX_VALUE.toLong())
    socket.soTimeout = remainingMs.toInt()
}

private fun readFullyBeforeDeadline(socket: Socket, buffer: ByteArray, deadlineNanos: Long) {
    var offset = 0
    while (offset < buffer.size) {
        applyReadDeadline(socket, deadlineNanos)
        val count = socket.getInputStream().read(buffer, offset, buffer.size - offset)
        if (count < 0) throw IOException("Unexpected end of SOCKS response")
        offset += count
    }
}

private fun skipFullyBeforeDeadline(socket: Socket, amount: Int, deadlineNanos: Long) {
    repeat(amount) {
        applyReadDeadline(socket, deadlineNanos)
        if (socket.getInputStream().read() < 0) throw IOException("Unexpected end of SOCKS response")
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
        HealthCheckScheduler.schedule(this)
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

    fun isBackgroundUnrestricted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    fun requestBackgroundUnrestricted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || isBackgroundUnrestricted()) return
        val request = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(request) }.onFailure { openBackgroundSettings() }
    }

    fun openBackgroundSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
    }

    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
    }

    fun showMonitoringNotification(completed: Int, total: Int, finished: Boolean, message: String = "") {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val title = if (finished) "Live monitoring completed" else "RaaSHub live monitoring"
        val content = if (finished) message.ifBlank { "Checked $total URLs." } else "Checking URLs: $completed of $total (${if (total > 0) completed * 100 / total else 0}%)"
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
                val url = normalizeSourceUrl(item.optString("url")) ?: continue
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
    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    val backgroundPrefs = remember { context.getSharedPreferences("raashub_background_prefs", android.content.Context.MODE_PRIVATE) }
    var showSetupPrompt by remember { mutableStateOf(
        !backgroundPrefs.getBoolean("setup_prompt_handled", false) &&
            ((Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !powerManager.isIgnoringBatteryOptimizations(context.packageName)))
    ) }
    // RaaSHub palette is intentionally limited to pink, blue and near-black.
    // Keep text high-contrast; use solid card surfaces so theme alpha never
    // blends text/cards with the page background and makes labels unreadable.
    val scheme = if (darkMode) {
        darkColorScheme(
            primary = Color(0xFFD8BC83), onPrimary = Color(0xFF17140E),
            secondary = Color(0xFF9C8B6B), onSecondary = Color(0xFF17140E),
            tertiary = Color(0xFFE9D8B2),
            background = Color(0xFF0B0D10), surface = Color(0xFF15191E),
            surfaceVariant = Color(0xFF20262D), onSurface = Color(0xFFF4F0E8),
            onSurfaceVariant = Color(0xFFB9B4AA), outline = Color(0xFF514B40),
            primaryContainer = Color(0xFF30291D), onPrimaryContainer = Color(0xFFF4E7C9),
            secondaryContainer = Color(0xFF292820), onSecondaryContainer = Color(0xFFE7DDC7)
        )
    } else {
        lightColorScheme(
            primary = MaterialTheme.colorScheme.primary, onPrimary = Color(0xFF0A0A0A),
            secondary = MaterialTheme.colorScheme.primary, onSecondary = Color(0xFFFFFFFF),
            tertiary = Color(0xFF9A7D48), background = Color(0xFFF5F2EB),
            surface = Color(0xFFFFFEFB), surfaceVariant = Color(0xFFECE7DC),
            onSurface = Color(0xFF24231F), onSurfaceVariant = Color(0xFF625D53),
            outline = Color(0xFFD1C8B8),
            primaryContainer = Color(0xFFEDE2C9), onPrimaryContainer = Color(0xFF332A1C),
            secondaryContainer = Color(0xFFE8E2D7), onSecondaryContainer = Color(0xFF38342C)
        )
    }
    val premiumTypography = Typography().let { base ->
        base.copy(
            displayLarge = base.displayLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, lineHeight = 54.sp),
            headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, lineHeight = 38.sp),
            headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
            titleLarge = base.titleLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
            titleMedium = base.titleMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
            bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.SansSerif, lineHeight = 24.sp),
            bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.SansSerif, lineHeight = 21.sp),
            bodySmall = base.bodySmall.copy(fontFamily = FontFamily.SansSerif, lineHeight = 18.sp),
            labelLarge = base.labelLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp)
        )
    }
    var tab by remember { mutableStateOf(Tab.HOME) }
    var search by remember { mutableStateOf("") }
    var sourceFilter by remember { mutableStateOf("all") }
    var selected by remember { mutableStateOf<OnionGroup?>(null) }
    var checking by remember { mutableStateOf(false) }
    var healthReport by remember { mutableStateOf(loadLatestHealthReport(context)) }
    var progressDone by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }
    var workerProgress by remember { mutableStateOf<List<WorkerProgress>>(emptyList()) }
    var activityLog by remember { mutableStateOf("Waiting for a scan to start.") }
    var lastBackPressed by remember { mutableLongStateOf(0L) }
    val runHealthChecks: () -> Unit = {
        if (!checking) {
            checking = true
            progressDone = 0
            progressTotal = sources.distinctBy { it.url.lowercase() }.size
            activityLog = "Starting background health-check service…"
            val intent = Intent(context, HealthCheckService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (error: Exception) {
                checking = false
                val message = "Unable to start background scan: ${error.message ?: error.javaClass.simpleName}"
                activityLog = message
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != HealthCheckService.ACTION_UPDATE) return
                progressDone = intent.getIntExtra("done", progressDone)
                progressTotal = intent.getIntExtra("total", progressTotal)
                checking = intent.getBooleanExtra("running", false)
                activityLog = intent.getStringExtra("log") ?: activityLog
                val workersJson = intent.getStringExtra("workers")
                if (!workersJson.isNullOrBlank()) {
                    workerProgress = runCatching {
                        val array = JSONArray(workersJson)
                        buildList {
                            for (i in 0 until array.length()) {
                                val item = array.optJSONObject(i) ?: continue
                                add(WorkerProgress(item.optString("id"), item.optString("status"), item.optString("url")))
                            }
                        }
                    }.getOrDefault(workerProgress)
                }
                if (intent.getBooleanExtra("reportUpdated", false) || !checking) {
                    healthReport = loadLatestHealthReport(context)
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(HealthCheckService.ACTION_UPDATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    BackHandler(enabled = true) {
        when {
            selected != null -> selected = null
            tab != Tab.HOME -> { tab = Tab.HOME; search = "" }
            System.currentTimeMillis() - lastBackPressed < 2000L -> (context as? Activity)?.finish()
            else -> { lastBackPressed = System.currentTimeMillis(); Toast.makeText(context, "Press back again to exit RaaSHub", Toast.LENGTH_SHORT).show() }
        }
    }
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            window.statusBarColor = scheme.background.toArgb()
            window.navigationBarColor = scheme.background.toArgb()
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !darkMode
                isAppearanceLightNavigationBars = !darkMode
            }
        }
    }
    MaterialTheme(colorScheme = scheme, typography = premiumTypography) {
        if (showSetupPrompt) {
            AlertDialog(
                onDismissRequest = {
                    showSetupPrompt = false
                    backgroundPrefs.edit().putBoolean("setup_prompt_handled", true).apply()
                },
                icon = { Icon(Icons.Default.BatteryChargingFull, null, tint = MaterialTheme.colorScheme.primary) },
                title = { Text("Set up background protection") },
                text = { Text("Allow notifications for live progress, then allow unrestricted battery use so health checks can continue when you switch apps or lock your phone. Android controls this setting; RaaSHub cannot enable it silently.") },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            showSetupPrompt = false
                            backgroundPrefs.edit().putBoolean("setup_prompt_handled", true).apply()
                            (context as? MainActivity)?.requestNotificationPermissionIfNeeded()
                        }) { Text("Notifications") }
                        TextButton(onClick = {
                            showSetupPrompt = false
                            backgroundPrefs.edit().putBoolean("setup_prompt_handled", true).apply()
                            (context as? MainActivity)?.requestBackgroundUnrestricted()
                        }) { Text("Background access") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showSetupPrompt = false
                        backgroundPrefs.edit().putBoolean("setup_prompt_handled", true).apply()
                    }) { Text("Later") }
                }
            )
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    Text("Developed With ♥️ AnonVox", Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp
                    ) {
                    Tab.entries.forEach { t ->
                        val icon = when (t) { Tab.HOME -> Icons.Default.Dashboard; Tab.SOURCES -> Icons.Default.Hub; Tab.ACTIVITY -> Icons.Default.Timeline; Tab.SETTINGS -> Icons.Default.Settings }
                        NavigationBarItem(selected = tab == t, onClick = { tab = t; selected = null }, icon = { Icon(icon, t.title) }, label = { Text(t.title) })
                    }
                    }
                }
            }
        ) { inset ->
            Column(
                Modifier.fillMaxSize()
                    .background(
                        if (darkMode) Brush.linearGradient(listOf(
                            Color(0xFF0B0D10), Color(0xFF11161B), Color(0xFF171A1E)
                        )) else Brush.linearGradient(listOf(
                            Color(0xFFF5F2EB), Color(0xFFEFEAE0), Color(0xFFF9F7F1)
                        ))
                    )
                    .padding(inset)
            ) {
                Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = if (darkMode) 0.20f else 0.12f),
                    MaterialTheme.colorScheme.secondary.copy(alpha = if (darkMode) 0.18f else 0.10f),
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.30f)
                ))).padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.Image(painter = painterResource(id = R.drawable.raashub_logo), contentDescription = "RaaSHub logo", modifier = Modifier.size((LocalConfiguration.current.screenWidthDp * 0.12f).coerceIn(40f, 54f).dp).clip(RoundedCornerShape(14.dp)).background(Color.Black), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("RaaSHub", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("INTELLIGENCE DIRECTORY", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, letterSpacing = 1.6.sp, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Explore sources. Check availability.", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                when {
                    selected != null -> Detail(selected!!, healthReport?.results.orEmpty(), onBack = { selected = null }, onCopy = { url ->
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("RaaSHub onion URL", url))
                    }, onOpen = { url -> if (context is MainActivity) context.openOnionUrl(url) else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("org.torproject.torbrowser")) } })
                    tab == Tab.HOME -> Home(sources, checking, healthReport, runHealthChecks) { tab = Tab.SOURCES }
                    tab == Tab.SOURCES -> Sources(sources, search, { search = it }, sourceFilter, { sourceFilter = it }, healthReport?.results.orEmpty()) { selected = it }
                    tab == Tab.ACTIVITY -> Activity(sources, checking, healthReport, progressDone, progressTotal, workerProgress, activityLog, runHealthChecks)
                    else -> Settings(
                        healthReport, sources.size, darkMode,
                        { enabled -> darkMode = enabled; themePrefs.edit().putBoolean("dark_mode", enabled).apply() },
                        onNotificationSettings = { (context as? MainActivity)?.openNotificationSettings() },
                        onBackgroundSettings = { (context as? MainActivity)?.openBackgroundSettings() },
                        onScheduleSettings = { (context as? MainActivity)?.openExactAlarmSettings() }
                    ) 
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
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.38f))) {
                Column(Modifier.padding(18.dp)) {
                    Text("Intelligence snapshot", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    StateLine("Live source availability", report?.let { "${it.results.size} checked" } ?: "Not checked", MaterialTheme.colorScheme.onSurfaceVariant)
                    StateLine("Network route", when { checking -> "Checking…"; report?.torAvailable == true -> "Connected"; report != null -> "Unavailable"; else -> "Not checked" }, MaterialTheme.colorScheme.onSurfaceVariant)
                    if (report?.torAvailable == false) Text("Live checks unavailable", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onRunChecks, enabled = !checking && sources.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        if (checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.NetworkCheck, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (checking) "Checking all onion URLs…" else "Check all sources")
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().clickable(onClick = onExplore), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f))) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Explore sources", fontWeight = FontWeight.Bold); Text("Browse groups, markets and their onion/clear-web URLs.", fontSize = 12.sp) }
                    Icon(Icons.Default.ArrowForward, null)
                }
            }
        }
        item { Text("Source groups", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        items(groups.take(5)) { SourceCard(it, report?.results.orEmpty()) { onExplore() } }
    }
}

@Composable
private fun Metric(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.38f))) {
        Column(Modifier.padding(15.dp)) {
            Icon(icon, null, tint = Color(0xFF007BA7), modifier = Modifier.size(24.dp))
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
private fun Sources(sources: List<OnionEntry>, query: String, onQuery: (String) -> Unit, sourceFilter: String, onFilter: (String) -> Unit, health: Map<String, HealthResult>, onSelect: (OnionGroup) -> Unit) {
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
            items(filtered, key = { it.name }) { group -> SourceCard(group, health) { onSelect(group) } }
        }
    }
}

@Composable
private fun responseStatusColor(result: HealthResult?): Color = when {
    result == null -> Color(0xFF8792A2)
    result.responseCode?.let { it in 100..199 } == true -> Color(0xFF4DA6FF)
    result.responseCode?.let { it in 200..299 } == true -> Color(0xFF19B77A)
    result.responseCode?.let { it in 300..399 } == true -> Color(0xFF8B7CFF)
    result.status == "Protected" -> Color(0xFFFFB547)
    result.status == "Unconfirmed" -> Color(0xFF8792A2)
    result.responseCode?.let { it in 400..499 } == true -> Color(0xFFFFB547)
    result.responseCode?.let { it in 500..599 } == true -> Color(0xFFFF5D67)
    else -> Color(0xFF8792A2)
}

@Composable
private fun SourceCard(group: OnionGroup, health: Map<String, HealthResult> = emptyMap(), onClick: () -> Unit) {
    val groupResults = group.urls.mapNotNull { health[it] }
    val responses = groupResults.count { it.responseCode != null }
    val latestColor = groupResults.maxByOrNull { it.checkedAt }?.let { responseStatusColor(it) } ?: Color(0xFF8792A2)
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, latestColor.copy(alpha = .38f))) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF007BA7).copy(alpha = .15f)) { Icon(Icons.Default.Language, null, Modifier.padding(9.dp), tint = Color(0xFFFF2D95)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(group.urls.size.toString() + " URLs", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    Text(group.urls.take(2).joinToString("  •  ") { Uri.parse(it).host ?: it }, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (groupResults.isNotEmpty()) {
                        Spacer(Modifier.height(7.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(8.dp), color = latestColor.copy(alpha = .14f)) {
                                Text("$responses/${group.urls.size} responded", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = latestColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                            groupResults.groupBy { it.status }
                                .entries.sortedBy { it.key }.take(4).forEach { (kind, entries) ->
                                    Text("$kind ${entries.size}", fontSize = 10.sp, color = responseStatusColor(entries.firstOrNull()))
                                }
                        }
                    } else {
                        Text("Status not checked yet", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Icon(Icons.Default.ChevronRight, null, tint = latestColor)
            }

        }
    }
}

@Composable
private fun Detail(group: OnionGroup, health: Map<String, HealthResult>, onBack: () -> Unit, onCopy: (String) -> Unit, onOpen: (String) -> Unit) {
    var selectedEntry by remember(group.name) { mutableStateOf<OnionEntry?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        TextButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null); Text("Back") }
        Text(group.name, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
        Text("${group.urls.size} URLs • ${group.categories.joinToString { it.replaceFirstChar { ch -> ch.uppercase() } }}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp)) {
            items(group.entries, key = { it.url }) { entry ->
                val result = health[entry.url]
                val statusLabel = when (result?.status) {
                    "Online" -> "Online"
                    "Redirect" -> "Redirect"
                    "Client error" -> "Client error"
                    "Server error" -> "Server error"
                    "Protected" -> "Protected / CAPTCHA"
                    "Informational" -> "Informational"
                    "Unconfirmed" -> "Unconfirmed"
                    "Offline" -> "Offline"
                    else -> "Not checked"
                }
                val statusColor = responseStatusColor(result)
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, statusColor.copy(alpha = 0.32f))) {
                    Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(entry.host, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.url, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(8.dp), color = statusColor.copy(alpha = 0.14f)) {
                                Text(statusLabel, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(result?.responseCode?.let { "HTTP $it" } ?: "No response code", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { selectedEntry = entry }) {
                                Icon(Icons.Default.Assessment, null, Modifier.size(17.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("View report")
                            }
                        }
                    }
                }
            }
        }
    }
    selectedEntry?.let { entry ->
        UrlReportDialog(
            entry = entry,
            result = health[entry.url],
            onDismiss = { selectedEntry = null },
            onCopy = { onCopy(entry.url) },
            onOpen = { onOpen(entry.url) }
        )
    }
}

@Composable
private fun UrlReportDialog(
    entry: OnionEntry,
    result: HealthResult?,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onOpen: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).heightIn(max = 760.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("URL detailed report", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(entry.host, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close report") }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (result == null) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        DetailLine("URL", entry.url)
                        DetailLine("Category", entry.category)
                        DetailLine("Check status", "Not checked yet")
                        Text("A detailed response report will appear here after this URL has been checked.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 12.dp))
                    }
                } else {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("General", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp, bottom = 3.dp))
                        DetailLine("FQDN / Host", entry.host)
                        DetailLine("Page title", result.title.ifBlank { "Not fetched in headers-only health check" })
                        DetailLine("Type / Category", entry.category)
                        DetailLine("Slug / URL", entry.url)
                        DetailLine("Enabled", "Enabled")
                        DetailLine("Status", result.status)
                        DetailLine("Updated", formatIst(result.checkedAt))
                        DetailLine("Last checked", formatIst(result.checkedAt))
                        Text("HTTP Response", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 3.dp))
                        DetailLine("Response code", result.responseCode?.toString() ?: "No HTTP response code")
                        DetailLine("Response status", result.responseCode?.let { statusForHttpCode(it) } ?: result.status)
                        DetailLine("Fetched at", formatIst(result.checkedAt))
                        DetailLine("Start URL", entry.url)
                        DetailLine("Final / Checked URL", result.finalUrl.ifBlank { entry.url })
                        DetailLine("HTTP version", result.httpVersion)
                        DetailLine("Probe detail", result.detail)
                        DetailLine("Error / Failure reason", result.error.ifBlank { "—" })
                        Text("Response fingerprint", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 3.dp))
                        DetailLine("Content type", result.contentType.ifBlank { "—" })
                        DetailLine("Server", result.server.ifBlank { "—" })
                        DetailLine("ETag", result.etag.ifBlank { "—" })
                        DetailLine("Last-Modified", result.lastModified.ifBlank { "—" })
                        DetailLine("Response Date", result.responseDate.ifBlank { "—" })
                        DetailLine("Set-Cookie present", result.setCookiePresent.toString())
                        Text("All captured response headers (${result.headers.size})", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 3.dp))
                        if (result.headers.isEmpty()) {
                            DetailLine("Response headers", "No headers received")
                        } else {
                            result.headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, value) ->
                                DetailLine(name, value)
                            }
                        }
                        Text("Header values are shown as received by the health checker. Sensitive header values, if returned by the server, are visible in this report.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.ContentCopy, null)
                        Spacer(Modifier.width(5.dp))
                        Text("Copy URL")
                    }
                    Button(onClick = onOpen, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.OpenInNew, null)
                        Spacer(Modifier.width(5.dp))
                        Text("Open URL")
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
private fun Activity(sources: List<OnionEntry>, checking: Boolean, report: HealthReport?, progressDone: Int, progressTotal: Int, workers: List<WorkerProgress>, activityLog: String, onRunChecks: () -> Unit) {
    val groups = remember(sources) { groupSources(sources) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Activity", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        item { Text("Health check results", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
        item { Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.38f))) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Inventory2, null, tint = Color(0xFF007BA7), modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column { Text("Source index", fontWeight = FontWeight.SemiBold); Text("${sources.count { it.host.endsWith(".onion", true) }} onion URLs and ${sources.size - sources.count { it.host.endsWith(".onion", true) }} clear-web URLs in ${groups.size} groups", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        }
        item { Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.38f))) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                val pulse = rememberInfiniteTransition(label = "monitoringPulse")
                val rotation by pulse.animateFloat(initialValue = 0f, targetValue = if (checking) 360f else 0f, animationSpec = infiniteRepeatable(animation = tween(1800, easing = LinearEasing), repeatMode = RepeatMode.Restart), label = "monitoringRotation")
                Icon(Icons.Default.PendingActions, null, tint = Color(0xFFFF2D95), modifier = Modifier.size(28.dp).rotate(rotation))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Live monitoring", fontWeight = FontWeight.SemiBold)
                    Text(if (checking) "Health checks are running…" else report?.message ?: "No live checks yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Text("The foreground service keeps the scan active when you switch apps. Unrestricted battery access improves reliability on devices with aggressive power management.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        }
        if (checking || workers.isNotEmpty()) {
            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.38f))) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (checking) "Live workers" else "Last worker activity", fontWeight = FontWeight.SemiBold)
                        Text("${workers.count { it.status == "Checking" }} URL slots currently checking • ${workers.count { it.status == "Waiting" }} waiting", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Two independent crawlers: up to 32 direct clear-web slots and 24 Tor onion slots. Each slot handles one URL at a time.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        workers.forEach { worker ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${worker.id}: ${worker.status}", fontWeight = FontWeight.Medium, fontSize = 12.sp)
                                if (worker.url.isNotBlank()) Text(worker.url, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.38f))) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (checking) "Live activity log" else "Last scan activity log", fontWeight = FontWeight.SemiBold)
                        Text("Newest events first • chunk saves and worker transitions", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        activityLog.lines().filter { it.isNotBlank() }.takeLast(36).asReversed().forEach { line ->
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.padding(top = 4.dp).size(6.dp).clip(CircleShape).background(if (line.contains("saved", true) || line.contains("completed", true)) Color(0xFF16B887) else MaterialTheme.colorScheme.secondary))
                                Text(line, Modifier.weight(1f), fontSize = 10.sp, lineHeight = 14.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
        if (checking && report?.results.isNullOrEmpty()) {
            items(3) { SkeletonSourceCard() }
        }
        groups.filter { group -> group.urls.any { report?.results?.containsKey(it) == true } }.forEach { group ->
            item { Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.38f))) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(group.name, fontWeight = FontWeight.SemiBold)
                    group.entries.filter { report?.results?.containsKey(it.url) == true }.forEach { entry ->
                        val result = report!!.results.getValue(entry.url)
                        Text(entry.url, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        StateLine("Result", result.status, when (result.status) {
                            "Online" -> Color(0xFF16B887)
                            "Redirect" -> Color(0xFF8B7CFF)
                            "Informational" -> Color(0xFF4DA6FF)
                            "Client error" -> Color(0xFFFFB547)
                            "Server error" -> Color(0xFFFF5D67)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        })
                        Text(result.detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } }
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
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.38f))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth(0.58f).height(16.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)).graphicsLayer { this.alpha = alpha })
            Box(Modifier.fillMaxWidth(0.92f).height(11.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)).graphicsLayer { this.alpha = alpha })
            Box(Modifier.fillMaxWidth(0.72f).height(11.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)).graphicsLayer { this.alpha = alpha })
        }
    }
}

@Composable
private fun Settings(
    report: HealthReport?, sourceCount: Int, darkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    onNotificationSettings: () -> Unit,
    onBackgroundSettings: () -> Unit,
    onScheduleSettings: () -> Unit
) {
    val context = LocalContext.current
    val backgroundAllowed = remember {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        item {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f))) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(9.dp))
                        Text("Automatic health checks", fontWeight = FontWeight.SemiBold)
                    }
                    Text("Daily at 05:00 AM and 05:00 PM • Asia/Kolkata (IST)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("Next run: ${HealthCheckScheduler.nextRunLabel()}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Exact timing depends on Android alarm access and device power settings. Interrupted scans keep completed chunks and resume from the saved checkpoint.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.38f))) {
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
                        Icon(Icons.Default.BatteryChargingFull, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Background execution", fontWeight = FontWeight.SemiBold)
                            Text(if (backgroundAllowed) "Unrestricted battery use is allowed." else "Battery optimization may pause long-running scans. Enable unrestricted use in Android settings.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = onBackgroundSettings) { Text(if (backgroundAllowed) "Manage" else "Enable") }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Exact scan schedule", fontWeight = FontWeight.SemiBold)
                            val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()
                            Text(if (exactAllowed) "Android exact-alarm access is available." else "Allow exact alarms for the closest 05:00 and 17:00 IST timing. Otherwise Android may defer scans.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = onScheduleSettings) { Text("Manage") }
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
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.38f))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Intelligence sources", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    StateLine("Indexed URLs", sourceCount.toString(), MaterialTheme.colorScheme.primary)
                    StateLine("Last network check", report?.let { if (it.torAvailable) "Completed" else "Unavailable" } ?: "Not run", MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("The source index is bundled with the app and can be browsed offline. Live status requires Tor connectivity.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.38f))) {
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


/**
 * Keeps a user-started URL scan independent from the Compose Activity lifecycle.
 * The ongoing notification makes the long-running network work visible to Android.
 */
class HealthCheckService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channelId = "raashub_background_health_check"
    private val notificationId = 4108
    @Volatile private var scanStarted = false
    @Volatile private var lastProgress: Int = 0
    private var torBound = false
    private val torServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { torBound = true }
        override fun onServiceDisconnected(name: ComponentName?) { torBound = false }
    }

    override fun onCreate() {
        super.onCreate()
        // Keep Tor alive for the entire background scan, even if MainActivity is destroyed.
        torBound = runCatching { bindService(Intent(this, TorService::class.java), torServiceConnection, BIND_AUTO_CREATE) }.getOrDefault(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "Background health check", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Persistent progress for URL health checks"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (scanStarted) return START_STICKY
        scanStarted = true
        startForeground(notificationId, buildNotification(0, 0, "Preparing URL health check…", true))
        serviceScope.launch {
            try {
                val array = JSONArray(assets.open("onion_sources.json").bufferedReader().use { it.readText() })
                val sources = buildList {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val url = normalizeSourceUrl(item.optString("url")) ?: continue
                        add(OnionEntry(url, item.optString("entity", "Unnamed source"), item.optString("source", "Source list"), item.optString("category", "other").lowercase()))
                    }
                }.distinctBy { it.url.lowercase() }
                val checkpoint = getSharedPreferences("raashub_scan_checkpoint", MODE_PRIVATE)
                val previousReport = loadLatestHealthReport(this@HealthCheckService)
                val wasInterrupted = checkpoint.getBoolean("active", false)
                val completedUrls = if (wasInterrupted) {
                    runCatching {
                        val saved = JSONArray(checkpoint.getString("completed_urls", "[]"))
                        buildSet { for (i in 0 until saved.length()) add(saved.optString(i).lowercase()) }
                    }.getOrDefault(emptySet())
                } else emptySet()
                if (!wasInterrupted) {
                    checkpoint.edit().putBoolean("active", true).putString("completed_urls", "[]")
                        .putLong("started_at", System.currentTimeMillis()).commit()
                }
                // RaaSHub Group Priority Queue v2: preserve each source group and rank URLs
                // inside that group. Online first, redirects second, protected/challenge
                // responses third, other HTTP responses/never checked next, no-response last.
                val rankedSources = sources.groupBy { it.entity.trim().ifBlank { it.host } }
                    .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    .values.flatMap { groupEntries ->
                        groupEntries.sortedWith(
                            compareBy<OnionEntry> { entry ->
                                val prior = previousReport?.results?.get(entry.url)
                                when {
                                    prior?.status == "Online" -> 0
                                    prior?.status == "Redirect" -> 1
                                    prior?.status == "Protected" -> 2
                                    prior == null -> 3
                                    prior.responseCode != null -> 3
                                    else -> 4
                                }
                            }.thenBy { previousReport?.results?.get(it.url)?.checkedAt ?: 0L }
                                .thenBy { it.url.lowercase() }
                        )
                    }
                val pendingSources = rankedSources.filterNot { it.url.lowercase() in completedUrls }
                val total = sources.size
                val resumedCount = completedUrls.size.coerceAtMost(total)
                update(resumedCount, total, if (wasInterrupted) "Resuming interrupted scan: $resumedCount/$total already checkpointed" else "Background scan started: 0/$total", true)
                if (pendingSources.isEmpty()) {
                    val finalReport = previousReport ?: HealthReport(false, null, "No pending URLs remain.", emptyMap())
                    saveLatestHealthReport(this@HealthCheckService, finalReport)
                    checkpoint.edit().putBoolean("active", false).commit()
                    update(total, total, "Completed: ${finalReport.message}", false)
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf(startId)
                    return@launch
                }
                val report = checkOnionSources(pendingSources, { done, count ->
                    update(done, count, "Health check running: $done/$count (${if (count == 0) 0 else done * 100 / count}%)", true)
                }, { workers, log ->
                    val active = workers.count { it.status == "Checking" }
                    update(lastProgress, total, "Active workers: $active • ${workers.size} worker slots", true, log, workers = workers)
                }) { crawler, chunk, chunkCount, partialResults ->
                    val onionCount = partialResults.keys.count { Uri.parse(it).host?.endsWith(".onion", true) == true }
                    val clearCount = partialResults.size - onionCount
                    val partialMessage = "$crawler chunk $chunk/$chunkCount saved. $onionCount onion and $clearCount clear-web URLs have detailed results stored; remaining chunks continue."
                    val mergedResults = (previousReport?.results.orEmpty() + partialResults)
                    saveLatestHealthReport(this@HealthCheckService, HealthReport(true, null, partialMessage, mergedResults))
                    val doneKeys = (completedUrls + partialResults.keys.map { it.lowercase() }).distinct()
                    checkpoint.edit().putString("completed_urls", JSONArray(doneKeys).toString())
                        .putBoolean("active", true).commit()
                    update(resumedCount + lastProgress, total, partialMessage, true, partialMessage, reportUpdated = true)
                }
                val mergedFinalResults = previousReport?.results.orEmpty() + report.results
                val finalReport = report.copy(results = mergedFinalResults)
                saveLatestHealthReport(this@HealthCheckService, finalReport)
                checkpoint.edit().putBoolean("active", false).putLong("completed_at", System.currentTimeMillis()).commit()
                HealthCheckScheduler.schedule(this@HealthCheckService)
                update(total, total, "Completed: ${finalReport.message}", false)
            } catch (error: Exception) {
                val reason = "Scan failed: ${error.message ?: error.javaClass.simpleName}"
                val retained = loadLatestHealthReport(this@HealthCheckService)
                saveLatestHealthReport(this@HealthCheckService, HealthReport(false, null, reason, retained?.results.orEmpty()))
                // Keep the checkpoint active so a later service restart can continue the unfinished queue.
                update(lastProgress, lastProgress.coerceAtLeast(1), reason, false)
                HealthCheckScheduler.schedule(this@HealthCheckService)
            }
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
        }
        return START_STICKY
    }

    private fun update(
        done: Int, total: Int, message: String, running: Boolean, log: String = message,
        workers: List<WorkerProgress> = emptyList(), reportUpdated: Boolean = false
    ) {
        lastProgress = done
        runCatching { getSystemService(NotificationManager::class.java).notify(notificationId, buildNotification(done, total, message, running)) }
        val workersJson = JSONArray().apply {
            workers.forEach { worker ->
                put(org.json.JSONObject().put("id", worker.id).put("status", worker.status).put("url", worker.url))
            }
        }.toString()
        sendBroadcast(Intent(ACTION_UPDATE).setPackage(packageName)
            .putExtra("done", done).putExtra("total", total)
            .putExtra("running", running).putExtra("log", log.takeLast(12000))
            .putExtra("workers", workersJson).putExtra("reportUpdated", reportUpdated))
    }

    private fun buildNotification(done: Int, total: Int, message: String, running: Boolean) =
        NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.raashub_notification)
            .setContentTitle(if (running) "RaaSHub is checking URLs" else "RaaSHub health check")
            .setContentText(message.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOngoing(running).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(total.coerceAtLeast(1), done.coerceIn(0, total.coerceAtLeast(1)), running && total <= 0)
            .build()

    @androidx.annotation.RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 enforces a six-hour daily budget for dataSync foreground services.
        // Chunk checkpoints remain committed; stop cleanly so the next scheduled run can resume.
        update(lastProgress, lastProgress.coerceAtLeast(1), "Android background time limit reached. Completed chunks are saved; the next scheduled run will resume.", false)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        if (torBound) runCatching { unbindService(torServiceConnection) }
        torBound = false
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object { const val ACTION_UPDATE = "com.raashub.HEALTH_CHECK_UPDATE" }
}
