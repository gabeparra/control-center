package app.controlcenter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.launch

/** Manifest-declared: catches PackageInstaller session results for installs of
 *  OTHER managed apps. Self-updates never come through here: they use the
 *  standalone-installer handoff, because this process dies when it's replaced. */
class InstallReceiver : BroadcastReceiver() {

    /** Fire-and-forget diagnostics to the bridge: session results are the only
     *  place Android says WHY an install stalled (pending confirm, failure code). */
    private fun report(intent: Intent, status: Int) {
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: "?"
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch {
            runCatching {
                Api.post("/event", org.json.JSONObject()
                    .put("device", "controlcenter").put("type", "install-result")
                    .put("text", "pkg=$pkg status=$status $msg"))
            }
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        report(intent, st)
        when (val status = st) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) {
                    // foreground: open the confirm directly; background: Android
                    // blocks activity starts, so ALSO post a tappable notification
                    runCatching { ctx.startActivity(confirm) }
                    val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
                    nm.createNotificationChannel(android.app.NotificationChannel(
                        "updates", "App updates",
                        android.app.NotificationManager.IMPORTANCE_HIGH))
                    val pi = android.app.PendingIntent.getActivity(
                        ctx, 2001, confirm,
                        android.app.PendingIntent.FLAG_IMMUTABLE or
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                    nm.notify(2001, android.app.Notification.Builder(ctx, "updates")
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setContentTitle("Update ready to install")
                        .setContentText("tap once to approve, future updates are silent")
                        .setContentIntent(pi).setAutoCancel(true).build())
                } else Toast.makeText(ctx, "installer: no confirm intent", Toast.LENGTH_LONG).show()
            }
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(ctx, "installed ✓", Toast.LENGTH_SHORT).show()
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                Toast.makeText(
                    ctx, "blocked: same app installed with a different signature: " +
                        "uninstall it once, then install fresh.", Toast.LENGTH_LONG).show()
            PackageInstaller.STATUS_FAILURE_ABORTED -> { /* user cancelled */ }
            else -> Toast.makeText(
                ctx, "install failed ($status): " +
                    (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown"),
                Toast.LENGTH_LONG).show()
        }
    }
}
