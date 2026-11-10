package app.controlcenter

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object Updater {
    data class Info(val code: Long, val name: String, val url: String, val notes: String = "",
                    val sha256: String = "", val size: Long = 0)

    fun currentVersion(ctx: Context): Pair<Long, String> {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi) to (pi.versionName ?: "?")
    }

    fun installedVersion(ctx: Context, packageName: String): Pair<Long, String>? = try {
        val pi = ctx.packageManager.getPackageInfo(packageName, 0)
        androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi) to (pi.versionName ?: "?")
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    suspend fun fetchManifest(url: String): Info {
        val j = Api.getAbs(url)
        return Info(j.getLong("versionCode"), j.getString("versionName"), j.getString("url"),
            j.optString("notes", ""), j.optString("sha256", ""), j.optLong("size", 0))
    }

    suspend fun check(ctx: Context): Info? {
        val j = Api.getAbs("${Api.baseUrl}/phone/version.json")
        val remote = j.getLong("versionCode")
        val (cur, _) = currentVersion(ctx)
        return if (remote > cur)
            Info(remote, j.getString("versionName"), j.getString("url"), j.optString("notes", ""),
                j.optString("sha256", ""), j.optLong("size", 0))
        else null
    }

    /** True once the OS lets us install packages. Below O it is always allowed. */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            ctx.packageManager.canRequestPackageInstalls()

    /** Sends the user to the per-app "allow install unknown apps" screen. */
    fun openInstallPermission(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    /** Downloads and installs.
     *
     *  Everything now uses the PackageInstaller session path (selfUpdate is kept
     *  only as an escape hatch to the ACTION_VIEW handoff). The session path is
     *  SILENT once Control Center is the installer of record for the target: true
     *  already for managed apps, and true for Control Center itself after one confirmed self-update
     *  (that first one shows a "tap to finish" notification via InstallReceiver;
     *  every update after is fully hands-off). The session commits to the system
     *  installer service, a separate process, so it survives Control Center being replaced. */
    suspend fun downloadAndInstall(
        ctx: Context, info: Info,
        selfUpdate: Boolean = false,
        onProgress: (Float) -> Unit = {},
    ) {
        if (!canInstall(ctx)) {
            openInstallPermission(ctx)
            throw IllegalStateException(
                "Allow \"install unknown apps\" for Control Center, then tap Update again.")
        }
        // Per-URL cache file: "Update all" runs several downloads concurrently
        // and a single shared update.apk let them clobber each other's bytes.
        val file = File(ctx.cacheDir, "update-${Integer.toHexString(info.url.hashCode())}.apk")
        // A truncated/corrupted download parse-fails at install time with a
        // cryptic INSTALL_PARSE_FAILED_*. Verify against the manifest's size
        // and sha256 (when published) and re-download once before giving up.
        var attempts = 0
        while (true) {
            attempts++
            withContext(Dispatchers.IO) { Api.download(info.url, file, onProgress) }
            val sizeOk = info.size <= 0 || file.length() == info.size
            val hashOk = info.sha256.isEmpty() || sha256Of(file).equals(info.sha256, true)
            if (sizeOk && hashOk) break
            if (attempts >= 2) throw IllegalStateException(
                "download corrupted twice (got ${'$'}{file.length()}B" +
                    if (info.size > 0) " of ${'$'}{info.size}B)" else ")")
        }
        if (selfUpdate) installViaHandoff(ctx, file)
        else withContext(Dispatchers.IO) { installViaSession(ctx, file) }
    }

    private fun sha256Of(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        // mask to 0xFF: a signed Byte >= 0x80 sign-extends under %02x to
        // "ffffffXX", which never matches a real sha256 → every verified
        // download would falsely report "corrupted".
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun installViaHandoff(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    private fun installViaSession(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Silent when we're the installer of record for the package
            // (we are, for apps installed through Control Center); otherwise Android
            // falls back to the normal confirm flow via InstallReceiver.
            params.setRequireUserAction(
                android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("app.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                android.app.PendingIntent.FLAG_MUTABLE else 0) or
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            val intent = Intent("app.controlcenter.INSTALL_RESULT")
                .setClass(ctx, InstallReceiver::class.java)
            val pi = android.app.PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
            session.commit(pi.intentSender)
        }
    }
}
