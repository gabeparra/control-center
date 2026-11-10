package app.controlcenter

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** One of your other sideloaded apps that Control Center can install/update.
 *  Add an entry here (and a matching `<package>` in AndroidManifest's
 *  `<queries>` block, required on Android 11+) to manage a new app. */
data class ManagedApp(
    val name: String,
    val packageName: String,
    val manifestUrl: String,
    /** Filename prefix in the bridge's /archive: defaults to the lowercased
     *  display name; set explicitly when the name has spaces. */
    val archivePrefix: String = "",
) {
    val archiveKey get() = archivePrefix.ifEmpty { name.lowercase() }
}

// No apps ship built in: add your own sideloaded apps here, or serve them
// from the bridge's /apps/managed endpoint (see ManagedAppsState.load below).
private val BUILT_IN = listOf<ManagedApp>()

/** The list the Apps tab actually renders.
 *
 * It lives on the bridge (apps.json) so adding an app is a file edit rather
 * than a rebuild and a publish. BUILT_IN stays as the fallback: a bridge that
 * is down should mean a stale list, not an empty screen. App-lifetime, not
 * composable-scoped, so switching tabs does not refetch. */
object ManagedAppsState {
    var apps by mutableStateOf(BUILT_IN)
        private set
    private var loaded = false

    suspend fun load() {
        if (loaded) return
        runCatching {
            val rows = Api.get("/apps/managed").getJSONArray("apps")
            val fetched = (0 until rows.length()).map {
                val o = rows.getJSONObject(it)
                ManagedApp(
                    name = o.getString("name"),
                    packageName = o.getString("packageName"),
                    manifestUrl = o.getString("manifestUrl"),
                    archivePrefix = o.optString("archivePrefix"),
                )
            }
            // An empty list from a half-written apps.json would silently wipe
            // the tab, so only adopt a non-empty one.
            if (fetched.isNotEmpty()) { apps = fetched; loaded = true }
        }
    }
}

private sealed class RemoteState {
    object Loading : RemoteState()
    data class Error(val message: String) : RemoteState()
    data class Loaded(val info: Updater.Info) : RemoteState()
}

/** Apps tab: one place to see and update every sideloaded app on the phone,
 *  including Control Center itself. Per-app downloads reuse Updater's
 *  downloader + package-installer intent; Control Center's own update still flows
 *  through the existing bridge-advertised dialog in Root via onUpdateFound. */
@Composable
fun AppsScreen(snackbar: SnackbarHostState, onUpdateFound: (Updater.Info) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { ManagedAppsState.load() }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("APPS", color = Amber, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text("sideloaded apps managed from this device", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    scope.launch {
                        snackbar.showSnackbar("checking all apps for updates…")
                        val done = AppInstalls.updateAll(ctx, snackbar)
                        if (done == 0) snackbar.showSnackbar("everything is up to date")
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Amber),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Update all apps") }
        }
        item { ControlCenterCard(ctx, onUpdateFound, snackbar) }
        items(ManagedAppsState.apps) { app -> ManagedAppCard(app, ctx, snackbar) }
    }
}

@Composable
private fun ControlCenterCard(
    ctx: Context,
    onUpdateFound: (Updater.Info) -> Unit,
    snackbar: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    val ver = remember { Updater.currentVersion(ctx).second }
    var checking by remember { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Control Center", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text("installed v$ver", color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(10.dp))
            Button(
                enabled = !checking,
                onClick = {
                    checking = true
                    scope.launch {
                        val info = runCatching { Updater.check(ctx) }.getOrNull()
                        checking = false
                        if (info == null) snackbar.showSnackbar("Control Center v$ver: up to date")
                        else onUpdateFound(info)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Amber),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (checking) "checking…" else "Check for updates") }
        }
    }
}

/** App-lifetime scope + state for downloads/installs, so switching tabs never
 *  cancels an in-flight download: the tab merely *displays* this state and
 *  picks it right back up when you return. */
object AppInstalls {
    val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    val installing = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    val progress = androidx.compose.runtime.mutableStateMapOf<String, Float>()
    val installed = androidx.compose.runtime.mutableStateMapOf<String, Pair<Long, String>?>()

