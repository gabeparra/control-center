package app.controlcenter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

/* Machines: per-box detail, behind a selector.
 *
 * Overview answers "is everything okay" for all machines at once. This answers
 * "what is actually going on with this one", and holds the things that only
 * make sense per machine: the DNS filter's detail, and the bridge host's tmux
 * and coding-agent sessions.
 */

data class ClaudeSession(
    val pid: Int, val cwd: String, val ageS: Long, val tmux: String?, val yolo: Boolean,
)

data class TmuxSession(val name: String, val windows: Int, val attached: Boolean)

private fun age(s: Long): String =
    if (s >= 3600) "${s / 3600}h${"%02d".format((s % 3600) / 60)}m" else "${s / 60}m"

/** Detail is per-machine; System is everything the old Stack tab held , 
 *  containers, backup, power history, updates, the network device list and
 *  the per-service log dialog. Absorbed whole rather than picked apart, so none
 *  of it is quietly lost in the move. */
@Composable
fun MachinesScreen(snackbar: SnackbarHostState) {
    var top by rememberSaveable { mutableStateOf("DETAIL") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("DETAIL" to "Detail", "SYSTEM" to "System", "TERM" to "Term")
                .filter { it.first != "TERM" || Caps.has("terminal") }
                .forEach { (k, label) ->
                val on = top == k
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (on) Amber else Surface1)
                        .clickable { top = k }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(label, color = if (on) Bg else Ink, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Box(Modifier.weight(1f)) {
            when (if (top == "TERM" && !Caps.has("terminal")) "DETAIL" else top) {
                // tmux belongs with the machine it runs on, and this screen
                // already lists the sessions it attaches to.
                "SYSTEM" -> StackScreen(snackbar)
                "TERM" -> TermScreen(snackbar)
                else -> MachinesDetail(snackbar)
            }
        }
    }
}

@Composable
private fun MachinesDetail(snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var machines by remember { mutableStateOf(listOf<MachineRow>()) }
    var selected by remember { mutableStateOf(AppCfg.selfHost) }
    var services by remember { mutableStateOf(listOf<Svc>()) }
    var adguard by remember { mutableStateOf<JSONObject?>(null) }
    var claude by remember { mutableStateOf(listOf<ClaudeSession>()) }
    var tmux by remember { mutableStateOf(listOf<TmuxSession>()) }
    var loading by remember { mutableStateOf(true) }

    fun load() = scope.launch {
        loading = true
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
                        GpuRow(g.optString("name"), g.optInt("util_pct"),
                            g.optInt("mem_used_mb"), g.optInt("mem_total_mb"), g.optInt("temp_c"))
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
        // Per-machine extras. Only fetched for the box they belong to.
        if (Caps.has("services")) runCatching {
            val rows = Api.get("/stack").getJSONArray("services")
            services = (0 until rows.length()).map {
                val o = rows.getJSONObject(it)
                Svc(o.getString("name"), o.getBoolean("active"), o.getString("state"))
            }
        }
        if (Caps.has("adguard")) runCatching { adguard = Api.get("/adguard") }
        if (Caps.has("claude_sessions")) runCatching {
            val a = Api.get("/claude/sessions").getJSONArray("sessions")
            claude = (0 until a.length()).map {
                val o = a.getJSONObject(it)
                ClaudeSession(o.getInt("pid"), o.optString("cwd"), o.optLong("age_s"),
                    if (o.isNull("tmux")) null else o.optString("tmux"), o.optBoolean("yolo"))
            }
        }
        if (Caps.has("tmux_overview")) runCatching {
            val a = Api.get("/tmux/sessions").getJSONArray("sessions")
            tmux = (0 until a.length()).map {
                val o = a.getJSONObject(it)
                TmuxSession(o.getString("name"), o.optInt("windows"), o.optBoolean("attached"))
            }
        }
        loading = false
    }

    LaunchedEffect(Unit) { load() }
    // Default to the configured bridge host, falling back to whatever loads
    // first once the machine list is in.
    LaunchedEffect(machines) {
        if (machines.isNotEmpty() && machines.none { it.name == selected })
            selected = machines.first().name
    }
    val m = machines.firstOrNull { it.name == selected }
    // The machine the bridge runs on. Without a configured name, assume the
    // first one listed, which is the bridge host on a single-machine setup.
    val hostName = AppCfg.selfHost.ifBlank { machines.firstOrNull()?.name ?: "" }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("MACHINES", color = Amber, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            if (loading) CircularProgressIndicator(color = Muted, strokeWidth = 2.dp,
                modifier = Modifier.size(15.dp))
            IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "refresh", tint = Muted) }
        }

        // selector
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            machines.forEach { row ->
                val on = row.name == selected
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (on) Amber else Surface1)
                        .clickable { selected = row.name }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp))
                            .background(if (row.online) Good else Bad))
                        Spacer(Modifier.width(6.dp))
                        Text(row.name, color = if (on) Bg else Ink, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {

            m?.let { Section(it.role.ifBlank { it.name }) { MachineDetail(it) } }

            // ---- this box only: what is actually running in it ----
            if (hostName.isNotBlank() && selected == hostName) {
                if (Caps.has("tmux_overview")) Section("tmux · ${tmux.size} sessions") {
                    tmux.forEach { t ->
                        KeyRow(t.name + if (t.attached) "  (attached)" else "",
                            "${t.windows}w", if (t.attached) Good else Muted)
                    }
                }
                if (Caps.has("claude_sessions")) Section("claude · ${claude.size} running") {
                    if (claude.isEmpty()) Text("none", color = Muted, fontSize = 12.sp)
                    claude.forEach { c ->
                        KeyRow(
                            "${c.tmux ?: "pid ${c.pid}"}${if (c.yolo) "  ⚡" else ""}",
                            age(c.ageS), Teal,
                        )
                    }
                }
                if (Caps.has("services")) Section("services · ${services.count { it.active }}/${services.size} up") {
                    services.filter { !it.active }.forEach { KeyRow(it.name, it.state, Bad) }
                    if (services.all { it.active })
                        Text("all running", color = Muted, fontSize = 12.sp)
                }
            }

            // ---- DNS filter stats (the bridge reports one AdGuard instance) ----
            if (hostName.isNotBlank() && selected == hostName) adguard?.let { ag ->
                Section("AdGuard") {
                    KeyRow("queries", "${ag.optInt("queries")}", Ink)
                    KeyRow("blocked", "${ag.optInt("blocked")} (${ag.optDouble("blocked_pct", 0.0)}%)", Amber)
                    KeyRow("rules", "${ag.optInt("rules")}", Muted)
                    KeyRow("avg", "${ag.optDouble("avg_ms", 0.0)} ms", Muted)
                    KeyRow("protection", if (ag.optBoolean("protection")) "on" else "PAUSED",
                        if (ag.optBoolean("protection")) Good else Bad)
                }
                Section("top clients") {
                    val rows = ag.optJSONArray("top_clients")
                    for (i in 0 until (rows?.length() ?: 0)) {
                        val o = rows!!.getJSONObject(i)
                        KeyRow(o.optString("name"), "${o.optInt("count")}", Muted)
                    }
                }
                Section("most blocked") {
                    val rows = ag.optJSONArray("top_blocked")
                    for (i in 0 until (rows?.length() ?: 0)) {
                        val o = rows!!.getJSONObject(i)
                        KeyRow(o.optString("name").take(34), "${o.optInt("count")}", Muted)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun Section(title: String, body: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title.uppercase(), color = Muted, fontSize = 10.sp, letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace)
        Card(colors = CardDefaults.cardColors(containerColor = Surface1),
            shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp),
                content = body)
        }
    }
}

@Composable
private fun KeyRow(k: String, v: String, tint: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, color = Ink, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        Text(v, color = tint, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 8.dp))
    }
}

