package app.rumo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.rumo.core.Geo
import app.rumo.core.RadarKind
import app.rumo.core.UserPoint
import app.rumo.core.UserPoints
import app.rumo.core.directionOf
import app.rumo.core.formatDistance
import app.rumo.core.radarLabel

private val AMBER = Color(0xFFFACC15)
private const val STEP = 10

/** Mesmas cores das bolinhas do mapa (ui/DriveMap.kt). */
private fun kindColor(kind: Int) = when (kind) {
    RadarKind.TRAFFIC_LIGHT_RADAR -> Color(0xFFF97316)
    RadarKind.MOBILE -> Color(0xFFEA580C)
    RadarKind.BUMP -> Color(0xFF9CA3AF)
    RadarKind.RED_LIGHT_CAMERA -> Color(0xFFEF4444)
    RadarKind.POLICE -> Color(0xFF3B82F6)
    RadarKind.TOLL -> Color(0xFF10B981)
    else -> AMBER
}

private fun kindInk(kind: Int) = when (kind) {
    RadarKind.RED_LIGHT_CAMERA, RadarKind.POLICE, RadarKind.TOLL -> Color.White
    else -> Color(0xFF1A1400)
}

private fun kindName(kind: Int) = if (kind == RadarKind.FIXED) "Radar fixo" else radarLabel(kind)

/** Dentro da bolinha: o limite (quem fiscaliza velocidade) ou uma letra, como no mapa. */
private fun kindBadge(kind: Int, limit: Int) = when {
    kind in RadarKind.ENFORCING -> limit.toString()
    kind == RadarKind.RED_LIGHT_CAMERA -> "S"
    kind == RadarKind.POLICE -> "P"
    kind == RadarKind.TOLL -> "$"
    else -> "L"
}

/**
 * Marcar um radar (ou lombada, polícia, pedágio…) que falta no mapa, no lugar onde se tocou no "+". Dois toques: o "+"
 * e o tipo. O limite começa no da via atual e o sentido no do carro; ficam ajustáveis. Tudo só neste aparelho
 * (core/UserPoints.kt). "Meus pontos" lista e apaga o que foi marcado.
 */
