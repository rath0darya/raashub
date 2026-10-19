package com.raashub

import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import org.torproject.jni.TorService
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

private data class OnionEntry(val url: String, val entity: String, val source: String) {
    val host: String get() = runCatching { Uri.parse(url).host ?: url }.getOrDefault(url)
}
private data class OnionGroup(val name: String, val entries: List<OnionEntry>) {
    val urls: List<String> get() = entries.map { it.url }.distinct()
}
private fun groupSources(sources: List<OnionEntry>): List<OnionGroup> =
    sources.groupBy { it.entity.trim().ifBlank { it.host } }
        .map { (name, entries) -> OnionGroup(name, entries.distinctBy { it.url.lowercase() }.sortedBy { it.host.lowercase() }) }
        .sortedBy { it.name.lowercase() }

private data class HealthResult(val status: String, val detail: String, val checkedAt: Long)
private data class HealthReport(val torAvailable: Boolean, val proxyPort: Int?, val message: String, val results: Map<String, HealthResult>)

private suspend fun checkOnionSources(sources: List<OnionEntry>): HealthReport {
    val proxyPort = withContext(Dispatchers.IO) {
        var port = findTorSocksProxy()
        var waited = 0
        while (port == null && waited < 90000) {
            Thread.sleep(1000)
            waited += 1000
            port = findTorSocksProxy()
        }
        port
    }
    if (proxyPort == null) return HealthReport(false, null, "Tor could not connect.", emptyMap())
    if (sources.isEmpty()) return HealthReport(true, proxyPort, "No onion URLs available to check.", emptyMap())

    val semaphore = Semaphore(4)
    val results = kotlinx.coroutines.coroutineScope {
        sources.take(10).map { entry ->
            async(Dispatchers.IO) { semaphore.withPermit { entry.url to checkOneOnion(entry.url, proxyPort) } }
        }.awaitAll().toMap()
    }
    val responding = results.values.count { it.status == "Online" || it.status == "Responding" }
    return HealthReport(
        true, proxyPort,
        "Checked ${results.size} of ${sources.size} onion URLs. $responding responded.",
        results
    )
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

private fun checkOneOnion(urlText: String, proxyPort: Int): HealthResult {
    val checkedAt = System.currentTimeMillis()
    return try {
        val uri = Uri.parse(urlText)
        val host = uri.host ?: throw IOException("Invalid source URL")
        if (!host.endsWith(".onion", ignoreCase = true)) throw IOException("Health checks are restricted to onion hosts")
        val scheme = (uri.scheme ?: "http").lowercase()
        if (scheme != "http" && scheme != "https") throw IOException("Unsupported URL scheme")
        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
        val raw = Socket()
        raw.connect(InetSocketAddress("127.0.0.1", proxyPort), 1800)
        raw.soTimeout = 6500
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
                connection.soTimeout = 6500
                val path = (uri.encodedPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.encodedQuery?.let { "?$it" } ?: "")
                val request = "HEAD $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: RaaSHub-HealthCheck/1.0\r\nConnection: close\r\n\r\n"
                connection.getOutputStream().write(request.toByteArray(StandardCharsets.ISO_8859_1))
                connection.getOutputStream().flush()
                val statusLine = BufferedReader(InputStreamReader(connection.getInputStream(), StandardCharsets.ISO_8859_1)).readLine()
                    ?: throw IOException("Empty HTTP response")
                val code = Regex("^HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})").find(statusLine)?.groupValues?.get(1)?.toIntOrNull()
                    ?: throw IOException("Unrecognised HTTP response")
                when {
                    code in 200..399 -> HealthResult("Online", "HTTP $code • Responded", checkedAt)
                    code in 400..499 -> HealthResult("Responding", "HTTP $code • Responded", checkedAt)
                    else -> HealthResult("Server error", "HTTP $code • host responded but returned an error", checkedAt)
                }
            }
        } finally { runCatching { raw.close() } }
    } catch (e: Exception) {
        val message = if (e is java.net.SocketTimeoutException) "Timed out through Tor" else (e.message ?: "Connection failed").take(120)
        HealthResult("Unverified", message, checkedAt)
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
    private var torBound = false
    private val torConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { }
        override fun onServiceDisconnected(name: ComponentName?) { torBound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            torBound = bindService(Intent(this, TorService::class.java), torConnection, BIND_AUTO_CREATE)
        }
        setContent { HubApp(readSources()) }
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
                val url = item.optString("url")
                if (url.contains(".onion", true)) add(OnionEntry(url, item.optString("entity", "Unnamed source"), item.optString("source", "Source list")))
            }
        }.distinctBy { it.url.lowercase() }.sortedBy { it.host.lowercase() }
    }.getOrDefault(emptyList())
}

private enum class Tab(val title: String) { HOME("Home"), SOURCES("Sources"), ACTIVITY("Activity"), SETTINGS("Settings") }

