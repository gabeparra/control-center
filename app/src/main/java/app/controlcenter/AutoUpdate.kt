package app.controlcenter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Forced updates: a background worker checks the manifests and installs
 *  updates without asking. Managed apps install SILENTLY because Control Center
 *  is their installer of record; Control Center itself may need one tap the first time
 *  (notification), then becomes its own installer of record and goes silent too.
 *
 *  Triggered two ways: every foreground (`MainActivity.onStart`) and a 15 minute
 *  periodic worker. */
object AutoUpdate {
    fun schedule(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            // UPDATE, not KEEP: KEEP means an already-scheduled worker survives
            // the upgrade that changed this line, so the old cadence would have
            // outlived every attempt to shorten it.
            "auto-update", ExistingPeriodicWorkPolicy.UPDATE,
            // 15 minutes is WorkManager's floor for periodic work; anything
            // shorter is silently clamped to it.
            PeriodicWorkRequestBuilder<AutoUpdateWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build())
    }

    fun runNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "auto-update-now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AutoUpdateWorker>().build())
    }

    fun notify(ctx: Context, id: Int, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("updates", "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(id, Notification.Builder(ctx, "updates")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(text).setAutoCancel(true).build())
    }
}

/** Poked externally (`am broadcast`) when a build lands on the bridge.
 *  Exported, so gated by a shared token: without it any installed app could
 *  spam update checks. Worst case even without the gate is only a
 *  version-gated check against your own bridge (no APK injection), so this is
 *  defense-in-depth against nuisance triggers. */
class TriggerUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // The placeholder token is public (it is in the example config), so a
        // build that never set its own must not react to anyone at all.
        val expected = BuildConfig.TRIGGER_TOKEN
        if (expected.isBlank() || expected == "CHANGE-ME-trigger-token") return
        if (intent.getStringExtra("token") != expected) return
        AutoUpdate.runNow(ctx)
    }
}

class AutoUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    /** Fire-and-forget diagnostics to the bridge's /event log: the worker's
     *  failures are otherwise swallowed by runCatching and invisible. */
    private suspend fun report(text: String) {
        if (!Caps.has("events")) return
        runCatching {
            Api.post("/event", org.json.JSONObject()
                .put("device", "controlcenter").put("type", "autoupdate").put("text", text))
        }
    }

    /**
     * Drain the bridge's command queue and deliver anything waiting as local
     * notifications: no separate poller needed, this app already has
     * notification permission.
     */
    private suspend fun drainCommands(): Boolean {
        var sawUpdatePoke = false
        runCatching {
            val cmds = Api.get("/commands/controlcenter").getJSONArray("commands")
            for (i in 0 until cmds.length()) {
                val c = cmds.getJSONObject(i)
                when (c.optString("action")) {
                    "update-apps" -> sawUpdatePoke = true
                    "notify" -> {
                        val a = c.optJSONObject("args") ?: continue
                        AutoUpdate.notify(
                            applicationContext,
                            // stable-ish id per message so repeats replace rather than stack
                            a.optString("title").hashCode(),
                            a.optString("title", "Control Center"),
                            a.optString("text"),
                        )
                    }
                    // other command kinds are left to whatever else on the
                    // bridge side is meant to handle them
                }
            }
        }
        return sawUpdatePoke
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        // Cold process: load the saved bridge address and ask what it offers.
        AppCfg.load(ctx)
        if (!Api.configured) return Result.success()
        Caps.refresh()
        if (Caps.has("commands")) drainCommands()
        val prefs = ctx.getSharedPreferences("controlcenter", Context.MODE_PRIVATE)
        // Off unless the user turned it on in Settings. Installing APKs a
        // server advertises is not something to do by default.
        if (!prefs.getBoolean("autoUpdate", false) || !Caps.has("updater")) {
            return Result.success()
        }

        // The worker often runs in a cold process, so pull the list before
        // using it: otherwise background updates would silently keep using the
        // build's baked-in apps while the tab showed the bridge's.
        ManagedAppsState.load()
        ManagedAppsState.apps.forEach { app ->
            runCatching {
                val cur = Updater.installedVersion(ctx, app.packageName)
                if (cur == null) { report("${app.name}: not installed, skipped"); return@runCatching }
                val info = Updater.fetchManifest(app.manifestUrl)
                if (info.code > cur.first) {
                    report("${app.name}: v${cur.second}(${cur.first}) -> v${info.name}(${info.code}), installing")
                    Updater.downloadAndInstall(ctx, info)
                    kotlinx.coroutines.delay(20_000)
                    val now = Updater.installedVersion(ctx, app.packageName)
                    report("${app.name}: post-install check " +
                        (if (now != null && now.first >= info.code) "OK v${now.second}" else "still v${now?.second} (${now?.first})"))
                    if (now != null && now.first >= info.code) {
                        AutoUpdate.notify(ctx, app.packageName.hashCode(),
                            "${app.name} updated to v${info.name} ✓", "auto-update")
                        runCatching { VersionReport.send(ctx) }
                    }
                }
            }.onFailure { report("${app.name}: FAILED ${it.javaClass.simpleName}: ${it.message}") }
        }

        // Control Center itself: session commit. If Android insists on confirmation,
        // InstallReceiver posts a "tap to finish" notification; once we're our
        // own installer of record, this goes silent as well.
        runCatching {
            val info = Updater.check(ctx)
            if (info != null) Updater.downloadAndInstall(ctx, info, selfUpdate = false)
        }.onFailure { report("Control Center self-update: FAILED ${it.javaClass.simpleName}: ${it.message}") }
        return Result.success()
    }
}
