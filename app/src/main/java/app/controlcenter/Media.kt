package app.controlcenter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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

/* Media: the Jellyfin library browser, plus the Files pane when a file
 * browser address is set in Settings.
 *
 * Each pane renders its own screen composable. With only the library there is
 * no pane selector at all.
 */

enum class MediaPane(val label: String) {
    LIBRARY("Library"),
    FILES("Files"),
}

/** The panes that have something behind them. Library goes through the bridge;
 *  Files needs its own address, so a blank one hides it. The whole Media tab
 *  disappears when this list is empty. */
fun mediaPanes(): List<MediaPane> = MediaPane.entries.filter {
    when (it) {
        MediaPane.LIBRARY -> Api.configured && Caps.has("jellyfin")
        MediaPane.FILES -> AppCfg.filesUrl.isNotBlank()
    }
}

@Composable
fun MediaScreen(snackbar: SnackbarHostState, files: FilesState) {
    // Survives tab switches, so flipping away and back does not reset you to
    // the first pane or reload a WebView you were part-way through.
    val panes = mediaPanes()
    var pane by rememberSaveable { mutableStateOf(MediaPane.LIBRARY.name) }
    val current = runCatching { MediaPane.valueOf(pane) }.getOrNull()
        ?.takeIf { it in panes } ?: panes.firstOrNull() ?: return

    Column(Modifier.fillMaxSize()) {
        if (panes.size > 1) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("MEDIA", color = Amber, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.width(6.dp))
            panes.forEach { p ->
                val on = p == current
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (on) Amber else Surface1)
                        .clickable { pane = p.name }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(p.label, color = if (on) Bg else Ink, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        Box(Modifier.weight(1f)) {
            when (current) {
                MediaPane.LIBRARY -> JellyfinScreen(snackbar)
                MediaPane.FILES -> FilesScreen(files)
            }
        }
    }
}
