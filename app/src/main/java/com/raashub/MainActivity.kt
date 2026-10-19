package com.raashub

import android.content.Intent
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

private data class OnionEntry(val url: String, val entity: String, val source: String) {
    val host: String get() = runCatching { Uri.parse(url).host ?: url }.getOrDefault(url)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HubApp(readSources()) }
    }

    private fun readSources(): List<OnionEntry> = runCatching {
        val array = JSONArray(assets.open("onion_sources.json").bufferedReader().use { it.readText() })
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = item.optString("url")
                if (url.contains(".onion", true)) add(OnionEntry(url, item.optString("entity", "Repository source"), item.optString("source", "Repository data")))
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
    var selected by remember { mutableStateOf<OnionEntry?>(null) }
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
                        Surface(color = Color(0xFF0FB98A).copy(alpha = .2f), shape = CircleShape) { Text("LOCAL", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color(0xFF9AF2D1), fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Know your threat landscape.", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("Repository-sourced intelligence. Stored on this device.", color = Color(0xFFCAD7EF), fontSize = 12.sp)
                }
                when {
                    selected != null -> Detail(selected!!, onBack = { selected = null }, onCopy = { url ->
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("RaaSHub onion source", url))
                    }, onOpen = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } })
                    tab == Tab.HOME -> Home(sources) { tab = Tab.SOURCES }
                    tab == Tab.SOURCES -> Sources(sources, search, { search = it }) { selected = it }
                    tab == Tab.ACTIVITY -> Activity(sources.size)
                    else -> Settings()
                }
                Text("Developed With ♥️ AnonVox", Modifier.fillMaxWidth().padding(vertical = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Home(sources: List<OnionEntry>, onExplore: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("ONION SOURCES", sources.size.toString(), Icons.Default.Hub, Modifier.weight(1f))
                Metric("ENTITIES", sources.map { it.entity }.distinct().size.toString(), Icons.Default.Groups, Modifier.weight(1f))
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Intelligence snapshot", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    StateLine("Repository ingestion", "Loaded", Color(0xFF16B887))
                    StateLine("Live onion availability", "Not checked", MaterialTheme.colorScheme.onSurfaceVariant)
                    StateLine("Network route", "Tor not confirmed", MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No service is labelled active or offline without a real check through Tor.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().clickable(onClick = onExplore), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Explore repository sources", fontWeight = FontWeight.Bold); Text("Search URLs and inspect their source file.", fontSize = 12.sp) }
                    Icon(Icons.Default.ArrowForward, null)
                }
            }
        }
        item { Text("Source preview", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        items(sources.take(5)) { SourceCard(it) { onExplore() } }
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
private fun Sources(sources: List<OnionEntry>, query: String, onQuery: (String) -> Unit, onSelect: (OnionEntry) -> Unit) {
    val filtered = remember(sources, query) { sources.filter { query.isBlank() || it.url.contains(query, true) || it.entity.contains(query, true) || it.source.contains(query, true) } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().padding(top = 12.dp), placeholder = { Text("Search URL, entity or file") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${filtered.size} sources", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("Repository data only", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (sources.isEmpty()) "Source index unavailable in this build." else "No matching sources") }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(filtered, key = { it.url }) { SourceCard(it) { onSelect(it) } }
        }
    }
}

@Composable
private fun SourceCard(entry: OnionEntry, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(17.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF8B69E8).copy(alpha = .15f)) { Icon(Icons.Default.Language, null, Modifier.padding(9.dp), tint = Color(0xFF9B80FF)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.entity, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.host, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.source, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Detail(entry: OnionEntry, onBack: () -> Unit, onCopy: (String) -> Unit, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null); Text("Back") }
        Card(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp)) {
                Icon(Icons.Default.Hub, null, tint = Color(0xFF8E79FF), modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(12.dp))
                Text(entry.entity, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(entry.url, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                Text("Source file", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Text(entry.source, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                StateLine("Availability", "Unknown", MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Opening an onion address requires a Tor-capable browser or a configured Tor route. This app does not claim traffic is routed through Tor.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(onClick = { onCopy(entry.url) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Copy URL") }
        OutlinedButton(onClick = { onOpen(entry.url) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Open with compatible app") }
    }
}

@Composable
private fun Activity(count: Int) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Activity", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Real events only. No fabricated scan results.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Card(shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Inventory2, null, tint = Color(0xFF16B8A6), modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column { Text("Repository import", fontWeight = FontWeight.SemiBold); Text("$count unique onion URLs indexed in this build", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Card(shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PendingActions, null, tint = Color(0xFFB58AFF), modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column { Text("Live monitoring", fontWeight = FontWeight.SemiBold); Text("Not configured; no live health checks have run.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun Settings() {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Card(shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Local-first source index", fontWeight = FontWeight.SemiBold)
                Text("The source index is bundled with this app. No cloud account is used.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("Theme", fontWeight = FontWeight.SemiBold)
                Text("Follows Android system light/dark appearance.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("RaaSHub 1.0.0 • Native Kotlin", fontWeight = FontWeight.SemiBold)
                Text("Source availability remains Unknown until measured through a real Tor route.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
