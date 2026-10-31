package app.rumo.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.rumo.core.PT_BR

/** Cores do protótipo HTML (variáveis CSS do .stage), com o âmbar como destaque. */
@Immutable
class Palette(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val line: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val mapBlock: Color,
    val accentInk: Color,
) {
    val accent = Color(0xFFFFB020)
    val onAccent = Color(0xFF1C1300)
    val accentSoft = Color(0x29FFB020)
    val danger = Color(0xFFDC2626)
}

val Night = Palette(
    bg = Color(0xFF06080B),
    surface = Color(0xFF11151B),
    surface2 = Color(0xFF1A2028),
    surface3 = Color(0xFF262D37),
    line = Color(0x12FFFFFF),
    text = Color(0xFFEEF2F6),
    text2 = Color(0xFFA3ADBA),
    text3 = Color(0xFF6F7A89),
    mapBlock = Color(0xFF0E1217),
    accentInk = Color(0xFFFFC45E),
)

val Day = Palette(
    bg = Color(0xFFD8DDE3),
    surface = Color(0xFFF8F9FB),
    surface2 = Color(0xFFEAEEF2),
    surface3 = Color(0xFFDBE1E8),
    line = Color(0x170A141E),
    text = Color(0xFF0C1117),
    text2 = Color(0xFF475262),
    text3 = Color(0xFF7A8594),
    mapBlock = Color(0xFFE9ECEF),
    accentInk = Color(0xFF8A4F00),
)

val LocalPalette = staticCompositionLocalOf { Night }

/** Tema automático do protótipo: noite das 18h às 6h. */
fun isNight(hour: Int): Boolean = hour >= 18 || hour < 6

/**
 * Automático: escuro com o farol aceso, como o Android Auto (o multimídia informa o farol pelo serviço carstatus);
 * em aparelho sem esse serviço ([headlight] null), das 18h às 6h ou com o modo noturno do sistema.
 * Escuro e Claro fixam o tema.
 */
enum class ThemeMode(val label: String) {
    AUTO("automático"), DARK("escuro"), LIGHT("claro");

    fun night(hour: Int, systemNight: Boolean, headlight: Boolean? = null): Boolean = when (this) {
        AUTO -> headlight ?: (isNight(hour) || systemNight)
        DARK -> true
        LIGHT -> false
    }

    fun next(): ThemeMode = entries[(ordinal + 1) % entries.size]
}

/** Texto de uma linha (por padrão) com reticências; [numeric] usa algarismos de largura fixa. */
@Composable
fun Txt(
    text: String,
    size: Int,
    modifier: Modifier = Modifier,
    color: Color = LocalPalette.current.text,
    weight: FontWeight = FontWeight.Medium,
    numeric: Boolean = false,
    caps: Boolean = false,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Unspecified,
    lineHeight: Float = 1.2f,
) {
    BasicText(
        text = if (caps) text.uppercase(PT_BR) else text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            fontWeight = weight,
            fontFeatureSettings = if (numeric) "tnum" else null,
            letterSpacing = if (caps) (size * 0.1f).sp else TextUnit.Unspecified,
            textAlign = align,
            lineHeight = (size * lineHeight).sp,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
