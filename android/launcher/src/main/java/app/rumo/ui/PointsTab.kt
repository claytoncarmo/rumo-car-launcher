package app.rumo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.rumo.core.Geo
import app.rumo.core.RadarKind
import app.rumo.core.UserPoint
import app.rumo.core.UserPoints
import app.rumo.core.compassOf
import app.rumo.core.directionOf
import app.rumo.core.formatDistance
import app.rumo.device.GpsInfo
import app.rumo.device.GpsStatus
import kotlinx.coroutines.delay

/** Metros que cada toque move o ponto ao longo da rua. */
private const val MOVE_M = 5

/** Quanto tempo o botão de apagar espera o segundo toque. */
private const val DELETE_CONFIRM_MS = 4_000L

/**
 * Aba Pontos das configurações da home: os pontos marcados no mapa (core/UserPoints.kt), os últimos adicionados
 * primeiro. Tocar num ponto abre a edição (tipo, limite, sentido e posição); apagar pede um segundo toque.
 */
@Composable
internal fun PointsTab(s: HomeState, a: HomeActions) {
    var editing by remember { mutableStateOf<Int?>(null) }
    // Apagar o ponto aberto (ou a lista mudar) volta para a lista
    val index = editing?.takeIf { it in s.myPoints.indices }
    if (index == null) PointsList(s, a) { editing = it } else PointEditor(s, a, index) { editing = null }
}

@Composable
private fun PointsList(s: HomeState, a: HomeActions, onEdit: (Int) -> Unit) {
    val p = LocalPalette.current
    var confirm by remember { mutableIntStateOf(-1) }
    // A lista guarda os pontos na ordem em que foram marcados: invertida, os últimos vêm primeiro
    val rows = s.myPoints.withIndex().toList().asReversed()
    val lat = s.gps.lat.takeIf { s.gps.status == GpsStatus.OK }
    val lon = s.gps.lon.takeIf { s.gps.status == GpsStatus.OK }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt(
            if (rows.isEmpty()) "Nenhum ponto marcado ainda. Toque no + do card do mapa para marcar um radar, lombada, polícia ou pedágio que falta."
            else "${rows.size} ${if (rows.size == 1) "ponto marcado" else "pontos marcados"} · só neste aparelho · os últimos adicionados primeiro. Toque num ponto para editar.",
            16, color = p.text2, maxLines = 2,
        )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surface2).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows, key = { it.index }) { (i, pt) ->
                val distance = if (lat != null && lon != null) Geo.distanceM(lat, lon, pt.lat, pt.lon).toInt() else null
                SavedPointRow(pt, distance, confirm == i, { onEdit(i) }, { confirm = if (confirm == i) -1 else i }) {
                    confirm = -1
                    a.removePoint(i)
                }
            }
        }
        Txt(
            "Arquivo: Rumo/my-points.csv, na memória interna. As três primeiras colunas são as do CSV do MapaRadar: dá para copiar as linhas e mandar ao fórum deles.",
            14, color = p.text3, maxLines = 2,
        )
    }
}

@Composable
private fun SavedPointRow(pt: UserPoint, distanceM: Int?, asking: Boolean, onOpen: () -> Unit, onAsk: () -> Unit, onDelete: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(16.dp)).background(p.surface).clickable(onClick = onOpen).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Badge(pt.kind, pt.limit, 48.dp)
        Column(Modifier.weight(1f)) {
            Txt(UserPoints.label(pt), 21, weight = FontWeight.SemiBold)
            Txt(pointDetail(pt, distanceM), 15, color = p.text2)
        }
        Box(
            Modifier
                .height(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (asking) p.danger else p.surface2)
                .clickable { if (asking) onDelete() else onAsk() }
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (asking) Txt("Apagar?", 18, color = Color.White, weight = FontWeight.SemiBold) else Ico(Icons.trash, 26.dp, p.text2)
        }
    }
}

/** "a 350 m · sentido norte · 01/11/2026". */
private fun pointDetail(pt: UserPoint, distanceM: Int?): String {
    val way = if (pt.bearing >= 0) "sentido ${directionOf(pt.bearing.toFloat())}" else "os dois sentidos"
    val where = distanceM?.let { "a ${formatDistance(it)} · " }.orEmpty()
    val day = pt.date.split('-').takeIf { it.size == 3 }?.reversed()?.joinToString("/")
    return where + way + (day?.let { " · $it" } ?: "")
}

