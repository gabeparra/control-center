package app.controlcenter

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

/* Overview: the first tab, and the answer to "is everything okay".
 *
 * Replaces the old split across Stack / Actions / Alerts / Links, where the one
 * thing you actually open the app for (all machines side by side) did
 * not exist anywhere.
 *
 * Each section loads independently. /machines may gather from several hosts
 * and take a second or two; AdGuard and the service list are instant, and
 * should not wait for it.
 */

data class MachineRow(
    val name: String, val role: String, val online: Boolean,
    val loadPct: Double, val cores: Int,
    val memPct: Double, val memUsedMb: Int, val memTotalMb: Int,
    val diskPct: Double, val diskUsedGb: Int, val diskTotalGb: Int,
    val tempC: Double?, val watts: Int?, val onBattery: Boolean, val uptimeS: Long,
    val gpu: GpuRow?,
    // Measured, not projected: real hourly samples. Null where a box has not
    // been logging long enough to have any.
    val kwhDay: Double?, val kwhWeek: Double?, val kwhMonth: Double?, val avgWDay: Int?,
    val updates: Int?, val updatable: Boolean,
    /** Needs attention for reasons an update count does not cover: a box can be
     *  fully patched and still running a kernel it no longer has installed. */
    val reboot: Boolean = false, val failed: Int = 0,
)

data class GpuRow(
    val name: String, val utilPct: Int,
    val memUsedMb: Int, val memTotalMb: Int, val tempC: Int,
)

