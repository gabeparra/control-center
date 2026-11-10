package app.controlcenter

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Runtime-configurable endpoints: nothing hardcoded beyond first-run defaults. */
object AppCfg {
    // Wake-on-LAN relay (a small box on the LAN that can send magic packets
    // even when the target machine, and thus its own bridge, is fully off).
    // Blank until set in Settings.
    var relayUrl: String = ""
    // Name of the one LAN machine your wake relay can actually reach with a
    // magic packet (must match the "name" your bridge reports for it).
    var wakeableHost: String = ""
    // Name of the machine the bridge itself runs on (matches the bridge's
    // machine name). Its tmux sessions show up in the Machines tab. Blank means
    // the first machine the bridge lists.
    var selfHost: String = ""
    var filesUrl: String = ""    // filebrowser (Files pane); blank hides the pane
    // Software power estimate: watts the box can't self-report (CPU when RAPL is
    // root-only, plus board/drives/fans/PSU loss). Calibrate against a smart plug.
    var baselineW: Int = 60
    var kwhRate: Double = 0.14   // $/kWh for the cost figure

    /** Load saved config into the singletons. MainActivity does this on start,
     *  but widgets and Quick Settings tiles run in a cold process where onCreate
     *  never ran: without this they'd fall back to compile-time defaults and a
     *  customised bridge/relay URL would be silently ignored. */
    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("controlcenter", Context.MODE_PRIVATE)
        Api.baseUrl = p.getString("baseUrl", Api.baseUrl)!!
        relayUrl = p.getString("relayUrl", relayUrl)!!
        wakeableHost = p.getString("wakeableHost", wakeableHost)!!
        selfHost = p.getString("selfHost", selfHost)!!
        filesUrl = p.getString("filesUrl", filesUrl)!!
        baselineW = p.getInt("baselineW", baselineW)
        kwhRate = p.getFloat("kwhRate", kwhRate.toFloat()).toString().toDouble()   // via string so 0.14f stays 0.14
    }
}

@Composable
fun SettingsScreen(snackbar: SnackbarHostState, onSaveUrl: (String) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("controlcenter", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var bridge by remember { mutableStateOf(Api.baseUrl) }
    var files by remember { mutableStateOf(AppCfg.filesUrl) }

    var autoUpdate by remember { mutableStateOf(prefs.getBoolean("autoUpdate", false)) }
    var relay by remember { mutableStateOf(AppCfg.relayUrl) }
    var wakeableHost by remember { mutableStateOf(AppCfg.wakeableHost) }
    var selfHost by remember { mutableStateOf(AppCfg.selfHost) }
    var baseline by remember { mutableStateOf(AppCfg.baselineW.toString()) }
    var rate by remember { mutableStateOf(AppCfg.kwhRate.toString()) }

    Column(
        Modifier.fillMaxSize().imePadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (Caps.demo) Text("Connected to a demo bridge: everything you see is sample data.",
            color = Muted, fontSize = 12.sp)
        if (Caps.has("updater")) {
            Text("UPDATES", color = Amber, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(checked = autoUpdate, onCheckedChange = {
                    autoUpdate = it
                    prefs.edit().putBoolean("autoUpdate", it).apply()
                }, colors = SwitchDefaults.colors(checkedTrackColor = Amber))
                Spacer(Modifier.width(8.dp))
                Text("check for and install app updates from the bridge automatically",
                    color = Muted, fontSize = 13.sp)
            }
        }

        Text("ENDPOINTS", color = Amber, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        OutlinedTextField(bridge, { bridge = it }, label = { Text("bridge URL") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("The bridge has no login of its own, so HTTPS or a private network " +
            "(for example a tailnet) is recommended. Plain http:// is allowed so a " +
            "LAN address or an emulator (http://10.0.2.2:8790) works.",
            color = Muted, fontSize = 11.sp)
        Text(
            when {
                !Api.configured -> "No bridge set yet."
                Caps.legacy -> "This bridge does not list its features, so everything is shown."
                else -> "Bridge offers ${Caps.info?.features?.size ?: 0} features" +
                    if (Caps.demo) " (demo)." else "."
            },
            color = Muted, fontSize = 11.sp)
        OutlinedTextField(files, { files = it },
            label = { Text("files URL (filebrowser, blank hides the Files pane)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            Api.baseUrl = Api.normalize(bridge)
            bridge = Api.baseUrl
            AppCfg.filesUrl = files.trim()
            onSaveUrl(Api.baseUrl)
            prefs.edit().putString("filesUrl", AppCfg.filesUrl).apply()
            scope.launch {
                Caps.refresh()   // new address or new URLs can change what is shown
                snackbar.showSnackbar("endpoints saved")
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = Amber)) { Text("Save endpoints") }

        Spacer(Modifier.height(6.dp))
        Text("POWER & WAKE", color = Amber, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text("Wake relay = a small always-on device on the LAN that can send a magic packet to wake a machine that is off (a bridge running on that same machine cannot, it is off too). Baseline = watts the machine can't self-report; calibrate it against a smart plug reading.",
            color = Muted, fontSize = 11.sp)
        OutlinedTextField(relay, { relay = it },
            label = { Text("wake relay URL (blank = none)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(wakeableHost, { wakeableHost = it },
            label = { Text("wakeable machine name (matches bridge's machine name)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(selfHost, { selfHost = it },
            label = { Text("bridge host machine name (shows its tmux sessions)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(baseline, { baseline = it.filter { c -> c.isDigit() } },
                label = { Text("baseline W") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(rate, { rate = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("\$/kWh") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Button(onClick = {
            AppCfg.relayUrl = relay.trim().trimEnd('/')
            AppCfg.wakeableHost = wakeableHost.trim()
            AppCfg.selfHost = selfHost.trim()
            AppCfg.baselineW = baseline.toIntOrNull() ?: 60
            AppCfg.kwhRate = rate.toDoubleOrNull() ?: 0.14
            prefs.edit().putString("relayUrl", AppCfg.relayUrl)
                .putString("wakeableHost", AppCfg.wakeableHost)
                .putString("selfHost", AppCfg.selfHost)
                .putInt("baselineW", AppCfg.baselineW)
                .putFloat("kwhRate", AppCfg.kwhRate.toFloat()).apply()
            scope.launch { snackbar.showSnackbar("power & wake saved") }
        }, colors = ButtonDefaults.buttonColors(containerColor = Amber)) { Text("Save power & wake") }

        Spacer(Modifier.height(24.dp))
    }
}
