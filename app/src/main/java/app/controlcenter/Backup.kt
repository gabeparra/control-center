package app.controlcenter

import androidx.compose.foundation.layout.*
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/* Backup section of the Stack tab. Talks to whatever backup script your
   bridge exposes at /backup: the point is a snapshot landing on a separate
   physical disk from the one it's backing up. Shows freshness (the number
   that matters), lets you fire an out-of-band run, and polls while one is in
   flight. */

@Composable
fun BackupCard(snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var b by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }

    suspend fun fetch() {
        runCatching { b = Api.get("/backup") }
            .onFailure { snackbar.showSnackbar("backup status failed: ${it.message}") }
    }

    LaunchedEffect(Unit) { fetch(); loading = false }
    // While a run is in flight, keep the card live rather than making the user
    // pull to refresh a job that takes tens of minutes.
    LaunchedEffect(b?.optBoolean("running")) {
        while (b?.optBoolean("running") == true) {
            delay(10_000)
            fetch()
        }
    }

    val d = b
    Card(colors = CardDefaults.cardColors(containerColor = Surface1),
        shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("BACKUP", color = Amber, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Spacer(Modifier.weight(1f))
                if (loading || d?.optBoolean("running") == true)
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Teal)
                else IconButton(onClick = { scope.launch { fetch() } }) {
                    Icon(Icons.Filled.Refresh, "refresh", tint = Muted)
                }
            }
            Spacer(Modifier.height(6.dp))
            when {
                d == null -> Text(if (loading) "checking…" else "unavailable",
                    color = Muted, fontSize = 12.sp)
                !d.optBoolean("configured") -> Text("not configured", color = Bad, fontSize = 13.sp)
                d.optBoolean("running") -> Text("Running now…", color = Teal, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold)
                else -> {
                    val age = if (d.isNull("age_hours")) null else d.optDouble("age_hours")
                    val ok = d.optBoolean("ok", true)
                    val stale = age != null && age > 36
                    Text(
                        when {
                            !ok -> "Last run FAILED"
                            age == null -> "No completed run yet"
                            stale -> "Stale: ${age.toInt()}h since last run"
                            age < 1 -> "Fresh, under an hour ago"
                            else -> "Fresh, ${age.toInt()}h ago"
                        },
                        color = if (!ok || stale) Bad else Good,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            if (d != null) {
                val gb = d.optLong("snapshot_bytes") / 1_000_000_000.0
                val freeGb = d.optLong("dest_avail_bytes") / 1_000_000_000.0
                Text(buildString {
                    append("${d.optInt("snapshot_count")} snapshots")
                    if (gb > 0) append("  ·  last ${"%.0f".format(gb)} GB")
                    if (freeGb > 0) append("  ·  ${"%.0f".format(freeGb)} GB free")
                }, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("snapshots are kept on a separate disk",
                    color = Muted, fontSize = 10.sp)
            }
            if (d?.optBoolean("running") != true) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = {
                    scope.launch {
                        runCatching { Api.post("/backup/run", JSONObject(), slow = true) }
                            .onSuccess { snackbar.showSnackbar("backup started"); fetch() }
                            .onFailure { snackbar.showSnackbar("start failed: ${it.message}") }
                    }
                }) { Text("Back up now", color = Ink, fontSize = 12.sp) }
            }
        }
    }
}