    fun start(ctx: Context, app: ManagedApp, info: Updater.Info, snackbar: SnackbarHostState) {
        val pkg = app.packageName
        val appCtx = ctx.applicationContext
        installing[pkg] = true; progress[pkg] = 0f
        scope.launch {
            // Android forbids in-place downgrades: picking an older version
            // walks through uninstall (system dialog) → install automatically.
            val cur = Updater.installedVersion(appCtx, pkg)?.first
            if (cur != null && info.code < cur) {
                snackbar.showSnackbar("downgrade: confirm the uninstall, then v${info.name} installs itself")
                runCatching {
                    appCtx.startActivity(android.content.Intent(
                        android.content.Intent.ACTION_DELETE,
                        android.net.Uri.parse("package:$pkg"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                var waited = 0
                while (Updater.installedVersion(appCtx, pkg) != null && waited < 120_000) {
                    kotlinx.coroutines.delay(2000); waited += 2000
                }
                if (Updater.installedVersion(appCtx, pkg) != null) {
                    installing[pkg] = false
                    snackbar.showSnackbar("uninstall didn't complete: downgrade aborted")
                    return@launch
                }
                installed[pkg] = null
            }
            runCatching { Updater.downloadAndInstall(appCtx, info) { p -> progress[pkg] = p } }
                .onFailure { snackbar.showSnackbar("${app.name} install failed: ${it.message}") }
            installing[pkg] = false
            // Session installer returns before the user approves: poll the real
            // installed version so the card flips the moment Android finishes.
            val before = installed[pkg]?.first
            repeat(60) {
                kotlinx.coroutines.delay(2000)
                val now = Updater.installedVersion(appCtx, pkg)
                if (now?.first != before) {
                    installed[pkg] = now
                    runCatching { VersionReport.send(appCtx) }
                    snackbar.showSnackbar("${app.name} is now v${now?.second}")
                    return@launch
                }
            }
        }
    }

    /** Checks every managed app + Control Center itself and installs any that are behind.
     *  Runs entirely in-app (no dependence on the phone's poll script).
     *  Returns how many updates it kicked off. */
    suspend fun updateAll(ctx: Context, snackbar: SnackbarHostState): Int {
        val appCtx = ctx.applicationContext
        var count = 0
        ManagedAppsState.apps.forEach { app ->
            runCatching {
                val cur = Updater.installedVersion(appCtx, app.packageName)
                val info = Updater.fetchManifest(app.manifestUrl)
                if (cur == null || info.code > cur.first) {
                    count++
                    start(appCtx, app, info, snackbar)
                }
            }
        }
        runCatching {
            Updater.check(appCtx)?.let { info ->
                count++
                installing[appCtx.packageName] = true; progress[appCtx.packageName] = 0f
                scope.launch {
                    runCatching { Updater.downloadAndInstall(appCtx, info, selfUpdate = false) {
                        progress[appCtx.packageName] = it } }
                        .onFailure { snackbar.showSnackbar("Control Center update failed: ${it.message}") }
                    installing[appCtx.packageName] = false
                }
            }
        }
        return count
    }

    fun open(ctx: Context, app: ManagedApp, snackbar: SnackbarHostState) {
        val li = ctx.packageManager.getLaunchIntentForPackage(app.packageName)
        if (li != null) ctx.startActivity(li)
        else scope.launch { snackbar.showSnackbar("${app.name}: no launchable activity") }
    }

    fun uninstall(ctx: Context, app: ManagedApp, snackbar: SnackbarHostState) {
        val pkg = app.packageName
        val appCtx = ctx.applicationContext
        runCatching {
            ctx.startActivity(android.content.Intent(
                android.content.Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg")))
        }
        scope.launch {
            repeat(30) {
                kotlinx.coroutines.delay(2000)
                if (Updater.installedVersion(appCtx, pkg) == null) {
                    installed[pkg] = null
                    runCatching { VersionReport.send(appCtx) }
                    snackbar.showSnackbar("${app.name} uninstalled")
                    return@launch
                }
            }
        }
    }
}

@Composable
private fun ManagedAppCard(app: ManagedApp, ctx: Context, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var remote by remember(app.packageName) { mutableStateOf<RemoteState>(RemoteState.Loading) }
    val installing = AppInstalls.installing[app.packageName] == true
    val progress = AppInstalls.progress[app.packageName] ?: 0f
    val installed = AppInstalls.installed.getOrPut(app.packageName) {
        Updater.installedVersion(ctx, app.packageName)
    }

    var showVersions by remember(app.packageName) { mutableStateOf(false) }
    var versions by remember(app.packageName) { mutableStateOf(listOf<Updater.Info>()) }

    fun refresh() = scope.launch {
        AppInstalls.installed[app.packageName] = Updater.installedVersion(ctx, app.packageName)
        remote = RemoteState.Loading
        remote = try {
            RemoteState.Loaded(Updater.fetchManifest(app.manifestUrl))
        } catch (e: Exception) {
            RemoteState.Error(e.message ?: "fetch failed")
        }
    }

    fun loadVersions() = scope.launch {
        runCatching {
            val rows = Api.get("/archive/${app.archiveKey}").getJSONArray("versions")
            versions = (0 until rows.length()).map {
                val o = rows.getJSONObject(it)
                Updater.Info(o.getLong("versionCode"), o.getString("versionName"),
                    Api.baseUrl + o.getString("url"))
            }
        }.onFailure { snackbar.showSnackbar("archive unavailable: ${it.message}") }
    }

    LaunchedEffect(app.packageName) { refresh() }

    Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(app.name, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        installed?.let { "installed v${it.second}" } ?: "not installed",
                        color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    )
                }
                IconButton(onClick = { refresh() }) {
                    Icon(Icons.Filled.Refresh, "refresh", tint = Muted)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (installed != null) {
                    OutlinedButton(
                        onClick = { AppInstalls.open(ctx, app, snackbar) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) { Text("Open", color = Teal, fontSize = 13.sp) }
                    OutlinedButton(
                        onClick = { AppInstalls.uninstall(ctx, app, snackbar) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) { Text("Uninstall", color = Bad, fontSize = 13.sp) }
                }
                OutlinedButton(
                    onClick = { showVersions = !showVersions; if (showVersions) loadVersions() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) { Text("Versions", color = Muted, fontSize = 13.sp) }
            }
            if (showVersions) {
                Spacer(Modifier.height(6.dp))
                if (versions.isEmpty())
                    Text("no archived versions yet", color = Muted, fontSize = 12.sp)
                versions.forEach { v ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "v${v.name}  ·  code ${v.code}" +
                                if (installed?.first == v.code) "  ·  installed" else "",
                            color = if (installed?.first == v.code) Teal else Ink,
                            fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            enabled = installed?.first != v.code && !installing,
                            onClick = { AppInstalls.start(ctx, app, v, snackbar) },
                        ) { Text("install", color = Amber, fontSize = 12.sp) }
                    }
                }
            }
            when (val r = remote) {
                is RemoteState.Loading -> {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Amber)
                }
                is RemoteState.Error -> {
                    Spacer(Modifier.height(8.dp))
                    Text("check failed: ${r.message}", color = Bad, fontSize = 12.sp)
                }
                is RemoteState.Loaded -> {
                    val info = r.info
                    Spacer(Modifier.height(4.dp))
                    Text("latest v${info.name}", color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    if (info.notes.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(info.notes, color = Muted, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    if (installing) {
                        if (progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(), color = Amber,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${(progress * 100).toInt()}%",
                                color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Amber)
                        }
                    } else {
                        val upToDate = installed != null && info.code <= installed.first
                        Button(
                            enabled = !upToDate,
                            onClick = { AppInstalls.start(ctx, app, info, snackbar) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Amber, disabledContainerColor = Surface1),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                when {
                                    upToDate -> "Up to date"
                                    installed == null -> "Install"
                                    else -> "Update"
                                },
                                color = if (upToDate) Muted else Ink,
                            )
                        }
                    }
                }
            }
        }
    }
}
