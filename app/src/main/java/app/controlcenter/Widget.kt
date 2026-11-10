package app.controlcenter

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/* Home-screen widget + Quick Settings tiles: glanceable control without
   opening the app. The widget shows stack health (services, containers, disk);
   the tiles wake your configured host through the wake relay and fire a backup.

   Widgets can't do network I/O on the broadcast thread, so every refresh goes
   through a WorkManager job that fetches, then pushes RemoteViews. */

object StackSummary {
    /** Fetches a one-line health summary; null on any failure (widget shows "?"). */
    suspend fun fetch(): Triple<String, String, Boolean>? = withContext(Dispatchers.IO) {
        runCatching {
            // Cold process: learn what this bridge offers before asking for it.
            Caps.refresh()
            var svcUp = 0; var svcTotal = 0
            if (Caps.has("services")) {
                val svc = Api.get("/stack").getJSONArray("services")
                svcTotal = svc.length()
                for (i in 0 until svc.length()) if (svc.getJSONObject(i).optBoolean("active")) svcUp++
            }

            var ctrUp = 0; var ctrTotal = 0
            if (Caps.has("containers")) runCatching {
                val c = Api.get("/containers").getJSONArray("containers")
                ctrTotal = c.length()
                for (i in 0 until c.length()) if (c.getJSONObject(i).optBoolean("running")) ctrUp++
            }

            var diskPct = -1
            if (Caps.has("vitals")) runCatching { diskPct = Api.get("/vitals").getInt("disk_pct") }

            val headline = listOfNotNull(
                if (svcTotal > 0) "$svcUp/$svcTotal svc" else null,
                if (ctrTotal > 0) "$ctrUp/$ctrTotal ctr" else null,
            ).joinToString("   ").ifEmpty { "bridge ok" }
            val detail = if (diskPct >= 0) "disk $diskPct%" else "bridge ok"
            val allGood = svcUp == svcTotal && (ctrTotal == 0 || ctrUp == ctrTotal)
            Triple(headline, detail, allGood)
        }.getOrNull()
    }
}

class StackWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        // Schedule both an immediate refresh and the periodic one; the widget
        // host only guarantees onUpdate, not network access.
        WidgetRefreshWorker.refreshNow(ctx)
        WidgetRefreshWorker.schedule(ctx)
    }

    override fun onEnabled(ctx: Context) = WidgetRefreshWorker.schedule(ctx)

    override fun onDisabled(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WidgetRefreshWorker.UNIQUE)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) WidgetRefreshWorker.refreshNow(ctx)
    }

    companion object {
        const val ACTION_REFRESH = "app.controlcenter.WIDGET_REFRESH"

        fun render(ctx: Context, headline: String, detail: String, good: Boolean) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, StackWidget::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(ctx.packageName, R.layout.widget_stack).apply {
                setTextViewText(R.id.widget_headline, headline)
                setTextViewText(R.id.widget_detail, detail)
                setTextColor(R.id.widget_headline,
                    if (good) 0xFFE8EAED.toInt() else 0xFFE5484D.toInt())
                // tap the body to open the app
                setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                // tap the refresh corner to re-poll
                setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(
                    ctx, 1, Intent(ctx, StackWidget::class.java).setAction(ACTION_REFRESH),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            mgr.updateAppWidget(ids, views)
        }
    }
}

class WidgetRefreshWorker(ctx: Context, params: WorkerParameters) :
    CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        AppCfg.load(applicationContext)          // cold process: prefs not loaded yet
        if (!Api.configured) {
            StackWidget.render(applicationContext, "not set up", "open Control Center", false)
            return Result.success()
        }
        val s = StackSummary.fetch()
        if (s == null) StackWidget.render(applicationContext, "unreachable", "bridge down", false)
        else StackWidget.render(applicationContext, s.first, s.second, s.third)
        return Result.success()
    }

    companion object {
        const val UNIQUE = "widget-refresh"

        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES).build())
        }

        fun refreshNow(ctx: Context) {
            WorkManager.getInstance(ctx).enqueue(
                OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build())
        }
    }
}

/** Quick Settings tile: wake your host through the wake relay. Works when the
 *  box is off, which is the only time it matters: so it deliberately does NOT
 *  go through the bridge (that dies with the box). */
class WakeTile : TileService() {
    override fun onStartListening() {
        AppCfg.load(this)                        // cold process: prefs not loaded yet
        qsTile?.apply {
            state = if (AppCfg.relayUrl.isBlank() || AppCfg.wakeableHost.isBlank())
                Tile.STATE_UNAVAILABLE else Tile.STATE_INACTIVE
            label = "Wake host"
            updateTile()
        }
    }

    override fun onClick() {
        AppCfg.load(this)
        if (AppCfg.relayUrl.isBlank() || AppCfg.wakeableHost.isBlank()) return
        qsTile?.apply { state = Tile.STATE_ACTIVE; label = "Waking…"; updateTile() }
        WorkManager.getInstance(this).enqueue(
            OneTimeWorkRequestBuilder<WakeWorker>().build())
    }
}

class WakeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        AppCfg.load(applicationContext)
        val relay = AppCfg.relayUrl
        val host = AppCfg.wakeableHost
        if (relay.isBlank() || host.isBlank()) return Result.failure()
        return runCatching { Api.postAbs("$relay/wake/$host", JSONObject()) }
            .fold({ Result.success() }, { Result.failure() })
    }
}