@Composable
fun MarkSheet(s: HomeState, a: HomeActions) {
    val p = LocalPalette.current
    val spot = s.markSpot ?: return
    var limit by remember(spot) { mutableIntStateOf(spot.limit) }
    var bothWays by remember(spot) { mutableStateOf(spot.bearing == null) }
    var list by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().card(p).swallowTouches()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Txt(if (list) "Meus pontos" else "Marcar ponto aqui", 30, weight = FontWeight.SemiBold)
                Txt(
                    if (list) "Só neste aparelho · ${s.myPoints.size} marcados"
                    else "Onde você tocou no +. Fica só neste aparelho, mesmo ao atualizar o Rumo.",
                    16, color = p.text2,
                )
            }
            Box(
                Modifier.size(76.dp, 64.dp).clip(RoundedCornerShape(20.dp)).background(p.surface2)
                    .clickable { if (list) list = false else s.sheet = Sheet.NONE },
                contentAlignment = Alignment.Center,
            ) {
                Ico(if (list) Icons.chevronLeft else Icons.x, 30.dp, p.text)
            }
        }
        if (list) {
            PointList(s, a)
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 28.dp, end = 28.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Txt("Limite", 18, color = p.text2)
                    StepButton(Icons.minus) { limit = (limit - STEP).coerceAtLeast(UserPoints.MIN_LIMIT) }
                    Txt("$limit km/h", 30, weight = FontWeight.Bold, numeric = true, modifier = Modifier.width(150.dp), align = TextAlign.Center)
                    StepButton(Icons.plus) { limit = (limit + STEP).coerceAtMost(UserPoints.MAX_LIMIT) }
                    Spacer(Modifier.weight(1f))
                    val mine = spot.bearing?.let { "Meu sentido: ${directionOf(it.toFloat())}" } ?: "Meu sentido: parado"
                    Chip(mine, !bothWays && spot.bearing != null, spot.bearing != null) { bothWays = false }
                    Chip("Os dois sentidos", bothWays, true) { bothWays = true }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    UserPoints.KINDS.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { kind ->
                                KindButton(kind, limit, Modifier.weight(1f)) { a.addPoint(spot, kind, limit, bothWays) }
                            }
                        }
                    }
                }
                Txt(
                    "O limite vale para radar e lombada eletrônica. Parado o GPS não dá o sentido: vale os dois.",
                    15, color = p.text3, maxLines = 2,
                )
                if (s.myPoints.isNotEmpty()) {
                    Box(
                        Modifier.height(60.dp).clip(RoundedCornerShape(18.dp)).background(p.surface2).clickable { list = true }
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Txt("Meus pontos (${s.myPoints.size})", 20, weight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepButton(icon: ImageVector, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.size(64.dp).clip(CircleShape).background(p.surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Ico(icon, 28.dp, p.text)
    }
}

@Composable
private fun Chip(text: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (on) p.accent else p.surface2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, 18, color = if (on) p.onAccent else if (enabled) p.text else p.text3, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun KindButton(kind: Int, limit: Int, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier.height(116.dp).clip(RoundedCornerShape(20.dp)).background(p.surface2).clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Badge(kind, limit, 52.dp)
        Txt(kindName(kind), 17, weight = FontWeight.SemiBold, maxLines = 2, align = TextAlign.Center, lineHeight = 1.1f)
    }
}

@Composable
private fun Badge(kind: Int, limit: Int, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(kindColor(kind)),
        contentAlignment = Alignment.Center,
    ) {
        Txt(kindBadge(kind, limit), 22, color = kindInk(kind), weight = FontWeight.Bold, numeric = true, lineHeight = 1f)
    }
}

/** Os pontos marcados, o mais perto primeiro. Apagar pede um segundo toque. */
@Composable
private fun PointList(s: HomeState, a: HomeActions) {
    val p = LocalPalette.current
    val lat = s.gps.lat
    val lon = s.gps.lon
    var confirm by remember { mutableIntStateOf(-1) }
    // O índice original vai junto: é ele que o removePoint recebe
    val rows = s.myPoints.withIndex()
        .map { (i, pt) -> Triple(i, pt, if (lat != null && lon != null) Geo.distanceM(lat, lon, pt.lat, pt.lon).toInt() else null) }
        .sortedBy { it.third ?: Int.MAX_VALUE }
    Column(
        Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surface2).padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (rows.isEmpty()) Txt("Nenhum ponto marcado ainda.", 18, color = p.text2)
            rows.forEach { (i, pt, dist) ->
                PointRow(pt, dist, confirm == i, { confirm = if (confirm == i) -1 else i }) {
                    confirm = -1
                    a.removePoint(i)
                }
            }
        }
        Txt(
            "Arquivo: Rumo/meus-pontos.csv, na memória interna. As três primeiras colunas são as do CSV do MapaRadar: dá para copiar as linhas e mandar ao fórum deles.",
            14, color = p.text3, maxLines = 2,
        )
    }
}

@Composable
private fun PointRow(pt: UserPoint, distanceM: Int?, asking: Boolean, onAsk: () -> Unit, onDelete: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(16.dp)).background(p.surface).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Badge(pt.kind, pt.limit, 44.dp)
        Column(Modifier.weight(1f)) {
            Txt(UserPoints.label(pt), 20, weight = FontWeight.SemiBold)
            val way = if (pt.bearing >= 0) "sentido ${directionOf(pt.bearing.toFloat())}" else "os dois sentidos"
            val where = distanceM?.let { "a ${formatDistance(it)} · " }.orEmpty()
            Txt(where + way + if (pt.date.isNotEmpty()) " · ${pt.date}" else "", 15, color = p.text2)
        }
        Box(
            Modifier
                .height(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (asking) p.danger else p.surface2)
                .clickable { if (asking) onDelete() else onAsk() }
                .padding(horizontal = if (asking) 18.dp else 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (asking) Txt("Apagar?", 18, color = Color.White, weight = FontWeight.SemiBold) else Ico(Icons.trash, 26.dp, p.text2)
        }
    }
}
