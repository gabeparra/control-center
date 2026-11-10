package app.controlcenter

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CandlestickChart
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Cottage
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ---------- palette (black, red and silver accents) ----------
val Bg = Color(0xFF0A0A0B)      // near-black ground
val Surface1 = Color(0xFF151518) // charcoal cards
val Amber = Color(0xFFC22A33)   // red, primary buttons and headers (name kept from an older palette)
val Teal = Color(0xFFC0C5CD)    // silver, icon accents (name kept from an older palette)
val Ink = Color(0xFFE8EAED)     // silver-white text
val Muted = Color(0xFF8B8F96)   // steel grey
val Good = Color(0xFFC9CDD3)    // silver dot = service alive
val Bad = Color(0xFFE5484D)     // bright red = down

// ---------- api ----------
object Api {
    // Points at your bridge companion server. Set in Settings; falls back to
    // BuildConfig.DEFAULT_BRIDGE_URL (see config.properties) until then. Blank = not set up yet.
    var baseUrl = BuildConfig.DEFAULT_BRIDGE_URL

    /** True once the user (or the build config) has given a bridge address. */
    val configured: Boolean get() = baseUrl.isNotBlank()

    /** Tidy what a person typed: trim, drop trailing slashes, and assume
     *  plain http when they left the scheme off ("192.168.1.5:8790"). */
    fun normalize(raw: String): String {
        val t = raw.trim().trimEnd('/')
        return if (t.isEmpty() || t.contains("://")) t else "http://$t"
    }

    // Ordinary calls get a short read timeout so a wrong port that accepts the
    // connection but never answers fails in seconds, not minutes. Calls that
    // really run long on the bridge (quick actions, backups, package updates,
    // LAN scans) and file downloads use the slow client.
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val slowClient = client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
    // The capability handshake runs before anything else is shown, so it gives up fast.
    private val quickClient = client.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()

    /** Parse a response as JSON, surfacing backend error detail instead of the
     *  JSONException you get from blindly parsing a FastAPI error body. */
    private fun parse(resp: okhttp3.Response): JSONObject {
        val text = resp.body?.string() ?: ""
        if (!resp.isSuccessful) {
            val detail = try { JSONObject(text).optString("detail", "") } catch (_: Exception) { "" }
            val extra = when {
                detail.isNotBlank() -> ": $detail"
                text.isNotBlank() -> ": ${text.take(200)}"
                else -> ""
            }
            throw java.io.IOException("HTTP ${resp.code}$extra")
        }
        return JSONObject(text)
    }

    suspend fun getAbs(url: String, slow: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        (if (slow) slowClient else client)
            .newCall(Request.Builder().url(url).build()).execute().use { parse(it) }
    }

    suspend fun postAbs(url: String, body: JSONObject, slow: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .post(body.toString().toRequestBody(json)).build()
        (if (slow) slowClient else client).newCall(req).execute().use { parse(it) }
    }

    /** GET with the short handshake timeouts. */
    suspend fun getQuick(path: String): JSONObject = withContext(Dispatchers.IO) {
        quickClient.newCall(Request.Builder().url(baseUrl + path).build()).execute().use { parse(it) }
    }

    /** Streams url to dest, reporting fraction 0f..1f (or null total → indeterminate -1f). */
    // withContext(IO) is mandatory: .execute() blocks, and callers from a
    // Main-dispatcher scope would otherwise throw NetworkOnMainThread.
    suspend fun download(url: String, dest: java.io.File, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        slowClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            // Without this, a 404/502 streams the error page into dest: which
            // for the updater means "installing" an HTML file as an APK.
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code} downloading $url")
            val body = resp.body!!
            val total = body.contentLength()
            var read = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        onProgress(if (total > 0) read.toFloat() / total else -1f)
                    }
                }
            }
        }
    }

    suspend fun delete(path: String): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(baseUrl + path).delete().build()).execute().use { parse(it) }
    }

    suspend fun deleteAbs(url: String): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).delete().build()).execute().use { parse(it) }
    }

    suspend fun putAbs(url: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .put(body.toString().toRequestBody(json)).build()
        client.newCall(req).execute().use { parse(it) }
    }

    suspend fun get(path: String, slow: Boolean = false): JSONObject = getAbs(baseUrl + path, slow)

    suspend fun post(path: String, body: JSONObject, slow: Boolean = false): JSONObject =
        postAbs(baseUrl + path, body, slow)
}

