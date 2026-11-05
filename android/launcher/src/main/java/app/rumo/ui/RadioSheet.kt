package app.rumo.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.rumo.core.Favorite
import app.rumo.core.Favorites
import app.rumo.core.PT_BR

/** Comandos da tela de rádio (o HomeActivity executa). */
sealed class RadioCmd {
    object Play : RadioCmd()
    object OpenApp : RadioCmd()
    object Scan : RadioCmd()
    object StopScan : RadioCmd()
    object ImportPresets : RadioCmd()
    object SaveLog : RadioCmd()
    data class Seek(val up: Boolean) : RadioCmd()
    data class Step(val up: Boolean) : RadioCmd()
    data class Tune(val khz: Int) : RadioCmd()
    data class ToggleFavorite(val khz: Int) : RadioCmd()
    data class Rename(val khz: Int, val name: String) : RadioCmd()
    data class Remove(val khz: Int) : RadioCmd()
}

/** Procura de estações: em andamento e o que já achou (kHz, em ordem de achado). */
data class RadioScan(val running: Boolean, val found: List<Int>)

private fun freqNumber(khz: Int) = if (khz >= 30_000) String.format(PT_BR, "%.1f", khz / 1000.0) else khz.toString()
private fun freqBand(khz: Int) = if (khz >= 30_000) "FM" else "AM"

/**
 * Tela de rádio do Rumo, no lugar do app de rádio do aparelho: estação atual em letras grandes, busca e ajuste fino,
 * favoritas próprias (sem limite, com nome) e procura de estações. Abrir a tela já põe o rádio para tocar.
 */
@Composable
fun RadioSheet(s: HomeState, a: HomeActions) {
    val p = LocalPalette.current
    var tab by remember { mutableStateOf(if (s.favorites.isEmpty()) 1 else 0) }
    var renaming by remember { mutableStateOf<Favorite?>(null) }
    // Teclado só com o carro parado (derivado: a folha não é refeita a cada posição do GPS, só quando isso muda)
    val parked by remember { derivedStateOf { (s.gps.speedKmh ?: 0) < 5 } }

    Box(Modifier.fillMaxSize().card(p).swallowTouches()) {
        Row(Modifier.fillMaxSize().padding(start = 28.dp, end = 14.dp, top = 14.dp, bottom = 18.dp)) {
            StationPanel(s, a, Modifier.weight(1f).fillMaxHeight())
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1.15f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TabButton("Favoritas (${s.favorites.size})", tab == 0) { tab = 0 }
                    TabButton("Procurar estações", tab == 1) { tab = 1 }
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(76.dp, 64.dp).clip(RoundedCornerShape(20.dp)).background(p.surface2).clickable { s.sheet = Sheet.NONE },
                        contentAlignment = Alignment.Center,
                    ) {
                        Ico(Icons.x, 30.dp, p.text)
                    }
                }
                Spacer(Modifier.height(14.dp))
                if (tab == 0) {
                    FavoritesGrid(s, a, parked) { renaming = it }
                } else {
                    ScanList(s, a)
                }
            }
        }
        renaming?.let { fav -> RenameDialog(fav, onDone = { name -> a.radio(RadioCmd.Rename(fav.khz, name)); renaming = null }) { renaming = null } }
    }
}