@Composable
private fun HubApp(sources: List<OnionEntry>) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme(primary = Color(0xFF6CE8FF), secondary = Color(0xFFB39BFF), background = Color(0xFF0A1020), surface = Color(0xFF141D32))
    else lightColorScheme(primary = Color(0xFF006A83), secondary = Color(0xFF6553A5))
    var tab by remember { mutableStateOf(Tab.HOME) }
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<OnionGroup?>(null) }
    var checking by remember { mutableStateOf(false) }
    var healthReport by remember { mutableStateOf<HealthReport?>(null) }
    val scope = rememberCoroutineScope()
    val runHealthChecks: () -> Unit = {
        if (!checking) {
            checking = true
            scope.launch {
                healthReport = checkOnionSources(sources)
                checking = false
            }
        }
    }
    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        val icon = when (t) { Tab.HOME -> Icons.Default.Dashboard; Tab.SOURCES -> Icons.Default.Hub; Tab.ACTIVITY -> Icons.Default.Timeline; Tab.SETTINGS -> Icons.Default.Settings }
                        NavigationBarItem(selected = tab == t, onClick = { tab = t; selected = null }, icon = { Icon(icon, t.title) }, label = { Text(t.title) })
                    }
                }
            }
        ) { inset ->
            Column(Modifier.fillMaxSize().padding(inset)) {
                Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF071A33), Color(0xFF172953), Color(0xFF48205E)))).padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Shield, null, tint = Color(0xFF67E9FF), modifier = Modifier.size(36.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("RaaSHub", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                            Text("CYBER THREAT INTELLIGENCE", color = Color(0xFFAEDCF8), fontSize = 10.sp, letterSpacing = 1.2.sp)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Know your threat landscape.", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
                when {
                    selected != null -> Detail(selected!!, healthReport?.results.orEmpty(), onBack = { selected = null }, onCopy = { url ->
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("RaaSHub onion URL", url))
                    }, onOpen = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } })
                    tab == Tab.HOME -> Home(sources, checking, healthReport, runHealthChecks) { tab = Tab.SOURCES }
                    tab == Tab.SOURCES -> Sources(sources, search, { search = it }) { selected = it }
                    tab == Tab.ACTIVITY -> Activity(sources, checking, healthReport, runHealthChecks)
                    else -> Settings(healthReport)
                }
                Text("Developed With ♥️ AnonVox", Modifier.fillMaxWidth().padding(vertical = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Metric("ONION URLS", sources.size.toString(), Icons.Default.Hub, Modifier.weight(1f))
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
                        Text(if (checking) "Checking sources…" else "Run live checks (up to 10)")
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().clickable(onClick = onExplore), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Explore sources", fontWeight = FontWeight.Bold); Text("Browse grouped onion URLs.", fontSize = 12.sp) }
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
private fun Sources(sources: List<OnionEntry>, query: String, onQuery: (String) -> Unit, onSelect: (OnionGroup) -> Unit) {
    val groups = remember(sources) { groupSources(sources) }
    val filtered = remember(groups, query) { groups.filter { group -> query.isBlank() || group.name.contains(query, true) || group.urls.any { it.contains(query, true) } } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().padding(top = 12.dp), placeholder = { Text("Search group or onion URL") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${filtered.size} groups", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("${filtered.sumOf { it.urls.size }} URLs", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (sources.isEmpty()) "No sources available." else "No matching sources") }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(filtered, key = { it.name }) { SourceCard(it) { onSelect(it) } }
        }
    }
}

@Composable
private fun SourceCard(group: OnionGroup, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(17.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF8B69E8).copy(alpha = .15f)) { Icon(Icons.Default.Language, null, Modifier.padding(9.dp), tint = Color(0xFF9B80FF)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(group.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${group.urls.size} onion URL${if (group.urls.size == 1) "" else "s"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                Text(group.urls.take(2).joinToString("  •  ") { Uri.parse(it).host ?: it }, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Detail(group: OnionGroup, health: Map<String, HealthResult>, onBack: () -> Unit, onCopy: (String) -> Unit, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        TextButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null); Text("Back") }
        Text(group.name, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
        Text("${group.urls.size} onion URLs", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(group.entries, key = { it.url }) { entry ->
                val result = health[entry.url]
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(entry.url, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        if (result != null) {
                            StateLine("Availability", result.status, when (result.status) {
                                "Online" -> Color(0xFF16B887)
                                "Responding" -> Color(0xFFB88922)
                                "Server error" -> Color(0xFFE58B35)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            })
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
private fun Activity(sources: List<OnionEntry>, checking: Boolean, report: HealthReport?, onRunChecks: () -> Unit) {
    val groups = remember(sources) { groupSources(sources) }
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Activity", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Health check results", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Card(shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Inventory2, null, tint = Color(0xFF16B8A6), modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column { Text("Source index", fontWeight = FontWeight.SemiBold); Text("${sources.size} onion URLs in ${groups.size} groups", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Card(shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PendingActions, null, tint = Color(0xFFB58AFF), modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Live monitoring", fontWeight = FontWeight.SemiBold)
                    Text(if (checking) "Health checks are running…" else report?.message?.takeIf { report.torAvailable } ?: "No live checks yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onRunChecks, enabled = !checking && count > 0) {
                        if (checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (checking) "Checking…" else "Run health checks")
                    }
                }
            }
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
private fun Settings(report: HealthReport?) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Card(shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Theme", fontWeight = FontWeight.SemiBold)
                Text("Follows Android system light/dark appearance.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("RaaSHub 1.0.0", fontWeight = FontWeight.SemiBold)
                Text(if (report?.torAvailable == false) "Network route unavailable" else "Health checks are available from Home and Activity.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