data class Svc(val name: String, val active: Boolean, val state: String)
data class Machine(val name: String, val online: Boolean, val os: String,
                   val ip: String, val self: Boolean, val lastSeen: String?)
data class QuickAction(val key: String, val label: String, val desc: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("controlcenter", Context.MODE_PRIVATE)
        Api.baseUrl = prefs.getString("baseUrl", Api.baseUrl)!!
        AppCfg.relayUrl = prefs.getString("relayUrl", "")!!
        AppCfg.wakeableHost = prefs.getString("wakeableHost", "")!!
        AppCfg.selfHost = prefs.getString("selfHost", "")!!
        AppCfg.filesUrl = prefs.getString("filesUrl", "")!!
        AppCfg.baselineW = prefs.getInt("baselineW", 60)
        AppCfg.kwhRate = prefs.getFloat("kwhRate", 0.14f).toDouble()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        AutoUpdate.schedule(this)
        handleAlertIntent(intent)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Amber, secondary = Teal, background = Bg,
                    surface = Surface1, onPrimary = Ink, onSurface = Ink,
                    onBackground = Ink, error = Bad,
                )
            ) { Root(onSaveUrl = { prefs.edit().putString("baseUrl", it).apply() }) }
        }
    }

    /** Check for updates every time the app comes to the foreground.
     *
     *  onCreate alone was not enough: it only fires on a cold start, so
     *  returning to a warm process skipped the check entirely. Combined with a
     *  publish poke that nothing drained until the next periodic tick, a fresh
     *  build could sit unnoticed for an hour: which is exactly what happened.
     *  Opening the app is the moment you expect it to be current, so that is
     *  when it now looks. */
    override fun onStart() {
        super.onStart()
        AutoUpdate.runNow(this)
    }

    /** A notification tap launches us with the alert's key; route it to the
     *  Audit tab. Handles both cold start (onCreate) and warm reuse (onNewIntent,
     *  single-top). */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAlertIntent(intent)
    }

    private fun handleAlertIntent(intent: android.content.Intent?) {
        intent?.getStringExtra("alert_key")?.takeIf { it.isNotBlank() }
            ?.let { DeepLink.open(it) }
    }
}

/** App-lifetime holder for a pending alert deep-link: set by a notification
 *  tap or by tapping an alert card in Overview. A plain object so it survives
 *  the composable teardown that happens on every tab switch. `seq` bumps on
 *  every open() so re-opening the same key still triggers navigation. */
object DeepLink {
    var alertKey by mutableStateOf<String?>(null)
        private set
    var seq by mutableStateOf(0)
        private set
    fun open(key: String) { alertKey = key; seq++ }
}

/** Unacked audit findings, held app-wide so the Audit tab can wear a badge
 *  without you opening it to find out. Refreshed by a slow poll in Root and
 *  nudged directly by whichever screen acks or reloads alerts. */
object AlertBadge {
    var unacked by mutableStateOf(0)
    suspend fun refresh() {
        if (!Api.configured || !Caps.has("alerts")) { unacked = 0; return }
        runCatching { unacked = Api.get("/alerts/active").optInt("unacked") }
    }
}

// Tab ids. The numbers are stored in preferences ("last_tab"), so they stay put.
const val TAB_HOME = 20
const val TAB_OVERVIEW = 21
const val TAB_MACHINES = 22
const val TAB_MEDIA = 23
const val TAB_SETTINGS = 26
const val TAB_AUDIT = 27
const val TAB_EXTENSIONS = 28
private val ALL_TABS = setOf(TAB_HOME, TAB_OVERVIEW, TAB_MACHINES, TAB_MEDIA, TAB_SETTINGS, TAB_AUDIT, TAB_EXTENSIONS)