@Composable
private fun Badge(label: String, tint: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier.padding(end = 6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(tint.copy(alpha = 0.18f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(label, color = tint, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}

private fun humanUptime(s: Long): String = when {
    s <= 0 -> "-"
    s < 3600 -> "${s / 60}m"
    s < 86400 -> "${s / 3600}h"
    else -> "${s / 86400}d"
}

/** Red above 90, amber above 75, silver below. Encodes severity in colour so a
 *  problem reads at a glance rather than requiring you to compare numbers. */
private fun sev(pct: Double): Color = when {
    pct >= 90 -> Bad
    pct >= 75 -> Amber
    else -> Good
}

private enum class OverviewPane(val label: String) {
    STATUS("Status"), ACTIONS("Actions"), LINKS("Links"), ALERTS("Alerts"),
}

/** Status is the dashboard; the other three are the tabs that used to sit
 *  beside it. Status stays the default because it is what the app is opened
 *  for: absorbing the others must not cost a tap to reach the thing you came
 *  for. */
@Composable
fun OverviewScreen(
    snackbar: SnackbarHostState,
    onSaveUrl: (String) -> Unit,
    onUpdateFound: (Updater.Info) -> Unit,
) {
    var pane by rememberSaveable { mutableStateOf(OverviewPane.STATUS.name) }
    // Only the panes this bridge can fill. A saved pane that is gone falls back to Status.
    val panes = OverviewPane.entries.filter {
        when (it) {
            OverviewPane.LINKS -> Caps.has("links")
            OverviewPane.ALERTS -> Caps.hasAny("alerts", "events")
            else -> true
        }
    }
    val current = runCatching { OverviewPane.valueOf(pane) }.getOrDefault(OverviewPane.STATUS)
        .takeIf { it in panes } ?: OverviewPane.STATUS

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            panes.forEach { p ->
                val on = p == current
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (on) Amber else Surface1)
                        .clickable { pane = p.name }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(p.label, color = if (on) Bg else Ink, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Box(Modifier.weight(1f)) {
            when (current) {
                OverviewPane.STATUS -> OverviewStatus(snackbar)
                OverviewPane.ACTIONS -> ActionsScreen(snackbar, onSaveUrl, onUpdateFound)
                OverviewPane.LINKS -> LinksScreen(snackbar)
                OverviewPane.ALERTS -> AlertsScreen(snackbar)
            }
        }
    }
}

@Composable
private fun OverviewStatus(snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var machines by remember { mutableStateOf(listOf<MachineRow>()) }
    var adguard by remember { mutableStateOf<JSONObject?>(null) }
    var down by remember { mutableStateOf(listOf<String>()) }
    var svcTotal by remember { mutableStateOf(0) }
    var todoOpen by remember { mutableStateOf<Int?>(null) }
    var loadingMachines by remember { mutableStateOf(true) }
    var busyProtect by remember { mutableStateOf(false) }
    var confirmUpdate by remember { mutableStateOf<MachineRow?>(null) }
    var askPassword by remember { mutableStateOf<MachineRow?>(null) }
    var updating by remember { mutableStateOf(setOf<String>()) }
    var activeAlerts by remember { mutableStateOf(listOf<ActiveAlert>()) }
    var wan by remember { mutableStateOf<JSONObject?>(null) }

    fun startUpdate(m: MachineRow) = scope.launch {
        updating = updating + m.name
        runCatching { Api.post("/machines/${m.name}/update", JSONObject(), slow = true) }
            .onSuccess { snackbar.showSnackbar("${m.name}: updating, you'll get a notification") }
            .onFailure { snackbar.showSnackbar("${m.name}: ${it.message}") }
        updating = updating - m.name
    }

    var restarting by remember { mutableStateOf(setOf<String>()) }
    fun restartAlertTarget(key: String) = scope.launch {
        val path = alertRestartPath(key) ?: return@launch
        restarting = restarting + key
        runCatching { Api.post(path, JSONObject()) }
            .onSuccess { snackbar.showSnackbar("restarted, alert clears on next scan") }
            .onFailure { snackbar.showSnackbar("restart: ${it.message ?: "failed"}") }
        restarting = restarting - key
    }

    // Dismiss = acknowledge on the bridge, so the finding stops appearing here
    // and stops re-notifying until it clears and recurs.
    fun dismissAlert(key: String) = scope.launch {
        runCatching { Api.post("/alerts/ack", JSONObject().put("key", key).put("ack", true)) }
            .onSuccess {
                activeAlerts = activeAlerts.filter { it.key != key }
                AlertBadge.unacked = (AlertBadge.unacked - 1).coerceAtLeast(0)
            }
            .onFailure { snackbar.showSnackbar("dismiss: ${it.message ?: "failed"}") }
    }

    fun loadMachines() = scope.launch {
        loadingMachines = true
        if (Caps.has("machines")) runCatching {
            val a = Api.get("/machines").getJSONArray("machines")
            machines = (0 until a.length()).map {
                val o = a.getJSONObject(it)
                MachineRow(
                    o.getString("name"), o.optString("role"), o.optBoolean("online"),
                    o.optDouble("load_pct", 0.0), o.optInt("cores"),
                    o.optDouble("mem_pct", 0.0), o.optInt("mem_used_mb"), o.optInt("mem_total_mb"),
                    o.optDouble("disk_pct", 0.0), o.optInt("disk_used_gb"), o.optInt("disk_total_gb"),
                    if (o.isNull("temp_c")) null else o.optDouble("temp_c"),
                    if (o.has("watts")) o.optInt("watts") else null,
                    o.optBoolean("on_battery"), o.optLong("uptime_s"),
                    o.optJSONObject("gpu")?.let { g ->
                        GpuRow(
                            g.optString("name"), g.optInt("util_pct"),
                            g.optInt("mem_used_mb"), g.optInt("mem_total_mb"),
                            g.optInt("temp_c"),
                        )
                    },
                    if (o.isNull("kwh_day")) null else o.optDouble("kwh_day"),
                    if (o.isNull("kwh_week")) null else o.optDouble("kwh_week"),
                    if (o.isNull("kwh_month")) null else o.optDouble("kwh_month"),
                    if (o.isNull("avg_w_day")) null else o.optInt("avg_w_day"),
                    if (o.has("updates")) o.optInt("updates") else null,
                    o.optBoolean("updatable", false),
                )
            }
        }.onFailure { snackbar.showSnackbar("machines: ${it.message}") }
        loadingMachines = false
    }

    fun loadRest() = scope.launch {
        if (Caps.has("adguard")) runCatching { adguard = Api.get("/adguard") }
        if (Caps.has("services")) runCatching {
            val a = Api.get("/stack").getJSONArray("services")
            svcTotal = a.length()
            down = (0 until a.length()).map { a.getJSONObject(it) }
                .filter { !it.optBoolean("active") }.map { it.getString("name") }
        }
        // /todo is not part of the capability list; only bridges that do not
        // advertise anything are asked, so it never errors against a newer one.
        if (Caps.legacy) runCatching { todoOpen = Api.get("/todo").optInt("open") }
        if (Caps.has("alerts")) runCatching {
            val a = Api.get("/alerts/active").getJSONArray("alerts")
            activeAlerts = (0 until a.length()).map {
                val o = a.getJSONObject(it)
                ActiveAlert(o.getString("key"), o.optString("title"), o.optString("text"),
                    o.optBoolean("critical"), o.optDouble("ts"), o.optBoolean("acknowledged"))
            }.filter { !it.acknowledged }  // acknowledged ones are gone from the main screen by design
            AlertBadge.unacked = activeAlerts.size
        }
        if (Caps.has("wan")) runCatching { wan = Api.get("/wan") }
    }

    fun refreshAll() { loadMachines(); loadRest() }

    LaunchedEffect(Unit) { refreshAll() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 14.dp)) {

        Row(Modifier.padding(top = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("OVERVIEW", color = Amber, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            if (loadingMachines) CircularProgressIndicator(
                color = Muted, strokeWidth = 2.dp, modifier = Modifier.size(15.dp))
            IconButton(onClick = { refreshAll() }) {
                Icon(Icons.Filled.Refresh, "refresh", tint = Muted)
            }
        }

        // ---- active alerts: current watchdog findings, dismiss to acknowledge ----
        // These are the things wrong right now. Dismissing one acknowledges it on
        // the bridge so it leaves the screen and stops nagging until it recurs.
        activeAlerts.forEach { a ->
            val edge = if (a.critical) Bad else Amber
            // Tapping the card lands on this finding in the Audit tab; the
            // dismiss text keeps its own (inner) click and wins the hit.
            Card(colors = CardDefaults.cardColors(containerColor = Surface1),
                border = BorderStroke(1.dp, edge),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().clickable { DeepLink.open(a.key) }) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(edge, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(if (a.critical) "CRITICAL" else "ALERT", color = edge, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Spacer(Modifier.weight(1f))
                        if (alertRestartPath(a.key) != null) {
                            if (a.key in restarting)
                                CircularProgressIndicator(Modifier.size(14.dp),
                                    strokeWidth = 2.dp, color = Teal)
                            else
                                Text("restart", color = Teal, fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { restartAlertTarget(a.key) })
                            Spacer(Modifier.width(14.dp))
                        }
                        Text("dismiss", color = Muted, fontSize = 12.sp,
                            modifier = Modifier.clickable { dismissAlert(a.key) })
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(a.title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (a.text.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(a.text, color = Muted, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text("tap to open in Audit", color = Teal, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ---- WAN line health: "is it me or the ISP", answered ----
        // Drop history comes from the bridge's own uptime check against a public
        // address, so a slow evening can be checked against actual line outages.
        wan?.let { w ->
            val up = w.optBoolean("up")
            val d24 = w.optInt("drops_24h")
            val d7 = w.optInt("drops_7d")
            val outages = w.optJSONArray("outages")
            val last = outages?.takeIf { it.length() > 0 }?.optJSONObject(outages.length() - 1)
            val lastTxt = last?.let {
                val t = java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date((it.optDouble("start") * 1000).toLong()))
                val secs = it.optInt("secs")
                "last $t" + if (secs > 0) " · ${(secs + 59) / 60}m" else " · ongoing"
            }
            val accent = if (!up) Bad else if (d24 > 0) Amber else Muted
            Card(colors = CardDefaults.cardColors(containerColor = Surface1),
                shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(if (up) Good else Bad, CircleShape))
                    Spacer(Modifier.width(9.dp))
                    Text("WAN", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (!up) "DOWN"
                        else if (d24 == 0 && d7 == 0) "clean all week"
                        else "$d24 ${if (d24 == 1) "drop" else "drops"} 24h · $d7 wk",
                        color = accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.weight(1f))
                    if (lastTxt != null && (d24 > 0 || !up))
                        Text(lastTxt, color = Muted, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ---- energy: the UPS-metered boxes side by side, in kWh and dollars ----
        // Heavy machines cost far more than idle ones; money is the unit that
        // makes that legible at a glance. Rate comes from Settings.
        val metered = machines.filter { it.kwhDay != null || it.watts != null }
        if (metered.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Surface1),
                shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ENERGY", color = Amber, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Spacer(Modifier.weight(1f))
                        Text("@ $${"%.2f".format(AppCfg.kwhRate)}/kWh",
                            color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    metered.forEach { m ->
                        Spacer(Modifier.height(7.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.name, color = Ink, fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            m.watts?.let {
                                Text("$it W now", color = Teal, fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                        val day = m.kwhDay
                        val mo = m.kwhMonth
                        if (day != null || mo != null) {
                            val parts = buildList {
                                day?.let { add("today ${"%.1f".format(it)} kWh · $${"%.2f".format(it * AppCfg.kwhRate)}") }
                                mo?.let { add("month ${"%.1f".format(it)} kWh · $${"%.2f".format(it * AppCfg.kwhRate)}") }
                            }
                            Text(parts.joinToString("   "), color = Muted, fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace)
                        } else {
                            Text("metering, first hour accumulating", color = Muted,
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ---- machines: the reason this tab exists ----
        machines.forEach { m ->
            // Wake only where a magic packet can land: the one machine named in
            // Settings as the wake target (a LAN peer your relay can reach). A
            // box with no LAN, or the box the bridge itself runs on, can't be
            // woken this way: that's the wake relay's job.
            val wakeable = Caps.has("wake") && !m.online &&
                AppCfg.wakeableHost.isNotBlank() && m.name == AppCfg.wakeableHost
            MachineCard(
                m,
                busy = m.name in updating,
                // Applying package updates (and the sudo password it may need)
                // only shows for bridges that offer the updates feature.
                canUpdate = Caps.has("updates"),
                onUpdate = { if (m.updatable) confirmUpdate = m else askPassword = m },
                onWake = if (!wakeable) null else {
                    {
                        scope.launch {
                            runCatching {
                                Api.post("/power/wake", JSONObject().put("name", m.name))
                            }.onSuccess {
                                snackbar.showSnackbar("magic packet sent to ${m.name}, give it a minute")
                            }.onFailure {
                                snackbar.showSnackbar("wake: ${it.message ?: "failed"}")
                            }
                        }
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
        }
        if (machines.isEmpty() && !loadingMachines && Caps.has("machines"))
            Text("No machine data.", color = Muted, fontSize = 12.sp)

        Spacer(Modifier.height(6.dp))

        // ---- services ----
        if (Caps.has("services")) Card(colors = CardDefaults.cardColors(containerColor = Surface1),
            shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Services", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (down.isEmpty()) "all $svcTotal running"
                        else "${down.size} down: ${down.joinToString(", ")}",
                        color = if (down.isEmpty()) Muted else Bad,
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    )
                }
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp))
                    .background(if (down.isEmpty()) Good else Bad))
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---- adguard ----
        adguard?.let { ag ->
            val protection = ag.optBoolean("protection")
            Card(colors = CardDefaults.cardColors(containerColor = Surface1),
                shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("AdGuard", color = Ink, fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold)
                            Text(
                                "${ag.optInt("blocked")} blocked of ${ag.optInt("queries")} " +
                                    "(${ag.optDouble("blocked_pct", 0.0)}%)",
                                color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            )
                        }
                        if (busyProtect) CircularProgressIndicator(
                            color = Amber, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        else Switch(
                            checked = protection,
                            onCheckedChange = { want ->
                                busyProtect = true
                                scope.launch {
                                    runCatching {
                                        Api.post("/adguard/protection",
                                            JSONObject().put("enabled", want))
                                    }.onFailure { snackbar.showSnackbar("adguard: ${it.message}") }
                                    busyProtect = false
                                    runCatching { adguard = Api.get("/adguard") }
                                }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = Amber),
                        )
                    }
                    if (!protection)
                        Text("Blocking is PAUSED", color = Bad, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("${ag.optInt("rules")} rules · ${ag.optDouble("avg_ms", 0.0)} ms avg",
                        color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ---- todos ----
        todoOpen?.let { n ->
            Card(colors = CardDefaults.cardColors(containerColor = Surface1),
                shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Todo", color = Ink, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(if (n == 0) "clear" else "$n open",
                        color = if (n == 0) Good else Amber, fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    confirmUpdate?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmUpdate = null },
            containerColor = Surface1,
            title = { Text("Update ${m.name}?", color = Ink, fontSize = 16.sp) },
            text = {
                Text("${m.updates} packages. This runs in the background and can take " +
                     "several minutes; you'll get a notification when it finishes.",
                    color = Muted, fontSize = 13.sp)
            },
            confirmButton = {
                Button(colors = ButtonDefaults.buttonColors(containerColor = Amber),
                    onClick = { val t = m; confirmUpdate = null; startUpdate(t) }) { Text("Update") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUpdate = null }) { Text("Cancel", color = Muted) }
            },
        )
    }

    askPassword?.let { m ->
        var pw by remember(m.name) { mutableStateOf("") }
        var saving by remember(m.name) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { askPassword = null },
            containerColor = Surface1,
            title = { Text("sudo password for ${m.name}", color = Ink, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Stored on the bridge, not in this app, and checked against " +
                         "sudo before it is saved.", color = Muted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = pw, onValueChange = { pw = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("password") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Amber,
                            focusedTextColor = Ink, unfocusedTextColor = Ink),
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = pw.isNotBlank() && !saving,
                    colors = ButtonDefaults.buttonColors(containerColor = Amber),
                    onClick = {
                        saving = true
                        val target = m; val secret = pw
                        scope.launch {
                            runCatching {
                                Api.post("/machines/${target.name}/sudo",
                                    JSONObject().put("password", secret), slow = true)
                            }.onSuccess {
                                askPassword = null
                                snackbar.showSnackbar("${target.name}: password verified")
                                loadMachines()
                            }.onFailure {
                                snackbar.showSnackbar(it.message ?: "rejected")
                            }
                            saving = false
                        }
                    },
                ) { Text(if (saving) "Checking…" else "Save") }
            },
            dismissButton = {
                TextButton(onClick = { askPassword = null }) { Text("Cancel", color = Muted) }
            },
        )
    }
}

@Composable
private fun MachineCard(m: MachineRow, busy: Boolean, canUpdate: Boolean, onUpdate: () -> Unit,
                        onWake: (() -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface1),
        shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (m.online) Good else Bad))
                Spacer(Modifier.width(8.dp))
                Text(m.name, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(m.role, color = Muted, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                m.watts?.let {
                    Text(if (m.onBattery) "$it W · BATT" else "$it W",
                        color = if (m.onBattery) Bad else Teal,
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                }
                m.tempC?.let {
                    Text("${it}°", color = if (it >= 75) Bad else Muted,
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                m.updates?.takeIf { it > 0 }?.let { n ->
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(4.dp))
                            .background(Amber.copy(alpha = if (n >= 25) 0.30f else 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("$n upd", color = Amber, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (!m.online) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("offline", color = Bad, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.weight(1f))
                    // An offline box you can only stare at is a wall poster; the
                    // wake path was buried in Stack > Power where it helped nobody.
                    if (onWake != null)
                        OutlinedButton(onClick = onWake, contentPadding =
                            PaddingValues(horizontal = 14.dp, vertical = 2.dp)) {
                            Text("wake", color = Teal, fontSize = 12.sp)
                        }
                }
            } else {
                Meter("cpu", m.loadPct, "${m.cores} cores")
                Meter("ram", m.memPct, "${m.memUsedMb / 1024}/${m.memTotalMb / 1024} GB")
                Meter("disk", m.diskPct, "${m.diskUsedGb}/${m.diskTotalGb} GB")
                // VRAM matters more than utilisation here: a full card is what
                // blocks the next generation, and idle util says nothing about it.
                m.gpu?.let { g ->
                    val vram = if (g.memTotalMb > 0)
                        g.memUsedMb.toDouble() / g.memTotalMb * 100 else 0.0
                    Meter("vram", vram,
                        "${g.memUsedMb / 1024}/${g.memTotalMb / 1024} GB · ${g.utilPct}% · ${g.tempC}°")
                    Text(g.name, color = Teal, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("up ${humanUptime(m.uptimeS)}", color = Muted, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace)
                    m.kwhDay?.let { d ->
                        Text("${d} kWh today · ${m.avgWDay ?: 0} W avg",
                            color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    m.kwhWeek?.let { w ->
                        Text("${w} kWh / wk", color = Muted, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                    // The headline figure: real energy drawn over the last 30
                    // days (measured, not projected), per box.
                    m.kwhMonth?.let { mo ->
                        Text("${mo} kWh / mo", color = Teal, fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                    }
                    if (m.watts == null && m.online)
                        Text("no UPS", color = Muted, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                    // These rot quietly: a box can go a long time asking for a
                    // reboot across several kernel updates while the app
                    // cheerfully shows it green.
                    if (m.reboot) Badge("reboot", Bad)
                    if (m.failed > 0) Badge("${m.failed} failed", Bad)
                    Spacer(Modifier.weight(1f))
                    if (canUpdate && (m.updates ?: 0) > 0) {
                        if (busy) CircularProgressIndicator(
                            color = Amber, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                        else TextButton(
                            onClick = onUpdate,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(22.dp),
                        ) {
                            Text(if (m.updatable) "Update" else "Needs password",
                                color = Amber, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Meter(label: String, pct: Double, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row {
            Text(label, color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(34.dp))
            Text("${pct.toInt()}%", color = sev(pct), fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                modifier = Modifier.width(38.dp))
            Text(detail, color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Bg)) {
            Box(Modifier.fillMaxWidth((pct / 100.0).coerceIn(0.0, 1.0).toFloat())
                .height(3.dp).background(sev(pct), RoundedCornerShape(2.dp)))
        }
    }
}
