package app.controlcenter

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
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

/* Power section of the Stack tab: wake/shutdown/reboot/suspend your
   machines. The bridge host relays wake packets for LAN peers; it can't wake
   itself, so that packet goes straight from the phone over local WiFi (the
   bridge returns the MAC list for exactly this). Destructive actions go
   through a confirm dialog: shutting down the bridge host kills every
   service including the bridge itself. */

data class PowerMachine(
    val name: String, val self: Boolean, val online: Boolean,
    val mac: String, val actions: List<String>,
)

@Composable
fun PowerSection(snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var machines by remember { mutableStateOf(listOf<PowerMachine>()) }
    var confirm by remember { mutableStateOf<Pair<PowerMachine, String>?>(null) }
    var assign by remember { mutableStateOf<PowerMachine?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun load() = scope.launch {
        if (Caps.has("wake")) runCatching {
            val rows = Api.get("/power/machines").getJSONArray("machines")
            machines = (0 until rows.length()).map {
                val o = rows.getJSONObject(it)
                val acts = o.optJSONArray("actions")
                PowerMachine(
                    o.getString("name"), o.optBoolean("self"), o.optBoolean("online"),
                    o.optString("mac"),
                    (0 until (acts?.length() ?: 0)).map { i -> acts!!.getString(i) },
                )
            }
        }.onFailure { snackbar.showSnackbar("power list failed: ${it.message}") }
    }

    suspend fun run(m: PowerMachine, action: String) {
        busy = true
        runCatching {
            when (action) {
                "wake" -> Api.post("/power/wake", JSONObject().put("name", m.name))
                else -> if (m.self)
                    Api.post("/power/self", JSONObject().put("action", action))
                else throw IllegalStateException("$action is only wired for this box")
            }
        }.onSuccess {
            snackbar.showSnackbar(
                if (action == "wake") "magic packet sent to ${m.name}"
                else "${m.name}: $action in 3s")
        }.onFailure { snackbar.showSnackbar("${m.name} $action failed: ${it.message}") }
        busy = false
        load()
    }

    LaunchedEffect(Unit) { load() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("POWER", color = Amber, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "refresh", tint = Muted) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Amber)
        // Relay wake: independent of the bridge on purpose: when the bridge
        // host is off the bridge is dead, so this button talks straight to the
        // relay. Shown whenever a relay + target are configured, even if the
        // machine list failed to load.
        if (AppCfg.relayUrl.isNotBlank() && AppCfg.wakeableHost.isNotBlank()) {
            var waking by remember { mutableStateOf(false) }
            Card(colors = CardDefaults.cardColors(containerColor = Surface1),
                shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Wake ${AppCfg.wakeableHost} · relay", color = Ink,
                            fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        Text("via the relay on the LAN, works even when the box is off",
                            color = Muted, fontSize = 11.sp)
                    }
                    if (waking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Teal)
                    else PowerBtn("Wake", Teal) {
                        waking = true
                        scope.launch {
                            runCatching { Api.postAbs("${AppCfg.relayUrl}/wake/${AppCfg.wakeableHost}", JSONObject()) }
                                .onSuccess { snackbar.showSnackbar("magic packet sent via relay") }
                                .onFailure { snackbar.showSnackbar("relay unreachable: ${it.message}") }
                            waking = false
                        }
                    }
                }
            }
        }
        machines.forEach { m ->
            Card(colors = CardDefaults.cardColors(containerColor = Surface1),
                shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(if (m.online) Good else Bad, CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name + if (m.self) "  · this box" else "",
                                color = Ink, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                            Text(
                                if (m.online) "online" else "offline" +
                                    (if (m.mac.isBlank() && "wake" in m.actions) " · no MAC yet" else ""),
                                color = Muted, fontSize = 11.sp)
                        }
                    }
                    if (m.actions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Wake shows when offline; power-downs when online.
                            if ("wake" in m.actions && !m.online) {
                                if (m.mac.isNotBlank())
                                    PowerBtn("Wake", Teal) { scope.launch { run(m, "wake") } }
                                else
                                    PowerBtn("Set MAC", Muted) { assign = m }
                            }
                            if (m.online) {
                                if ("suspend" in m.actions)
                                    PowerBtn("Suspend", Teal) { scope.launch { run(m, "suspend") } }
                                if ("reboot" in m.actions)
                                    PowerBtn("Reboot", Amber) { confirm = m to "reboot" }
                                if ("shutdown" in m.actions)
                                    PowerBtn("Shut down", Bad) { confirm = m to "shutdown" }
                            }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { (m, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Surface1,
            icon = { Icon(
                if (action == "reboot") Icons.Filled.RestartAlt else Icons.Filled.PowerSettingsNew,
                null, tint = if (action == "shutdown") Bad else Amber) },
            title = { Text("${action.replaceFirstChar { it.uppercase() }} ${m.name}?", color = Ink) },
            text = {
                Text(
                    if (m.self)
                        "This is the box the bridge runs on, so you'll lose the app connection and every service on it until it's back. " +
                        (if (action == "shutdown")
                            (if (AppCfg.relayUrl.isNotBlank())
                                "Wake it anytime with the relay button above (works from anywhere via the relay)."
                            else "Wake it with a magic packet from this tab (only over local WiFi; set a relay in Settings to wake it from anywhere).")
                        else "It'll come back on its own.")
                    else "Sends $action to ${m.name}.",
                    color = Muted, fontSize = 13.sp)
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = m; val act = action; confirm = null
                    scope.launch { run(target, act) }
                }) { Text(action.replaceFirstChar { it.uppercase() },
                    color = if (action == "shutdown") Bad else Amber) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel", color = Muted) } },
        )
    }

    assign?.let { m -> AssignMacDialog(m, snackbar, onClose = { assign = null; load() }) }
}

/** Scan the LAN and let the user pick which host is [machine], writing its MAC
 *  to the bridge so Wake works. Only reachable for a wake-capable machine that
 *  has no MAC yet: the target must be powered on to appear in the scan. */
@Composable
private fun AssignMacDialog(machine: PowerMachine, snackbar: SnackbarHostState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var hosts by remember { mutableStateOf<List<Triple<String, String, String>>?>(null) }

    LaunchedEffect(Unit) {
        runCatching {
            val rows = Api.get("/power/scan", slow = true).getJSONArray("hosts")
            hosts = (0 until rows.length()).map {
                val o = rows.getJSONObject(it)
                Triple(o.getString("ip"), o.getString("mac"), o.optString("hostname"))
            }
        }.onFailure { snackbar.showSnackbar("scan failed: ${it.message}") }
    }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Surface1,
        title = { Text("Which host is ${machine.name}?", color = Ink, fontSize = 15.sp) },
        text = {
            when (val h = hosts) {
                null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Teal, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp)); Text("scanning the LAN…", color = Muted, fontSize = 12.sp)
                }
                else -> Column(Modifier.heightIn(max = 360.dp)) {
                    Text("Turn the machine on first so it shows up.", color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    h.forEach { (ip, mac, host) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(host.ifBlank { ip }, color = Ink, fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace)
                                Text("$ip · $mac", color = Muted, fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching {
                                        Api.post("/power/assign", JSONObject()
                                            .put("name", machine.name).put("mac", mac).put("lan_ip", ip))
                                    }.onSuccess { snackbar.showSnackbar("${machine.name} → $mac"); onClose() }
                                        .onFailure { snackbar.showSnackbar("assign failed: ${it.message}") }
                                }
                            }) { Text("This one", color = Teal, fontSize = 12.sp) }
                        }
                        HorizontalDivider(color = Bg)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", color = Muted) } },
    )
}

@Composable
private fun PowerBtn(label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
    ) { Text(label, fontSize = 12.sp) }
}