@Composable
private fun PointEditor(s: HomeState, a: HomeActions, index: Int, onClose: () -> Unit) {
    val p = LocalPalette.current
    val pt = s.myPoints[index]
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(confirming) {
        if (confirming) {
            delay(DELETE_CONFIRM_MS)
            confirming = false
        }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(
            Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard("Tipo") {
                UserPoints.KINDS.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { kind ->
                            KindButton(kind, pt.limit, Modifier.weight(1f), selected = kind == pt.kind) {
                                a.updatePoint(index, UserPoints.withKind(pt, kind))
                            }
                        }
                    }
                }
                if (pt.kind in RadarKind.ENFORCING) {
                    StepperRow(
                        title = "Limite",
                        detail = "Velocidade fiscalizada neste ponto.",
                        value = "${pt.limit} km/h",
                        canDecrease = pt.limit > UserPoints.MIN_LIMIT,
                        canIncrease = pt.limit < UserPoints.MAX_LIMIT,
                        onDecrease = { a.updatePoint(index, UserPoints.withLimit(pt, pt.limit - LIMIT_STEP)) },
                        onIncrease = { a.updatePoint(index, UserPoints.withLimit(pt, pt.limit + LIMIT_STEP)) },
                    )
                }
            }
            SectionCard("Sentido") {
                Chip("Os dois sentidos", pt.bearing < 0, true) { a.updatePoint(index, pt.copy(bearing = -1)) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (deg in COMPASS_DEGREES) {
                        val on = pt.bearing >= 0 && Geo.angleDiff(pt.bearing.toDouble(), deg.toDouble()) <= 22.5
                        Chip(compassOf(deg.toFloat()), on, true, Modifier.weight(1f)) { a.updatePoint(index, pt.copy(bearing = deg)) }
                    }
                }
                Txt(
                    if (pt.bearing >= 0) "Avisa quem anda para o ${directionOf(pt.bearing.toFloat())} (até 45° de diferença)."
                    else "Avisa quem passa nos dois sentidos da rua.",
                    15, color = p.text2, maxLines = 2,
                )
            }
        }
        // Sem rolagem: o mapa ocupa o que sobra e "Voltar à lista" e "Apagar ponto" ficam sempre à vista
        Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PointPreview(s, a, index, pt, Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Voltar à lista", enabled = true, primary = false, onClick = onClose)
                PillButton(
                    if (confirming) "Toque de novo para apagar" else "Apagar ponto",
                    enabled = true,
                    primary = confirming,
                ) {
                    if (confirming) {
                        confirming = false
                        a.removePoint(index)
                    } else {
                        confirming = true
                    }
                }
            }
        }
    }
}

/**
 * O mapa em volta do ponto que está sendo editado, com os botões que o movem ao longo da rua em cima dele, para ver o
 * efeito de cada mudança (posição, tipo, limite). Com sentido, o mapa gira para o sentido do ponto ficar para cima (o
 * + sobe e o − desce); sem, fica com o norte para cima.
 */
@Composable
private fun PointPreview(s: HomeState, a: HomeActions, index: Int, pt: UserPoint, modifier: Modifier) {
    val p = LocalPalette.current
    val fonts = s.fontsDir
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.mapBlock)) {
        if (s.mapFiles.isNotEmpty() && fonts != null) {
            val position = GpsInfo(
                status = GpsStatus.OK,
                lat = pt.lat,
                lon = pt.lon,
                bearing = pt.bearing.takeIf { it >= 0 }?.toFloat(),
            )
            DriveMap(s.mapFiles, fonts, s.radars, position, null, p === Night, preview = true)
            Txt(
                if (pt.bearing >= 0) "Sentido do ponto (${directionOf(pt.bearing.toFloat())}) para cima" else "Norte para cima",
                14, color = p.text2,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).clip(RoundedCornerShape(10.dp))
                    .background(p.surface.copy(alpha = 0.85f)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Column(Modifier.align(Alignment.CenterEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MoveButton("+$MOVE_M m", Modifier.width(112.dp)) { a.movePoint(index, MOVE_M) }
                MoveButton("−$MOVE_M m", Modifier.width(112.dp)) { a.movePoint(index, -MOVE_M) }
            }
        } else {
            Txt("Sem dados do mapa para mostrar o ponto.", 16, color = p.text2, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun MoveButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier.height(76.dp).clip(RoundedCornerShape(22.dp)).background(p.surface.copy(alpha = 0.92f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, 30, weight = FontWeight.Bold, numeric = true, align = TextAlign.Center)
    }
}

private const val LIMIT_STEP = 10

/** Os oito pontos cardeais, em graus, na ordem da linha de sentidos. */
private val COMPASS_DEGREES = listOf(0, 45, 90, 135, 180, 225, 270, 315)