@OptIn(ExperimentalFoundationApi::class) // combinedClickable
@Composable
private fun StationPanel(s: HomeState, a: HomeActions, modifier: Modifier) {
    val p = LocalPalette.current
    val radio = s.radio
    val fav = radio?.let { r -> s.favorites.firstOrNull { it.khz == r.khz } }
    Column(modifier) {
        // Toque longo no título: salva o registro dos comandos (teste)
        Box(Modifier.height(64.dp).combinedClickable(onLongClick = { a.radio(RadioCmd.SaveLog) }) {}, contentAlignment = Alignment.CenterStart) {
            Txt("Rádio", 30, weight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Txt(radio?.khz?.let(::freqNumber) ?: "--", 96, weight = FontWeight.SemiBold, numeric = true, lineHeight = 1f)
            Txt(radio?.khz?.let(::freqBand) ?: "", 26, color = p.text2, modifier = Modifier.padding(start = 10.dp, bottom = 14.dp))
            Spacer(Modifier.weight(1f))
            if (radio != null) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape).clickable { a.radio(RadioCmd.ToggleFavorite(radio.khz)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Ico(if (fav != null) Icons.starFill else Icons.star, 40.dp, if (fav != null) p.accent else p.text2)
                }
            }
        }
        Txt(Favorites.line(s.favorites, radio?.khz, radio?.text).ifEmpty { " " }, 20, color = p.text2, maxLines = 1)
        // Sem "Ouvir rádio": abrir esta tela já põe o rádio para tocar (o serviço do rádio assume o áudio ao ligar)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ControlButton(Icons.prev, "Buscar") { a.radio(RadioCmd.Seek(false)) }
            // Ajuste fino: 0,2 MHz pelas frequências ímpares
            ControlButton(Icons.chevronLeft, null) { a.radio(RadioCmd.Step(false)) }
            ControlButton(Icons.chevronRight, null) { a.radio(RadioCmd.Step(true)) }
            ControlButton(Icons.next, "Buscar") { a.radio(RadioCmd.Seek(true)) }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).clickable { a.radio(RadioCmd.OpenApp) }.padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt("App do rádio ›", 15, color = p.accentInk, weight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, label: String?, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.size(104.dp, 96.dp).clip(RoundedCornerShape(24.dp)).background(p.surface2).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Ico(icon, if (label == null) 40.dp else 34.dp, p.text)
        label?.let { Txt(it, 14, color = p.text2) }
    }
}