@Composable
fun Root(onSaveUrl: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Where you left off. rememberSaveable survives a rotation but not the
    // process being killed, and being killed in the background is exactly when
    // coming back to the wrong tab is most annoying.
    val prefs = remember { ctx.getSharedPreferences("controlcenter", Context.MODE_PRIVATE) }
    // The stored value is checked against the real tabs: a missing or stale
    // number (a fresh install, an older build) must land on Overview, never on
    // a tab that does not exist, which would draw an empty screen.
    var tab by remember {
        mutableStateOf(prefs.getInt("last_tab", TAB_OVERVIEW).takeIf { it in ALL_TABS } ?: TAB_OVERVIEW)
    }
    LaunchedEffect(tab) { prefs.edit().putInt("last_tab", tab).apply() }
    // A notification tap or an Overview card tap calls DeepLink.open(); route to
    // Audit. Keyed on seq, not the payload, so re-opening the same item
    // still navigates. Ignored when this bridge has no Audit tab.
    LaunchedEffect(DeepLink.seq) {
        if (DeepLink.alertKey != null && Api.configured && Caps.hasAny("alerts", "events")) tab = TAB_AUDIT
    }
    // Ask the bridge what it offers before anything starts polling.
    LaunchedEffect(Unit) { Caps.refresh() }
    // Keep the Audit badge honest while the app is open. One tiny GET a minute.
    LaunchedEffect(Caps.ready) {
        while (Caps.ready) {
            AlertBadge.refresh()
            kotlinx.coroutines.delay(60_000)
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<Updater.Info?>(null) }
    var installing by remember { mutableStateOf(false) }
    // Filebrowser WebView: created on first visit, then kept alive across
    // tab switches so an in-progress upload survives.
    var files by remember { mutableStateOf<FilesState?>(null) }

    LaunchedEffect(Caps.ready) {
        if (Caps.ready && Api.configured && Caps.has("updater")) {
            runCatching { update = Updater.check(ctx) }
            runCatching { VersionReport.send(ctx) }
        }
    }

    update?.let { info ->
        var progress by remember { mutableStateOf(0f) }
        AlertDialog(
            onDismissRequest = { if (!installing) update = null },
            containerColor = Surface1,
            title = { Text("Update available", color = Ink) },
            text = {
                Column {
                    Text("Control Center v${info.name} is on the bridge.", color = Muted)
                    if (installing) {
                        Spacer(Modifier.height(14.dp))
                        if (progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(), color = Amber,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${(progress * 100).toInt()}%",
                                color = Ink, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Amber)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !installing,
                    onClick = {
                        installing = true; progress = 0f
                        scope.launch {
                            runCatching { Updater.downloadAndInstall(ctx, info, selfUpdate = false) { p -> progress = p } }
                                .onFailure { snackbar.showSnackbar("update failed: ${it.message}") }
                            installing = false; update = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Amber),
                ) { Text(if (installing) "downloading… ${if (progress >= 0f) "${(progress * 100).toInt()}%" else ""}" else "Install") }
            },
            dismissButton = {
                if (!installing) TextButton(onClick = { update = null }) { Text("Later", color = Muted) }
            },
        )
    }
    // Which tabs this bridge and configuration can actually fill. Reading
    // Caps.epoch makes a settings save or a fresh handshake re-evaluate this.
    val epoch = Caps.epoch
    val configured = Api.configured
    val visibleTabs = buildSet {
        add(TAB_OVERVIEW)   // doubles as the setup card until a bridge is set
        if (configured && Caps.hasAny("alerts", "events")) add(TAB_AUDIT)
        if (configured && Caps.hasAny(*Caps.machineFeatures)) add(TAB_MACHINES)
        if (configured && Caps.has("home")) add(TAB_HOME)
        if (mediaPanes().isNotEmpty()) add(TAB_MEDIA)
        // Unlike the other features, "unknown" does not mean "present" here:
        // /extensions is newer than the handshake, so a bridge that gave no
        // handshake cannot have it.
        if (configured && !Caps.legacy && Caps.has("extensions")) add(TAB_EXTENSIONS)
        add(TAB_SETTINGS)
    }
    val current = if (tab in visibleTabs) tab else TAB_OVERVIEW
    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // Up to seven destinations, shown only when they have something to show.
            // They share the width equally, so all of them stay on screen (a
            // scrolling strip pushed Settings off the edge once Extras joined).
            // navigationBarsPadding lifts the tappable row above the phone's
            // gesture bar while the Surface background still fills to the edge.
            Surface(color = Surface1) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                  Row(
                    Modifier.weight(1f).padding(vertical = 6.dp),
                  ) {
                    listOf(
                        Triple(TAB_OVERVIEW, Icons.Filled.SpaceDashboard, "Overview"),
                        Triple(TAB_AUDIT, Icons.Filled.Notifications, "Audit"),
                        Triple(TAB_MACHINES, Icons.Filled.Storage, "Machines"),
                        Triple(TAB_HOME, Icons.Filled.Cottage, "Home"),
                        Triple(TAB_MEDIA, Icons.Filled.VideoLibrary, "Media"),
                        Triple(TAB_EXTENSIONS, Icons.Filled.Extension, "Extras"),
                        // Settings is a destination like any other: last in the
                        // strip, but nothing to dismiss. It was an overlay with
                        // its own flag and a Done button, which is a pop-up
                        // wearing a tab's clothes.
                        Triple(TAB_SETTINGS, Icons.Filled.Settings, "Settings"),
                    ).filter { it.first in visibleTabs }.forEach { (idx, icon, label) ->
                        val sel = current == idx
                        Column(
                            Modifier.weight(1f).clickable { tab = idx }
                                .padding(horizontal = 2.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // Audit wears its unacked count so findings announce
                            // themselves from any tab instead of waiting to be found.
                            if (idx == TAB_AUDIT && AlertBadge.unacked > 0) {
                                BadgedBox(badge = {
                                    Badge(containerColor = Bad) {
                                        Text("${AlertBadge.unacked}", fontSize = 9.sp)
                                    }
                                }) { Icon(icon, label, tint = if (sel) Amber else Muted) }
                            } else {
                                Icon(icon, label, tint = if (sel) Amber else Muted)
                            }
                            Text(label, color = if (sel) Amber else Muted, fontSize = 10.sp, maxLines = 1)
                        }
                    }
                  }
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(Bg)) {
            // Small and out of the way: demo data should never pass for real readings.
            if (Caps.demo) Text("Demo data", color = Muted, fontSize = 10.sp,
                letterSpacing = 1.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().background(Surface1)
                    .padding(horizontal = 14.dp, vertical = 2.dp))
            Box(Modifier.weight(1f).fillMaxWidth().background(Bg)) {
                if (!Caps.ready) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(26.dp),
                        color = Amber, strokeWidth = 2.dp)
                } else if (current == TAB_SETTINGS) {
                    Column(Modifier.fillMaxSize().background(Bg)) {
                        Text("SETTINGS", color = Amber, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                        Box(Modifier.weight(1f)) { SettingsScreen(snackbar, onSaveUrl) }
                    }
                } else {
                    // Keyed on the handshake so every screen reloads against a
                    // new answer instead of showing what the old one allowed.
                    key(epoch) {
                        when (current) {
                            TAB_HOME -> HomeScreen(snackbar)
                            TAB_OVERVIEW ->
                                if (!configured) SetupScreen(snackbar, onSaveUrl)
                                else OverviewScreen(snackbar, onSaveUrl, onUpdateFound = { update = it })
                            TAB_MACHINES -> MachinesScreen(snackbar)
                            TAB_MEDIA -> MediaScreen(snackbar, files ?: FilesState(ctx).also { files = it })
                            TAB_AUDIT -> AlertsScreen(snackbar, focusKey = DeepLink.alertKey)
                            TAB_EXTENSIONS -> ExtensionsScreen(snackbar)
                        }
                    }
                }
            }
        }
    }
}

// ---------- Stack ----------
@Composable
fun StackScreen(snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var gpu by remember { mutableStateOf<JSONObject?>(null) }
    var vitals by remember { mutableStateOf<JSONObject?>(null) }
    var draw by remember { mutableStateOf<JSONObject?>(null) }
    var powerHistory by remember { mutableStateOf<JSONObject?>(null) }
    var updates by remember { mutableStateOf<JSONObject?>(null) }
    var services by remember { mutableStateOf(listOf<Svc>()) }
    var machines by remember { mutableStateOf(listOf<Machine>()) }
    var actions by remember { mutableStateOf(listOf<QuickAction>()) }
    var loading by remember { mutableStateOf(false) }
    // Which service's journal to show (null = closed) and the fetched lines.
    var logService by remember { mutableStateOf<String?>(null) }
    // Output of the last-run quick action, shown in a dismissable dialog.
    var actionOut by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Every block loads on its own: a bridge with no GPU, or no Tailscale, still
    // gets the rest of the pane. Only when everything we asked for failed do we
    // say the bridge is unreachable.
    fun refresh() = scope.launch {
        loading = true
        var asked = 0
        var failed = 0
        var lastError: String? = null
        suspend fun <T> load(feature: String, block: suspend () -> T): T? {
            if (!Caps.has(feature)) return null
            asked++
            return try { block() } catch (e: Exception) {
                failed++; lastError = e.message; null
            }
        }
        load("gpu") { Api.get("/gpu") }?.let { gpu = it }
        load("vitals") { Api.get("/vitals") }?.let { vitals = it }
        load("power_draw") { Api.get("/power/draw") }?.let { draw = it }
        load("power_draw") { Api.get("/power/history") }?.let { powerHistory = it }
        load("updates") { Api.get("/updates") }?.let { updates = it }   // cached bridge-side
        load("services") {
            val rows = Api.get("/stack").getJSONArray("services")
            (0 until rows.length()).map {
                val o = rows.getJSONObject(it)
                Svc(o.optString("name"), o.optBoolean("active"), o.optString("state"))
            }
        }?.let { services = it }
        load("actions") {
            val aarr = Api.get("/actions").getJSONArray("actions")
            (0 until aarr.length()).map {
                val o = aarr.getJSONObject(it)
                QuickAction(o.optString("key"), o.optString("label"), o.optString("desc"))
            }
        }?.let { actions = it }
        load("tailnet") {
            val marr = Api.get("/tailnet").getJSONArray("devices")
            (0 until marr.length()).map {
                val o = marr.getJSONObject(it)
                Machine(o.optString("name"), o.optBoolean("online"), o.optString("os"),
                        o.optString("ip"), o.optBoolean("self"),
                        if (o.isNull("last_seen")) null else o.optString("last_seen"))
            }
        }?.let { machines = it }
        if (asked > 0 && failed == asked) snackbar.showSnackbar("bridge unreachable: $lastError")
        loading = false
    }

    LaunchedEffect(Unit) { refresh() }

    logService?.let { name ->
        LogDialog(name, "/logs/$name", "/service/$name/restart", snackbar) { logService = null }
    }
    actionOut?.let { (label, out) ->
        AlertDialog(
            onDismissRequest = { actionOut = null },
            containerColor = Surface1,
            title = { Text(label, color = Ink, fontSize = 15.sp) },
            text = {
                SelectionContainer {
                    Text(out, color = Ink, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = { TextButton(onClick = { actionOut = null }) { Text("Close", color = Amber) } },
        )
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SERVER STACK", color = Amber, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { refresh() }) {
                    Icon(Icons.Filled.Refresh, "refresh", tint = Muted)
                }
            }
        }
        gpu?.let { g ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Surface1),
                    shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(g.optString("name", "GPU"), color = Ink, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        val used = g.optInt("vram_used_mb"); val total = g.optInt("vram_total_mb")
                        LinearProgressIndicator(
                            progress = { if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f },
                            modifier = Modifier.fillMaxWidth(), color = Teal,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "$used / $total MB VRAM  ·  ${g.optInt("util_pct")}% util  ·  ${g.optInt("temp_c")}°C",
                            color = Muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
        vitals?.let { v ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Surface1),
                    shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("HOST  ·  ${v.optInt("cores")} cores", color = Ink,
                            fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        VitalBar("CPU", v.optInt("load_pct"),
                            "load ${v.optDouble("load1", 0.0)}  ·  ${v.optInt("load_pct")}%")
                        Spacer(Modifier.height(8.dp))
                        VitalBar("RAM", v.optInt("mem_pct"),
                            "${v.optInt("mem_used_mb")} / ${v.optInt("mem_total_mb")} MB")
                        Spacer(Modifier.height(8.dp))
                        VitalBar("DISK", v.optInt("disk_pct"),
                            "${v.optInt("disk_used_gb")} / ${v.optInt("disk_total_gb")} GB  ·  ${v.optInt("disk_free_gb")} free")
                    }
                }
            }
        }
        draw?.let { d ->
            item {
                val gpuW = if (d.isNull("gpu_w")) 0.0 else d.optDouble("gpu_w", 0.0)
                val raplOk = d.optBoolean("rapl_ok")
                val cpuW = if (raplOk) d.optDouble("cpu_w", 0.0) else 0.0
                val baseline = AppCfg.baselineW
                // A UPS measures what actually leaves the socket. The estimate
                // below can only see the GPU and sometimes the CPU package, and
                // models the rest as a constant, so it can sit well under the
                // real figure. Prefer the meter when present.
                val ups = d.optJSONObject("ups")
                val measured = ups != null
                val total = if (measured) ups!!.optDouble("watts", 0.0) else gpuW + cpuW + baseline
                // No monthly projection: extrapolating from the hours the box
                // happened to be on would invent a number and present it as a
                // measurement. Actual energy drawn is reported instead.
                Card(colors = CardDefaults.cardColors(containerColor = Surface1),
                    shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("POWER DRAW", color = Amber, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                            Spacer(Modifier.weight(1f))
                            Text("${"%.0f".format(total)} W", color = Ink,
                                fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("est. ${"%.0f".format(total)} W", color = Ink,
                            fontFamily = FontFamily.Monospace, fontSize = 20.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(buildString {
                            if (measured) {
                                append("${ups!!.optString("model")}  ·  ")
                                append("${"%.0f".format(ups.optDouble("load_pct", 0.0))}% of ${ups.optInt("rated_w")}W")
                                append("  ·  GPU ${"%.1f".format(gpuW)}W")
                            } else {
                                append("GPU ${"%.1f".format(gpuW)}W")
                                append("  ·  CPU ")
                                append(if (raplOk) "${"%.1f".format(cpuW)}W" else "in baseline")
                                append("  ·  baseline ${baseline}W")
                            }
                            append("  ·  @\$${AppCfg.kwhRate}/kWh")
                        }, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        // Averages beat a spot reading for cost: draw can swing
                        // widely within seconds, so a single sample says almost
                        // nothing about the bill.
                        powerHistory?.let { h ->
                            val week = h.optJSONObject("week")
                            val day = h.optJSONObject("day")
                            if (week != null || day != null) {
                                Spacer(Modifier.height(8.dp))
                                val w = week ?: day!!
                                val kwh = w.optDouble("kwh", 0.0)
                                Text(
                                    "${"%.2f".format(kwh)} kWh drawn over ${w.optInt("hours")}h  ·  " +
                                        "$${"%.2f".format(kwh * AppCfg.kwhRate)}",
                                    color = Ink, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    "avg ${w.optInt("avg_w")}W, range ${w.optInt("min_w")}-${w.optInt("max_w")}W" +
                                        (h.optInt("uptime_s").takeIf { it > 0 }
                                            ?.let { "  ·  up ${it / 3600}h" } ?: ""),
                                    color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                        Text(
                            if (measured)
                                "measured at the wall by the UPS · everything on it, +/-${ups!!.optInt("resolution_w")}W" +
                                    (if (ups.optBoolean("on_battery")) "  ·  ON BATTERY" else "")
                            else "software estimate, CPU draw is not measured",
                            color = if (measured && ups!!.optBoolean("on_battery")) Bad else Muted,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
        if (actions.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Surface1),
                    shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("QUICK ACTIONS", color = Amber, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Spacer(Modifier.height(10.dp))
                        // two-per-row grid; avoids the experimental FlowRow opt-in
                        actions.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { a ->
                                    var busy by remember { mutableStateOf(false) }
                                    OutlinedButton(
                                        modifier = Modifier.weight(1f),
                                        enabled = !busy,
                                        onClick = {
                                            busy = true
                                            scope.launch {
                                                try {
                                                    val r = Api.post("/action/${a.key}", JSONObject(), slow = true)
                                                    actionOut = a.label to r.optString("output", "(no output)")
                                                } catch (e: Exception) {
                                                    snackbar.showSnackbar("${a.label} failed: ${e.message}")
                                                }
                                                busy = false
                                            }
                                        },
                                    ) {
                                        if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Amber)
                                        else Text(a.label, color = Ink, fontSize = 12.sp, maxLines = 1)
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))  // keep last row aligned
                            }
                        }
                    }
                }
            }
        }
        if (Caps.has("usage")) item {
            Spacer(Modifier.height(4.dp))
            UsageSection(snackbar)
        }
        items(services) { s ->
            var busy by remember { mutableStateOf(false) }
            Card(colors = CardDefaults.cardColors(containerColor = Surface1),
                shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).background(
                        when { s.active -> Good; s.state == "activating" -> Amber; else -> Bad },
                        CircleShape))
                    Spacer(Modifier.width(12.dp))
                    // tap name/state to read the service's journal (why it's down)
                    Column(Modifier.weight(1f).clickable { logService = s.name }) {
                        Text(s.name, color = Ink, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        Text("${s.state}  ·  logs", color = Muted, fontSize = 11.sp)
                    }
                    fun control(action: String) {
                        busy = true
                        scope.launch {
                            try {
                                Api.post("/service/${s.name}/$action", JSONObject())
                                snackbar.showSnackbar("${s.name} $action")
                                refresh()
                            } catch (e: Exception) {
                                snackbar.showSnackbar("$action failed: ${e.message}")
                            }
                            busy = false
                        }
                    }
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else if (s.active || s.state == "activating") {
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
        if (Caps.has("containers")) item {
            Spacer(Modifier.height(8.dp))
            ContainersSection(snackbar)
        }
        if (Caps.has("backup")) item {
            Spacer(Modifier.height(4.dp))
            BackupCard(snackbar)
        }
        updates?.let { u ->
            item {
                val n = u.optInt("count")
                Card(colors = CardDefaults.cardColors(containerColor = Surface1),
                    shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("SYSTEM UPDATES", color = Amber, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                            Spacer(Modifier.weight(1f))
                            Text(if (n == 0) "up to date" else "$n pending",
                                color = if (n == 0) Good else Amber,
                                fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        }
                        if (n > 0) {
                            Spacer(Modifier.height(6.dp))
                            // Read-only on purpose: applying updates needs admin
                            // rights and can want a reboot, so it stays a job for
                            // the machine itself.
                            Text(
                                (0 until minOf(u.optJSONArray("packages")?.length() ?: 0, 4))
                                    .joinToString("\n") {
                                        u.optJSONArray("packages")?.optJSONObject(it)?.optString("name") ?: ""
                                    } + if (n > 4) "\n… and ${n - 4} more" else "",
                                color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text("apply these on the machine itself",
                                color = Muted, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        if (machines.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("NETWORK DEVICES", color = Amber, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
            items(machines) { m ->
                Card(colors = CardDefaults.cardColors(containerColor = Surface1),
                    shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(
                            if (m.online) Good else Bad, CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name + if (m.self) "  · this box" else "",
                                color = Ink, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                            Text(
                                listOfNotNull(
                                    m.os.ifEmpty { null }, m.ip.ifEmpty { null },
                                    m.lastSeen?.let { "seen $it" },
                                ).joinToString(" · "),
                                color = Muted, fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
        // The relay card inside works without the bridge's own wake support.
        val relaySet = AppCfg.relayUrl.isNotBlank() && AppCfg.wakeableHost.isNotBlank()
        if (Caps.has("wake") || relaySet) item {
            Spacer(Modifier.height(8.dp))
            PowerSection(snackbar)
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Amber) }
    }
}

/** One labelled meter row for the host vitals card. Turns amber past 75%,
 *  red past 90%: the disk bar going red is the "everything's about to break" tell. */
@Composable
private fun VitalBar(label: String, pct: Int, detail: String) {
    val color = when { pct >= 90 -> Bad; pct >= 75 -> Amber; else -> Teal }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(42.dp))
        Column(Modifier.weight(1f)) {
            LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier.fillMaxWidth(), color = color,
            )
            Spacer(Modifier.height(3.dp))
            Text(detail, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.width(10.dp))
        Text("$pct%", color = color, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

/** Log viewer for a service or a container: fetched fresh each open, tail
 *  pinned to the bottom (newest last), with a refresh + restart shortcut so you
 *  can read the crash and bounce it in one place. Paths are passed in so the
 *  same dialog serves journald (`/logs/x`) and docker (`/container/x/logs`). */
@Composable
internal fun LogDialog(
    title: String, logPath: String, restartPath: String,
    snackbar: SnackbarHostState, onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() = scope.launch {
        error = null; lines = null
        try {
            val sep = if (logPath.contains('?')) "&" else "?"
            val rows = Api.get("$logPath${sep}lines=80").getJSONArray("lines")
            lines = (0 until rows.length()).map { rows.getString(it) }
        } catch (e: Exception) { error = e.message ?: "log fetch failed" }
    }
    LaunchedEffect(logPath) { load() }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Surface1,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Ink, fontSize = 15.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "reload", tint = Muted) }
            }
        },
        text = {
            when {
                error != null -> Text(error!!, color = Bad, fontSize = 12.sp)
                lines == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Amber)
                    Spacer(Modifier.width(10.dp)); Text("reading journal…", color = Muted, fontSize = 12.sp)
                }
                lines!!.isEmpty() -> Text("(no journal output)", color = Muted, fontSize = 12.sp)
                else -> {
                    val scroll = rememberScrollState()
                    LaunchedEffect(lines) { scroll.scrollTo(scroll.maxValue) }  // pin to newest
                    SelectionContainer {
                        Text(lines!!.joinToString("\n"), color = Ink, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.heightIn(max = 420.dp).verticalScroll(scroll))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", color = Amber) } },
        dismissButton = {
            TextButton(onClick = {
                scope.launch {
                    try {
                        Api.post(restartPath, JSONObject())
                        snackbar.showSnackbar("$title restarted"); load()
                    } catch (e: Exception) { snackbar.showSnackbar("restart failed: ${e.message}") }
                }
            }) { Text("Restart", color = Muted) }
        },
    )
}

// ---------- Actions ----------
@Composable
fun ActionsScreen(
    snackbar: SnackbarHostState,
    onSaveUrl: (String) -> Unit,
    onUpdateFound: (Updater.Info) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var torch by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(Api.baseUrl) }

    fun act(label: String, block: suspend () -> Unit) = scope.launch {
        try { block(); snackbar.showSnackbar(label) }
        catch (e: Exception) { snackbar.showSnackbar("failed: ${e.message}") }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("QUICK ACTIONS", color = Amber, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 2.sp)

        if (Caps.has("commands")) ActionButton(Icons.Filled.Notifications, "Test notification (via bridge)") {
            act("queued, arrives with next poll") {
                Api.post("/command", JSONObject()
                    .put("action", "notify")
                    .put("args", JSONObject().put("title", "Control Center").put("text", "control center says hi")))
            }
        }
        ActionButton(Icons.Filled.FlashlightOn, if (torch) "Flashlight off" else "Flashlight on") {
            try {
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = cm.cameraIdList.first {
                    cm.getCameraCharacteristics(it)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                torch = !torch
                cm.setTorchMode(id, torch)
            } catch (e: Exception) {
                scope.launch { snackbar.showSnackbar("no torch: ${e.message}") }
            }
        }
        ActionButton(Icons.Filled.Vibration, "Vibrate") {
            ctx.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
        }

        Spacer(Modifier.weight(1f))
        Text("BRIDGE", color = Amber, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("base URL") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            Api.baseUrl = Api.normalize(url)
            url = Api.baseUrl
            onSaveUrl(Api.baseUrl)
            scope.launch { Caps.refresh(); snackbar.showSnackbar("saved") }
        }, colors = ButtonDefaults.buttonColors(containerColor = Amber)) { Text("Save") }
        // The version used to live only inside the "check for updates" button
        // label, which is the one place you do not look when the question is
        // "which build am I on".
        val (verCode, ver) = remember { Updater.currentVersion(ctx) }
        Spacer(Modifier.height(6.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = Surface1),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Control Center", color = Ink, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text("v$ver  ·  build $verCode", color = Teal, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace)
                    Text(Api.baseUrl, color = Muted, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, maxLines = 1)
                }
                if (Caps.has("updater")) TextButton(onClick = {
                    scope.launch {
                        val info = runCatching { Updater.check(ctx) }.getOrNull()
                        if (info == null) snackbar.showSnackbar("v$ver: up to date")
                        else onUpdateFound(info)
                    }
                }) { Text("Check", color = Amber, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Surface1),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Teal)
            Spacer(Modifier.width(14.dp))
            Text(label, color = Ink, fontSize = 15.sp)
        }
    }
}