@Composable
private fun MachineDetail(m: MachineRow) {
    if (!m.online) { Text("offline", color = Bad, fontSize = 13.sp); return }
    KeyRow("cpu", "${m.loadPct.toInt()}% of ${m.cores} cores", Ink)
    KeyRow("ram", "${m.memUsedMb / 1024}/${m.memTotalMb / 1024} GB", Ink)
    KeyRow("disk", "${m.diskUsedGb}/${m.diskTotalGb} GB (${m.diskPct.toInt()}%)",
        if (m.diskPct >= 85) Bad else Ink)
    m.gpu?.let {
        KeyRow("gpu", it.name, Teal)
        KeyRow("vram", "${it.memUsedMb / 1024}/${it.memTotalMb / 1024} GB · ${it.utilPct}% · ${it.tempC}°", Ink)
    }
    m.watts?.let { KeyRow("power", "$it W" + if (m.onBattery) "  ON BATTERY" else "", if (m.onBattery) Bad else Ink) }
    if (m.kwhDay != null || m.kwhMonth != null) {
        val parts = buildList {
            m.kwhDay?.let { add("$it kWh today") }
            m.kwhWeek?.let { add("$it/wk") }
            m.kwhMonth?.let { add("$it/mo") }
        }
        KeyRow("energy", parts.joinToString(" · "), Muted)
    }
    m.tempC?.let { KeyRow("temp", "$it °C", if (it >= 75) Bad else Muted) }
    KeyRow("uptime", age(m.uptimeS), Muted)
    m.updates?.let {
        KeyRow("updates", if (it == 0) "up to date" else "$it pending",
            if (it == 0) Good else Amber)
    }
}