@Composable
private fun TabButton(label: String, on: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (on) p.text else p.surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, 17, color = if (on) p.surface else p.text, weight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalFoundationApi::class) // combinedClickable
@Composable
private fun FavoritesGrid(s: HomeState, a: HomeActions, parked: Boolean, onRename: (Favorite) -> Unit) {
    val p = LocalPalette.current
    if (s.favorites.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Txt(
                "Nenhuma favorita ainda. Toque na estrela para favoritar a estação atual, ou use Procurar estações.",
                18, color = p.text2, maxLines = 3,
            )
            if (s.devicePresets.isNotEmpty()) {
                Box(
                    Modifier.height(56.dp).clip(RoundedCornerShape(18.dp)).background(p.surface2)
                        .clickable { a.radio(RadioCmd.ImportPresets) }.padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Txt("Trazer as ${s.devicePresets.size} memórias do aparelho", 17, weight = FontWeight.SemiBold)
                }
            }
        }
        return
    }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(s.favorites, key = { it.khz }) { fav ->
            val current = s.radio?.khz == fav.khz
            Box {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (current) p.accentSoft else p.surface2)
                        .then(if (current) Modifier.border(2.dp, p.accent, RoundedCornerShape(20.dp)) else Modifier)
                        .combinedClickable(onLongClick = { menuFor = fav.khz }) { a.radio(RadioCmd.Tune(fav.khz)) }
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Txt(freqNumber(fav.khz), 30, weight = FontWeight.SemiBold, numeric = true, lineHeight = 1f)
                    // Sem nome dado: o último texto que a emissora mandou (nas últimas horas)
                    Txt(fav.name.ifBlank { s.radioNames[fav.khz] ?: freqBand(fav.khz) }, 15, color = p.text2)
                }
                if (menuFor == fav.khz) {
                    FavoriteMenu(
                        onDismiss = { menuFor = null },
                        onRename = { if (parked) onRename(fav) else s.toast = "Dar nome só com o carro parado" },
                        onRemove = { a.radio(RadioCmd.Remove(fav.khz)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteMenu(onDismiss: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
    val p = LocalPalette.current
    val below = with(LocalDensity.current) { 100.dp.roundToPx() }
    val shape = RoundedCornerShape(22.dp)
    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, below),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
    ) {
        Column(Modifier.width(280.dp).clip(shape).background(p.surface).border(1.dp, p.line, shape).padding(8.dp)) {
            MenuItem(Icons.star, "Dar nome") { onDismiss(); onRename() }
            MenuItem(Icons.trash, "Remover", p.danger) { onDismiss(); onRemove() }
        }
    }
}

@Composable
private fun ScanList(s: HomeState, a: HomeActions) {
    val p = LocalPalette.current
    val scan = s.radioScan
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .height(60.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(if (scan?.running == true) p.surface2 else p.accent)
                    .clickable { a.radio(if (scan?.running == true) RadioCmd.StopScan else RadioCmd.Scan) }
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Ico(if (scan?.running == true) Icons.x else Icons.search, 26.dp, if (scan?.running == true) p.text else p.onAccent)
                    Txt(
                        if (scan?.running == true) "Parar" else if (scan == null) "Procurar estações" else "Procurar de novo",
                        18, color = if (scan?.running == true) p.text else p.onAccent, weight = FontWeight.SemiBold,
                    )
                }
            }
            val status = when {
                scan == null -> "Varre a faixa FM com a busca do rádio (cerca de 1 minuto). Não mexe nas memórias do aparelho."
                scan.running -> "Procurando… ${scan.found.size} estações" + (s.radio?.khz?.let { " · ${freqNumber(it)}" } ?: "")
                else -> "${scan.found.size} estações encontradas. Toque na estrela para favoritar."
            }
            Txt(status, 16, color = p.text2, maxLines = 2, modifier = Modifier.weight(1f))
        }
        val found = scan?.found?.sorted().orEmpty()
        // A busca sobe pela faixa, então o resultado novo é sempre o último: durante a busca, a lista rola para o fim
        val listState = rememberLazyListState()
        LaunchedEffect(found.size) {
            if (scan?.running == true && found.isNotEmpty()) listState.animateScrollToItem(found.lastIndex)
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(found, key = { it }) { khz ->
                val fav = s.favorites.firstOrNull { it.khz == khz }
                val current = s.radio?.khz == khz
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (current) p.accentSoft else p.surface2)
                        .clickable(enabled = scan?.running != true) { a.radio(RadioCmd.Tune(khz)) }
                        .padding(start = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(freqNumber(khz), 26, weight = FontWeight.SemiBold, numeric = true)
                    Txt(" ${freqBand(khz)}", 16, color = p.text2)
                    (fav?.name?.takeIf { it.isNotBlank() } ?: s.radioNames[khz])?.let { Txt("  ·  $it", 16, color = p.text2) }
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(68.dp).clip(CircleShape).clickable { a.radio(RadioCmd.ToggleFavorite(khz)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Ico(if (fav != null) Icons.starFill else Icons.star, 32.dp, if (fav != null) p.accent else p.text2)
                    }
                }
            }
        }
    }
}

/** Nome da favorita: só com o carro parado (o teclado aparece). */
@Composable
private fun RenameDialog(fav: Favorite, onDone: (String) -> Unit, onCancel: () -> Unit) {
    val p = LocalPalette.current
    var text by remember { mutableStateOf(fav.name) }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onCancel), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .padding(top = 40.dp)
                .width(560.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(p.surface)
                .clickable(enabled = false) {}
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Txt("Nome da ${freqNumber(fav.khz)} ${freqBand(fav.khz)}", 24, weight = FontWeight.SemiBold)
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(24) },
                singleLine = true,
                textStyle = TextStyle(color = p.text, fontSize = 26.sp),
                cursorBrush = SolidColor(p.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone(text) }),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface2).padding(16.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(p.surface2).clickable(onClick = onCancel).padding(horizontal = 22.dp),
                    contentAlignment = Alignment.Center,
                ) { Txt("Cancelar", 18, weight = FontWeight.SemiBold) }
                Box(
                    Modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(p.accent).clickable { onDone(text) }.padding(horizontal = 22.dp),
                    contentAlignment = Alignment.Center,
                ) { Txt("Salvar", 18, color = p.onAccent, weight = FontWeight.SemiBold) }
            }
        }
    }
}
