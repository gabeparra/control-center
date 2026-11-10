package app.controlcenter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
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

/* Containers section of the Stack tab: the other half of the stack. The
   systemd list covers native services; everything containerized (jellyfin +
   a photo library, a wiki, whatever else you run)
   shows up here. Collapsed by default when the list is long; anything not
   running is surfaced in the header regardless. */

data class Container(
    val name: String, val running: Boolean, val state: String,
    val status: String, val health: String, val image: String,
)

@Composable
fun ContainersSection(snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var containers by remember { mutableStateOf(listOf<Container>()) }
    var expanded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var logFor by remember { mutableStateOf<String?>(null) }

    fun load() = scope.launch {
        loading = true
        runCatching {
            val rows = Api.get("/containers").getJSONArray("containers")
            containers = (0 until rows.length()).map {
                val o = rows.getJSONObject(it)
                Container(o.getString("name"), o.getBoolean("running"),
                    o.optString("state"), o.optString("status"),
                    o.optString("health"), o.optString("image"))
            }
        }.onFailure { snackbar.showSnackbar("containers unreachable: ${it.message}") }
        loading = false
    }

    LaunchedEffect(Unit) { load() }

    logFor?.let { name ->
        LogDialog(name, "/container/$name/logs", "/container/$name/restart",
            snackbar, onClose = { logFor = null })
    }

    val down = containers.filter { !it.running || it.health == "unhealthy" }
    // Problems always visible; the healthy majority hides behind the toggle.
    val shown = if (expanded) containers else down

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
            Text("CONTAINERS", color = Amber, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                if (containers.isEmpty()) "" else
                    "${containers.count { it.running }}/${containers.size} up" +
                        if (down.isNotEmpty()) "  ·  ${down.size} need attention" else "",
                color = if (down.isNotEmpty()) Bad else Muted, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Muted)
            else IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "refresh", tint = Muted) }
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                "toggle", tint = Muted)
        }
        if (!expanded && down.isEmpty() && containers.isNotEmpty()) {
            Text("all healthy, tap to show all ${containers.size}",
                color = Muted, fontSize = 11.sp)
        }
        shown.forEach { c ->
            var busy by remember(c.name) { mutableStateOf(false) }
            Card(colors = CardDefaults.cardColors(containerColor = Surface1),
                shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).background(
                        when {
                            c.health == "unhealthy" -> Bad
                            c.running -> Good
                            else -> Bad
                        }, CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).clickable { logFor = c.name }) {
                        Text(c.name, color = Ink, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        Text(listOfNotNull(c.status.ifBlank { null },
                            c.health.ifBlank { null }).joinToString(" · ") + "  ·  logs",
                            color = if (c.health == "unhealthy") Bad else Muted, fontSize = 10.sp)
                    }
                    fun control(action: String) {
                        busy = true
                        scope.launch {
                            runCatching { Api.post("/container/${c.name}/$action", JSONObject()) }
                                .onSuccess { snackbar.showSnackbar("${c.name} $action"); load() }
                                .onFailure { snackbar.showSnackbar("$action failed: ${it.message}") }
                            busy = false
                        }
                    }
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else if (c.running) {
                        IconButton(onClick = { control("restart") }) {
                            Icon(Icons.Filled.RestartAlt, "restart", tint = Muted)
                        }
                        IconButton(onClick = { control("stop") }) {
                            Icon(Icons.Filled.Stop, "stop", tint = Bad)
                        }
                    } else {
                        IconButton(onClick = { control("start") }) {
                            Icon(Icons.Filled.PlayArrow, "start", tint = Good)
                        }
                    }
                }
            }
        }
    }
}
