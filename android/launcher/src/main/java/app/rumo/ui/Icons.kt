package app.rumo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Ícones de traço 2 na grade 24, os mesmos do protótipo HTML. */
object Icons {
    private const val PHONE = "M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"

    val home = icon("home", s("M3 10.5 12 3l9 7.5"), s("M5 9.5V21h14V9.5"), s("M10 21v-6h4v6"))
    val cast = icon(
        "cast",
        s("M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6"),
        s("M2 12a9 9 0 0 1 8 8"), s("M2 16a5 5 0 0 1 4 4"), s("M2 20h.01"),
    )
    val music = icon("music", s("M9 18V5l12-2v13"), s(circle(6f, 18f, 3f)), s(circle(18f, 16f, 3f)))
    val radio = icon(
        "radio",
        s("M4.9 19.1C1 15.2 1 8.8 4.9 4.9M7.8 16.2c-2.3-2.3-2.3-6.1 0-8.5M16.2 7.8c2.3 2.3 2.3 6.1 0 8.5M19.1 4.9C23 8.8 23 15.1 19.1 19"),
        s(circle(12f, 12f, 2f)),
    )
    val phone = icon("phone", s(PHONE))
    val apps = icon(
        "apps",
        s(rect(3f, 3f, 7f, 7f, 1.5f)), s(rect(14f, 3f, 7f, 7f, 1.5f)),
        s(rect(3f, 14f, 7f, 7f, 1.5f)), s(rect(14f, 14f, 7f, 7f, 1.5f)),
    )
    val mic = icon("mic", s(rect(9f, 2f, 6f, 12f, 3f)), s("M5 10a7 7 0 0 0 14 0"), s("M12 17v5"))
    val prev = icon("prev", f("M19 5 8.5 12 19 19Z"), s("M5 5v14", 2.6f))
    val next = icon("next", f("M5 5l10.5 7L5 19Z"), s("M19 5v14", 2.6f))
    // Play e pausa juntos: o ZLINK não informa se está tocando, então o botão só alterna
    val playPause = icon("play-pause", f("M3.5 5 12.5 12 3.5 19Z"), s("M17 5v14", 3f), s("M21.5 5v14", 3f))
    val nav = icon("nav", f("M12 2 19 21 12 17 5 21Z"))
    val pin = icon("pin", s("M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0Z"), s(circle(12f, 10f, 3f)))
    val gear = icon(
        "gear",
        s(circle(12f, 12f, 3f)), s(circle(12f, 12f, 7f)),
        s("M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9 7 7M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1"),
    )
    val x = icon("x", s("M18 6 6 18M6 6l12 12"))
    val check = icon("check", s("M5 12l5 5L20 7", 3f))
    val plus = icon("plus", s("M12 5v14M5 12h14", 2.8f))
    val minus = icon("minus", s("M5 12h14", 2.8f))
    private const val STAR = "M12 2.5l2.9 6 6.6.9-4.8 4.6 1.2 6.5L12 17.4l-5.9 3.1 1.2-6.5-4.8-4.6 6.6-.9Z"
    val star = icon("star", s(STAR))
    val starFill = icon("star-fill", f(STAR))
    val chevronLeft = icon("chevron-left", s("M15 5l-7 7 7 7", 2.6f))
    val chevronRight = icon("chevron-right", s("M9 5l7 7-7 7", 2.6f))
    val search = icon("search", s(circle(11f, 11f, 7f)), s("M20 20l-4-4"))
    val info = icon("info", s(circle(12f, 12f, 10f)), s("M12 16v-4M12 8h.01"))
    val trash = icon("trash", s("M3 6h18"), s("M8 6V4h8v2"), s("M19 6l-1 14H6L5 6"), s("M10 11v6M14 11v6"))
    val sun = icon(
        "sun", s(circle(12f, 12f, 4f)),
        s("M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"),
    )
    val moon = icon("moon", s("M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z"))
    val themeAuto = icon("theme-auto", s(circle(12f, 12f, 9f)), f("M12 3a9 9 0 0 1 0 18Z"))
    val speedCamera = icon(
        "radar",
        s("M3 8h11a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H3Z"), s(circle(9f, 12f, 2f)),
        s("m16 11 5-2v6l-5-2"), s("M8 16v5M5 21h6"),
    )
}

@Composable
fun Ico(icon: ImageVector, size: Dp, color: Color, modifier: Modifier = Modifier) {
    Image(
        painter = rememberVectorPainter(icon),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(color),
    )
}

/** Um traçado do ícone; [fill] preenche além de contornar (como fill="currentColor" no SVG). */
private class Part(val d: String, val fill: Boolean, val width: Float)

private fun s(d: String, width: Float = 2f) = Part(d, false, width)
private fun f(d: String) = Part(d, true, 2f)

private fun circle(cx: Float, cy: Float, r: Float) =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0Z"

private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
    "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}" +
        "h${2 * r - w}a$r $r 0 0 1 ${-r} ${-r}v${2 * r - h}a$r $r 0 0 1 $r ${-r}Z"

private fun icon(name: String, vararg parts: Part): ImageVector {
    val b = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    for (p in parts) {
        b.addPath(
            pathData = addPathNodes(p.d),
            fill = if (p.fill) SolidColor(Color.Black) else null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = p.width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return b.build()
}
