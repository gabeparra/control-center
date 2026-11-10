package app.controlcenter

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Files tab: a filebrowser instance running on your server, embedded so the
 * phone can pull and push files (logs, configs, whatever you keep there).
 * The WebView is kept alive across tab switches so an in-progress upload
 * or a deep directory position survives navigation.
 *
 * Downloads are handed to Android's DownloadManager rather than the WebView,
 * which can't write files on its own: that's what makes "pull a file to the
 * phone" actually work.
 */
class FilesState(ctx: Context) {
    val canGoBack = mutableStateOf(false)
    /** The URL the WebView was last pointed at, to notice a changed setting. */
    var loaded: String = ""
    /** Why the page failed to load, or null. Shown as a card over the WebView
     *  instead of the browser's own blank or raw error page. */
    val error = mutableStateOf<String?>(null)

    @SuppressLint("SetJavaScriptEnabled")
    val web: WebView = WebView(ctx).apply {
        setBackgroundColor(0xFF14161C.toInt())   // Surface1: no white flash
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                canGoBack.value = view.canGoBack()
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                error.value = null
            }

            override fun onReceivedError(
                view: WebView, request: android.webkit.WebResourceRequest,
                err: android.webkit.WebResourceError,
            ) {
                if (request.isForMainFrame) error.value = err.description?.toString() ?: "could not load"
            }

            override fun onReceivedHttpError(
                view: WebView, request: android.webkit.WebResourceRequest,
                response: android.webkit.WebResourceResponse,
            ) {
                if (request.isForMainFrame && response.statusCode >= 400)
                    error.value = "the server answered HTTP ${response.statusCode}"
            }
        }
        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            runCatching {
                val req = DownloadManager.Request(android.net.Uri.parse(url))
                    .setMimeType(mimeType)
                    .addRequestHeader("User-Agent", userAgent)
                    .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType))
                (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(context, "Downloading…", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Download failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
        loadUrl(url())
        loaded = url()
    }

    companion object {
        /** Set in Settings: your filebrowser (or similar) URL. */
        fun url(): String = AppCfg.filesUrl
    }
}

@Composable
fun FilesScreen(files: FilesState) {
    BackHandler(enabled = files.canGoBack.value) { files.web.goBack() }
    // The WebView is built once, possibly before the URL was set in Settings.
    LaunchedEffect(FilesState.url()) {
        if (FilesState.url().isNotBlank() && files.loaded != FilesState.url()) {
            files.loaded = FilesState.url()
            files.web.loadUrl(FilesState.url())
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                (files.web.parent as? ViewGroup)?.removeView(files.web)
                files.web
            },
            modifier = Modifier.fillMaxSize(),
        )
        files.error.value?.let { why ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface1),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(24.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Could not open the files page", color = Ink)
                    Text("${FilesState.url()}\n$why", color = Muted)
                    Text("Check the files URL in Settings and that the server is running " +
                        "and reachable from this device.", color = Muted)
                    Button(
                        onClick = { files.error.value = null; files.web.loadUrl(FilesState.url()) },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber),
                    ) { Text("Retry") }
                }
            }
        }
        SmallFloatingActionButton(
            onClick = { files.web.loadUrl(FilesState.url()) },
            containerColor = Surface1, contentColor = Muted,
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
        ) { Icon(Icons.Filled.Refresh, "reload") }
    }
}
