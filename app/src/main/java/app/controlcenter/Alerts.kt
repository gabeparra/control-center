package app.controlcenter

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* Audit tab: the bridge's watchdog findings and its event log in one place.
   Up top: what is currently wrong and not yet acknowledged, each dismissable
   (a real acknowledge on the bridge, so it stops nagging everywhere until it
   clears and recurs). Below: the full scrollback, newest first: a push you
   can't review later is only half an alert. `focusKey` lets a notification tap
   land on a specific item and highlight it. */

data class Ev(val ts: Double, val type: String, val text: String, val device: String)
data class ActiveAlert(val key: String, val title: String, val text: String,
                       val critical: Boolean, val ts: Double, val acknowledged: Boolean)

private val timeFmt = SimpleDateFormat("MMM d  HH:mm", Locale.getDefault())

/** Alert keys encode their target (`svc:name`, `ctr:name`), which is exactly
 *  what the bridge's whitelisted restart endpoints take: so a down-service
 *  alert can carry its own fix. Null for alerts with nothing to restart. */
internal fun alertRestartPath(key: String): String? = when {
    key.startsWith("svc:") -> "/service/${key.removePrefix("svc:")}/restart"
    key.startsWith("ctr:") -> "/container/${key.removePrefix("ctr:")}/restart"
    else -> null
}

@Composable
fun AlertsScreen(snackbar: SnackbarHostState, focusKey: String? = null) {
    val scope = rememberCoroutineScope()
    var events by remember { mutableStateOf(listOf<Ev>()) }
    var active by remember { mutableStateOf(listOf<ActiveAlert>()) }
    var loading by remember { mutableStateOf(true) }
    var onlyAlerts by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun loadActive() = scope.launch {
        if (Caps.has("alerts")) runCatching {
            val rows = Api.get("/alerts/active").getJSONArray("alerts")
            active = (0 until rows.length()).map {
                val o = rows.getJSONObject(it)
                ActiveAlert(o.getString("key"), o.optString("title"), o.optString("text"),
                    o.optBoolean("critical"), o.optDouble("ts"), o.optBoolean("acknowledged"))
            }
            AlertBadge.unacked = active.count { !it.acknowledged }
        }
    }

    fun loadEvents() = scope.launch {
        loading = true
        if (Caps.has("events")) runCatching {
            val rows = Api.get("/events?limit=200").getJSONArray("events")
            events = (rows.length() - 1 downTo 0).map {
                val o = rows.getJSONObject(it)
                Ev(o.optDouble("ts"), o.optString("type"), o.optString("text"), o.optString("device"))
            }
        }.onFailure { snackbar.showSnackbar("events unreachable: ${it.message}") }
        loading = false
    }

    fun setAck(key: String, ack: Boolean) = scope.launch {
        runCatching { Api.post("/alerts/ack", JSONObject().put("key", key).put("ack", ack)) }
            .onSuccess { loadActive() }
            .onFailure { snackbar.showSnackbar("dismiss: ${it.message ?: "failed"}") }
    }

    var restarting by remember { mutableStateOf(setOf<String>()) }
    fun restartTarget(key: String) = scope.launch {
        val path = alertRestartPath(key) ?: return@launch
        restarting = restarting + key
        runCatching { Api.post(path, JSONObject()) }
            .onSuccess {
                // the alert itself clears on the watchdog's next scan (~1 min)
                snackbar.showSnackbar("restarted, alert clears on next scan")
            }
            .onFailure { snackbar.showSnackbar("restart: ${it.message ?: "failed"}") }
        restarting = restarting - key
    }

    fun reload() { loadEvents(); loadActive() }
    LaunchedEffect(Unit) { reload() }
    // A notification tap deep-links here with the item's key: pull it to the top.
    LaunchedEffect(focusKey, active) {
        if (focusKey != null && active.any { it.key == focusKey }) listState.animateScrollToItem(0)
    }

    val shown = if (onlyAlerts) events.filter { it.type == "alert" } else events
    val unacked = active.count { !it.acknowledged }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("AUDIT", color = Amber, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.width(10.dp))
            Text("$unacked active · ${events.size} events",
                color = if (unacked > 0) Bad else Muted, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Muted)
            else IconButton(onClick = { reload() }) {
                Icon(Icons.Filled.Refresh, "reload", tint = Muted)
            }
        }
        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !onlyAlerts, onClick = { onlyAlerts = false },
                label = { Text("Everything", fontSize = 12.sp) })
            FilterChip(selected = onlyAlerts, onClick = { onlyAlerts = true },
                label = { Text("Alerts only", fontSize = 12.sp) })
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {

            // ---- Active findings: what is wrong right now ----
            if (active.isNotEmpty()) {
                item(key = "active-hdr") {
                    Text("ACTIVE", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp))
                }
                items(active, key = { "a-${it.key}" }) { a ->
                    val focused = a.key == focusKey
                    val edge = when {
                        a.acknowledged -> Muted
                        a.critical -> Bad
                        else -> Amber
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = Surface1),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(if (focused) 2.dp else 1.dp, edge),
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).background(edge, CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(a.title, color = if (a.acknowledged) Muted else Ink,
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f))
                                if (alertRestartPath(a.key) != null) {
                                    if (a.key in restarting)
                                        CircularProgressIndicator(Modifier.size(14.dp),
                                            strokeWidth = 2.dp, color = Teal)
                                    else
                                        Text("restart", color = Teal, fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.clickable { restartTarget(a.key) })
                                    Spacer(Modifier.width(14.dp))
                                }
                                if (a.acknowledged)
                                    Text("restore", color = Teal, fontSize = 12.sp,
                                        modifier = Modifier.clickable { setAck(a.key, false) })
                                else
                                    Text("dismiss", color = Muted, fontSize = 12.sp,
                                        modifier = Modifier.clickable { setAck(a.key, true) })
                            }
                            if (a.text.isNotBlank()) {
                                Spacer(Modifier.height(3.dp))
                                Text(a.text, color = Muted, fontSize = 12.sp)
                            }
                            Text(a.key, color = Teal, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                item(key = "hist-hdr") {
                    Text("HISTORY", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                }
            }

            if (shown.isEmpty() && !loading) {
                item { Text(if (onlyAlerts) "No alerts in the log." else "No events yet.",
                    color = Muted, fontSize = 13.sp) }
            }
            items(shown) { e ->
                val tint = when (e.type) {
                    "alert" -> Bad
                    "install-result" -> Good
                    else -> Muted
                }
                Card(colors = CardDefaults.cardColors(containerColor = Surface1),
                    shape = RoundedCornerShape(10.dp)) {
                    Row(Modifier.padding(12.dp)) {
                        Box(Modifier.padding(top = 5.dp).size(7.dp).background(tint, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row {
                                Text(e.type, color = tint, fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                Text(timeFmt.format(Date((e.ts * 1000).toLong())),
                                    color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(Modifier.height(3.dp))
                            SelectionContainer {
                                Text(e.text, color = Ink, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
