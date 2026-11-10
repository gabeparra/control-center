package app.controlcenter

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/* Access: let a browser into a gated web app, or cut one off.
 *
 * Pairs with a bridge-side login gate that shows an unrecognised browser a
 * six-digit code instead of the app and pushes a notification here. This is
 * where that request is answered.
 *
 * **Compare the code before approving.** The number on this screen and the
 * number on the browser must match. Approving without looking is the one way
 * this design fails: if someone else's request is waiting when you happen to
 * be signing in yourself, an unchecked tap lets them in instead of you. That
 * is why the code is the largest thing on each row.
 *
 * Pending requests are polled every three seconds while the tab is open, since
 * they expire after three minutes and a stale list would offer you a button
 * that cannot work.
 */

data class PendingReq(
    val id: String, val code: String, val ip: String,
    val browser: String, val age: Int,
)

data class KnownDevice(
    val id: String, val browser: String, val ip: String,
    val lastSeen: Double, val revoked: Boolean,
)

private fun ago(seconds: Double): String {
    val s = seconds.toLong()
    return when {
        s < 90 -> "${s}s ago"
        s < 5400 -> "${s / 60}m ago"
        s < 172800 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}

@Composable
fun AccessScreen(snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf(listOf<PendingReq>()) }
    var devices by remember { mutableStateOf(listOf<KnownDevice>()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refresh(quiet: Boolean = false) {
        runCatching {
            val p = Api.get("/access/pending").getJSONArray("pending")
            pending = (0 until p.length()).map {
                val o = p.getJSONObject(it)
                PendingReq(o.getString("id"), o.getString("code"), o.getString("ip"),
                    o.getString("browser"), o.optInt("age"))
            }
            val d = Api.get("/access/devices").getJSONArray("devices")
            devices = (0 until d.length()).map {
                val o = d.getJSONObject(it)
                KnownDevice(o.getString("id"), o.getString("browser"), o.getString("ip"),
                    o.optDouble("last_seen", 0.0), o.optBoolean("revoked", false))
            }
            error = null
        }.onFailure {
            // A failed poll must not read as "nothing is waiting": that is the
            // one wrong answer this screen can give.
            error = it.message
            if (!quiet) snackbar.showSnackbar("access: ${it.message}")
        }
        loaded = true
    }

    fun act(path: String, ok: String) = scope.launch {
        busy = true
        runCatching { Api.post(path, JSONObject()) }
            .onSuccess { snackbar.showSnackbar(ok) }
            .onFailure { snackbar.showSnackbar("failed: ${it.message}") }
        refresh(quiet = true)
        busy = false
    }

    // Poll while the tab is open. Requests expire in three minutes, so a list
    // that only loaded once would show buttons that no longer do anything.
    LaunchedEffect(Unit) {
        while (true) {
            refresh(quiet = loaded)
            delay(3000)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Access", color = Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { scope.launch { refresh() } }, enabled = !busy) {
                Icon(Icons.Filled.Refresh, "Refresh", tint = Teal)
            }
        }

        error?.let {
            Text("Can't reach the gate: $it", color = Bad, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp))
        }

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text("WAITING", color = Amber, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }

            if (pending.isEmpty()) {
                item {
                    Text(
                        if (!loaded) "Checking…" else "Nobody is asking to get in.",
                        color = Muted, fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }

            items(pending, key = { it.id }) { r ->
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surface1)
                        .border(1.dp, Amber, RoundedCornerShape(8.dp))
                        .padding(14.dp),
                ) {
                    Text("Does the browser show this code?", color = Muted, fontSize = 11.sp)
                    Text(
                        r.code, color = Ink, fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 6.sp,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    Text("${r.browser} · ${r.ip}", color = Ink, fontSize = 12.sp)
                    Text("asked ${r.age}s ago · expires after 3 min",
                        color = Muted, fontSize = 11.sp)

                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                                .background(Surface1)
                                .border(1.dp, Muted, RoundedCornerShape(6.dp))
                                .clickable(enabled = !busy) {
                                    act("/access/deny/${r.id}", "Denied")
                                }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("Deny", color = Muted, fontSize = 14.sp) }

                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                                .background(Amber)
                                .clickable(enabled = !busy) {
                                    act("/access/approve/${r.id}", "Approved: ${r.code}")
                                }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Approve", color = Ink, fontSize = 14.sp,
                                fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item {
                Text("APPROVED DEVICES", color = Amber, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                    modifier = Modifier.padding(top = 18.dp))
            }

            val live = devices.filter { !it.revoked }
            if (live.isEmpty() && loaded) {
                item {
                    Text("No devices are signed in.", color = Muted, fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 6.dp))
                }
            }

            items(live, key = { it.id }) { d ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(Surface1).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.browser, color = Ink, fontSize = 14.sp)
                        Text(
                            "${d.ip} · seen ${ago(System.currentTimeMillis() / 1000.0 - d.lastSeen)}",
                            color = Muted, fontSize = 11.sp,
                        )
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .border(1.dp, Bad, RoundedCornerShape(6.dp))
                            .clickable(enabled = !busy) {
                                act("/access/revoke/${d.id}", "Revoked ${d.browser}")
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text("Revoke", color = Bad, fontSize = 12.sp) }
                }
            }

            val gone = devices.count { it.revoked }
            if (gone > 0) {
                item {
                    Text("$gone revoked device${if (gone == 1) "" else "s"} kept for the record.",
                        color = Muted, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}
