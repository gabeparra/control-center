package app.controlcenter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Reports what's actually installed (Control Center + managed apps) to the
 *  bridge's version ledger, closing the build→install loop: the bridge can
 *  notify you when a reported version advances. */
object VersionReport {
    fun payload(ctx: Context): JSONObject {
        val apps = JSONObject()
        val (code, name) = Updater.currentVersion(ctx)
        apps.put(ctx.packageName, JSONObject().put("code", code).put("name", name))
        ManagedAppsState.apps.forEach { m ->
            Updater.installedVersion(ctx, m.packageName)?.let { (c, n) ->
                apps.put(m.packageName, JSONObject().put("code", c).put("name", n))
            }
        }
        return JSONObject().put("device", "controlcenter").put("apps", apps)
    }

    suspend fun send(ctx: Context) {
        Api.post("/versions", payload(ctx))
    }
}

/** Fires in the NEW build the moment a self-update completes: posts the
 *  "updated ✓: tap to open" notification that the in-process flow can't show
 *  (it dies with the old process), and reports the new version to the bridge. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val (_, name) = Updater.currentVersion(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("updates", "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        nm.notify(1001, Notification.Builder(ctx, "updates")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Control Center updated to v$name ✓")
            .setContentText("tap to open")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
        val pending = goAsync()
        Thread {
            runCatching {
                runBlocking {
                    // New process: load the saved bridge address and only report
                    // to a bridge that says it tracks app versions.
                    AppCfg.load(ctx)
                    if (Api.configured) {
                        Caps.refresh()
                        if (Caps.has("updater")) VersionReport.send(ctx)
                    }
                }
            }
            pending.finish()
        }.start()
    }
}
