package app.controlcenter

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** Minimal ANSI SGR parser -> AnnotatedString, so the Term tab renders tmux
 *  colored output (claude's UI, git colors, etc.) instead of raw escape codes. */
object Ansi {
    private const val ESC = '\u001b'

    private val base = listOf(
        Color(0xFF3B4048), Color(0xFFE06C5B), Color(0xFF7FB069), Color(0xFFE0B341),
        Color(0xFF5B9BD5), Color(0xFFB08CC7), Color(0xFF4FB6B0), Color(0xFFC7CCD1),
    )
    private val bright = listOf(
        Color(0xFF6B7078), Color(0xFFFF8B7A), Color(0xFF9FD88A), Color(0xFFFFD066),
        Color(0xFF7FBBF5), Color(0xFFD0ACE7), Color(0xFF6FD6D0), Color(0xFFF0F4F8),
    )

    fun render(raw: String, default: Color): AnnotatedString = buildAnnotatedString {
        var fg: Color? = null
        var bold = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == ESC && i + 1 < raw.length && raw[i + 1] == '[') {
                val end = raw.indexOf('m', i)
                if (end == -1) { i++; continue }
                val codes = raw.substring(i + 2, end).split(';').mapNotNull { it.toIntOrNull() }
                var j = 0
                while (j < codes.size) {
                    when (val code = codes[j]) {
                        0 -> { fg = null; bold = false }
                        1 -> bold = true
                        22 -> bold = false
                        39 -> fg = null
                        in 30..37 -> fg = base[code - 30]
                        in 90..97 -> fg = bright[code - 90]
                        38 -> {
                            if (j + 2 < codes.size && codes[j + 1] == 5) {
                                fg = xterm(codes[j + 2]); j += 2
                            } else if (j + 4 < codes.size && codes[j + 1] == 2) {
                                fg = Color(codes[j + 2], codes[j + 3], codes[j + 4]); j += 4
                            }
                        }
                    }
                    j++
                }
                i = end + 1
            } else if (c == ESC) {
                // non-SGR escape (cursor moves etc.): skip the sequence
                var k = i + 1
                while (k < raw.length && raw[k] !in 'A'..'Z' && raw[k] !in 'a'..'z') k++
                i = k + 1
            } else {
                withStyle(SpanStyle(
                    color = fg ?: default,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                )) { append(c) }
                i++
            }
        }
    }

    private fun xterm(n: Int): Color = when (n) {
        in 0..7 -> base[n]
        in 8..15 -> bright[n - 8]
        in 16..231 -> {
            val v = n - 16
            val r = (v / 36) * 51; val g = ((v / 6) % 6) * 51; val b = (v % 6) * 51
            Color(r, g, b)
        }
        else -> { val g = 8 + (n - 232) * 10; Color(g, g, g) }
    }
}
