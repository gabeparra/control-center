package app.controlcenter

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

/* Home tab: the Home Assistant entities worth a tap from the phone.
 *
 * The list and the control allowlist both live on the bridge, not here: adding
 * a light later should not need an APK. This screen only renders what it is
 * given and posts back an action.
 */

data class HomeEntity(
    val label: String, val entityId: String, val kind: String,
    val state: String, val unit: String, val controllable: Boolean,
)

@Composable
fun HomeScreen(snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var entities by remember { mutableStateOf(listOf<HomeEntity>()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirmOpen by remember { mutableStateOf<HomeEntity?>(null) }

    fun load() = scope.launch {
        runCatching {
            val a = Api.get("/home").getJSONArray("entities")
            entities = (0 until a.length()).map {
                val o = a.getJSONObject(it)
                HomeEntity(
                    o.getString("label"), o.getString("entity_id"), o.getString("kind"),
                    o.optString("state"), o.optString("unit"), o.optBoolean("controllable"),
                )
            }
        }.onFailure { snackbar.showSnackbar("home failed: ${it.message}") }
        loading = false
    }

    fun act(e: HomeEntity, action: String) = scope.launch {
        busy = e.entityId
        runCatching {
            Api.post("/home/act", JSONObject().put("entity_id", e.entityId).put("action", action))
        }.onFailure { snackbar.showSnackbar("${e.label}: ${it.message}") }
        busy = null
        // HA needs a beat to reflect the new state, especially a moving cover.
        kotlinx.coroutines.delay(1200)
        load()
    }

    LaunchedEffect(Unit) { load() }

    // ---- Zigbee (via Zigbee2MQTT on the bridge side): pairing from the couch ----
    var zig by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var zigBusy by remember { mutableStateOf(false) }
    fun loadZig() = scope.launch { if (Caps.has("zigbee")) runCatching { zig = Api.get("/zigbee") } }
    fun setPermit(on: Boolean) = scope.launch {
        zigBusy = true
        runCatching { Api.post("/zigbee/permit", org.json.JSONObject().put("on", on)) }
            .onSuccess { kotlinx.coroutines.delay(1500); loadZig() }
            .onFailure { snackbar.showSnackbar("zigbee: ${it.message ?: "failed"}") }
        zigBusy = false
    }
    LaunchedEffect(Unit) { loadZig() }
    // While the pairing window is open, poll so a joining bulb appears live , 
    // the whole point is watching it show up without touching a computer.
    LaunchedEffect(zig?.optBoolean("permit_join") == true) {
        while (zig?.optBoolean("permit_join") == true) {
            kotlinx.coroutines.delay(5000)
            loadZig()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("HOME", color = Amber, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "refresh", tint = Muted) }
        }

        if (loading && entities.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Amber, strokeWidth = 2.dp,
                    modifier = Modifier.size(26.dp))
            }
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)) {
            if (Caps.has("zigbee")) item(key = "zigbee") {
                val z = zig
                val permit = z?.optBoolean("permit_join") == true
                Card(colors = CardDefaults.cardColors(containerColor = Surface1),
                    shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("ZIGBEE DEVICES", color = Amber, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                            Spacer(Modifier.weight(1f))
                            when {
                                zigBusy -> CircularProgressIndicator(color = Amber,
                                    strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                z == null -> Text("unreachable", color = Muted, fontSize = 11.sp)
                                else -> TextButton(onClick = { setPermit(!permit) }) {
                                    Text(if (permit) "close pairing" else "pair new device",
                                        color = if (permit) Bad else Amber, fontSize = 12.sp)
                                }
                            }
                        }
                        if (permit) {
                            Text("pairing open (about 4 min). Put the device in pairing " +
                                 "mode (bulbs usually need a quick off/on reset sequence)",
                                color = Amber, fontSize = 11.sp)
                            Spacer(Modifier.height(4.dp))
                        }
                        val devs = z?.optJSONArray("devices")
                        if (devs == null || devs.length() == 0) {
                            Text("no devices paired yet", color = Muted, fontSize = 12.sp)
                        } else {
                            (0 until devs.length()).forEach { i ->
                                val d = devs.getJSONObject(i)
                                Row(Modifier.padding(top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(d.optString("name"), color = Ink, fontSize = 13.sp,
                                        modifier = Modifier.weight(1f))
                                    Text(listOf(d.optString("vendor"), d.optString("model"))
                                        .filter { it.isNotBlank() && it != "?" }
                                        .joinToString(" "),
                                        color = Muted, fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
            items(entities, key = { it.entityId }) { e ->
                Card(colors = CardDefaults.cardColors(containerColor = Surface1),
                    shape = RoundedCornerShape(10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.label, color = Ink, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold)
                            Text("${e.state}${if (e.unit.isNotBlank()) " ${e.unit}" else ""}",
                                color = if (e.state == "on" || e.state == "open") Good else Muted,
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        when {
                            busy == e.entityId ->
                                CircularProgressIndicator(color = Amber, strokeWidth = 2.dp,
                                    modifier = Modifier.size(20.dp))
                            e.kind == "switch" && e.controllable ->
                                Switch(
                                    checked = e.state == "on",
                                    onCheckedChange = { act(e, if (it) "on" else "off") },
                                    colors = SwitchDefaults.colors(checkedTrackColor = Amber),
                                )
                            e.kind == "cover" && e.controllable ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    // Opening is gated: this can be a physical door or
                                    // gate, and a mis-tap should not open it.
                                    TextButton(onClick = { confirmOpen = e }) {
                                        Text("Open", color = Amber, fontSize = 12.sp)
                                    }
                                    TextButton(onClick = { act(e, "close") }) {
                                        Text("Close", color = Muted, fontSize = 12.sp)
                                    }
                                }
                        }
                    }
                }
            }
        }
    }

    confirmOpen?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmOpen = null },
            containerColor = Surface1,
            title = { Text("Open the ${e.label.lowercase()}?", color = Ink, fontSize = 16.sp) },
            text = {
                Text("This physically opens it. Make sure that is what you want.",
                    color = Muted, fontSize = 13.sp)
            },
            confirmButton = {
                Button(colors = ButtonDefaults.buttonColors(containerColor = Amber),
                    onClick = { val t = e; confirmOpen = null; act(t, "open") }) { Text("Open") }
            },
            dismissButton = {
                TextButton(onClick = { confirmOpen = null }) { Text("Cancel", color = Muted) }
            },
        )
    }
}
