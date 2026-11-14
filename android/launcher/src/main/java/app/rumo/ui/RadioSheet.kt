package app.rumo.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import app.rumo.core.WebFilter
import app.rumo.core.WebRadio
import app.rumo.core.WebStation
import app.rumo.core.WebStatus

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

    // Rádio online
    /** Troca a tela entre FM e Online (e põe a fonte escolhida para tocar). */
    data class Band(val web: Boolean) : RadioCmd()
    data class WebPlay(val station: WebStation) : RadioCmd()
    /** Tocar ou parar a estação online atual. */
    object WebToggle : RadioCmd()
    data class WebStep(val forward: Boolean) : RadioCmd()
    data class WebFavorite(val station: WebStation) : RadioCmd()
    data class WebPickFilter(val filter: WebFilter) : RadioCmd()
    /** O Wi-Fi caiu: a mesma emissora no FM. */
    data class ListenFm(val khz: Int) : RadioCmd()
}

/** Tela de rádio: o rádio do aparelho (FM) ou o rádio pela internet (Online). */
enum class RadioBand { FM, WEB }

/** Rádio online agora: estação, estado e o "tocando agora" que o stream manda. */
data class WebNow(val station: WebStation? = null, val status: WebStatus = WebStatus.OFF, val title: String? = null)

/** Aba Explorar: filtro, lista (já filtrada), consulta em andamento, erro e o estado de "Perto de você". */
data class WebBrowse(
    val filter: WebFilter = WebFilter.NEAR,
    val stations: List<WebStation> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val place: String? = null,
)

/** Linha de baixo do rádio online (card de mídia e tela). */
fun webLine(w: WebNow): String = when (w.status) {
    WebStatus.OFF -> "Parada"
    WebStatus.CONNECTING -> "Conectando…"
    WebStatus.LIVE -> w.title ?: w.station?.let(WebRadio::genre)?.takeIf { it.isNotBlank() }?.let { "$it · ao vivo" } ?: "Ao vivo"
    WebStatus.NO_WIFI -> "Sem Wi-Fi"
    WebStatus.LOST -> "O Wi-Fi caiu · parada"
    WebStatus.ERROR -> "Não conectou na rádio"
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
    var webTab by remember { mutableStateOf(if (s.webFavorites.isEmpty()) 1 else 0) }
    val web = s.radioBand == RadioBand.WEB
    var renaming by remember { mutableStateOf<Favorite?>(null) }
    // Teclado só com o carro parado (derivado: a folha não é refeita a cada posição do GPS, só quando isso muda)
    val parked by remember { derivedStateOf { (s.gps.speedKmh ?: 0) < 5 } }

    Box(Modifier.fillMaxSize().card(p).swallowTouches()) {
        Row(Modifier.fillMaxSize().padding(start = 28.dp, end = 14.dp, top = 14.dp, bottom = 18.dp)) {
            if (web) WebPanel(s, a, Modifier.weight(1f).fillMaxHeight()) else StationPanel(s, a, Modifier.weight(1f).fillMaxHeight())
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1.15f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (web) {
                        TabButton("Favoritas (${s.webFavorites.size})", webTab == 0) { webTab = 0 }
                        TabButton("Explorar", webTab == 1) { webTab = 1 }
                    } else {
                        TabButton("Favoritas (${s.favorites.size})", tab == 0) { tab = 0 }
                        TabButton("Procurar estações", tab == 1) { tab = 1 }
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(76.dp, 64.dp).clip(RoundedCornerShape(20.dp)).background(p.surface2).clickable { s.sheet = Sheet.NONE },
                        contentAlignment = Alignment.Center,
                    ) {
                        Ico(Icons.x, 30.dp, p.text)
                    }
                }
                Spacer(Modifier.height(14.dp))
                when {
                    web && webTab == 0 -> WebFavoritesGrid(s, a)
                    web -> WebExplore(s, a)
                    tab == 0 -> FavoritesGrid(s, a, parked) { renaming = it }
                    else -> ScanList(s, a)
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
        BandSwitch(s, a)
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

/** FM / Online no lugar do título. Toque longo no FM: salva o registro dos comandos do rádio (teste). */
@OptIn(ExperimentalFoundationApi::class) // combinedClickable
@Composable
private fun BandSwitch(s: HomeState, a: HomeActions) {
    val p = LocalPalette.current
    Box(Modifier.height(64.dp), contentAlignment = Alignment.CenterStart) {
        Row(
            Modifier.clip(RoundedCornerShape(20.dp)).background(p.surface2).padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BandButton(Icons.radio, "FM", null, s.radioBand == RadioBand.FM, onLong = { a.radio(RadioCmd.SaveLog) }) {
                a.radio(RadioCmd.Band(web = false))
            }
            BandButton(if (s.online) Icons.globe else Icons.wifi, "Online", if (s.online) null else "sem Wi-Fi", s.radioBand == RadioBand.WEB) {
                a.radio(RadioCmd.Band(web = true))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class) // combinedClickable
@Composable
private fun BandButton(icon: ImageVector, label: String, note: String?, on: Boolean, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) p.accentSoft else Color.Transparent)
            .combinedClickable(onLongClick = onLong, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Ico(icon, 24.dp, if (on) p.accentInk else p.text2)
        Txt(label, 19, color = if (on) p.accentInk else p.text2, weight = FontWeight.SemiBold)
        note?.let { Txt(it, 13, color = p.danger) }
    }
}

/**
 * Estação online: nome, estado (ao vivo, conectando, sem Wi-Fi), lugar e qualidade, o "tocando agora" e os botões.
 * Sem Wi-Fi, se a emissora também está no FM daqui (frequência no nome e com sinal: favorita, achada na procura ou
 * com texto RDS visto), oferece ouvir no FM.
 */
@Composable
private fun WebPanel(s: HomeState, a: HomeActions, modifier: Modifier) {
    val p = LocalPalette.current
    val w = s.web
    val st = w.station
    val fav = st != null && s.webFavorites.any { it.id == st.id }
    val playing = w.status == WebStatus.CONNECTING || w.status == WebStatus.LIVE
    val fm = st?.let { WebRadio.fmKhz(it.name) }?.takeIf { khz ->
        s.favorites.any { it.khz == khz } || s.radioScan?.found?.contains(khz) == true || s.radioNames.containsKey(khz)
    }
    Column(modifier) {
        BandSwitch(s, a)
        Row(Modifier.padding(top = 14.dp).height(112.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(st?.name ?: "Nenhuma estação", 42, weight = FontWeight.SemiBold, maxLines = 2, lineHeight = 1.1f, modifier = Modifier.weight(1f))
            if (st != null) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape).clickable { a.radio(RadioCmd.WebFavorite(st)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Ico(if (fav) Icons.starFill else Icons.star, 40.dp, if (fav) p.accent else p.text2)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusChip(w.status)
            st?.let { Txt(listOf(it.place, WebRadio.quality(it)).filter(String::isNotBlank).joinToString(" · "), 18, color = p.text2) }
        }
        Spacer(Modifier.height(10.dp))
        when {
            !s.online -> Txt("Ligue o Wi-Fi do aparelho numa rede, como o roteador do celular.", 17, color = p.text2, maxLines = 2)
            st == null -> Txt("Escolha uma estação em Explorar.", 17, color = p.text2, maxLines = 2)
            w.status == WebStatus.LIVE && w.title != null -> Txt(w.title, 20, maxLines = 2)
            w.status == WebStatus.ERROR -> Txt("A rádio não respondeu. Tente de novo ou escolha outra.", 17, color = p.text2, maxLines = 2)
        }
        if (!s.online && fm != null) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.height(56.dp).clip(RoundedCornerShape(18.dp)).background(p.surface2)
                    .clickable { a.radio(RadioCmd.ListenFm(fm)) }.padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Ico(Icons.radio, 24.dp, p.text)
                Txt("Ouvir no FM ${freqNumber(fm)}", 17, weight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ControlButton(Icons.prev, "Anterior") { a.radio(RadioCmd.WebStep(false)) }
            Column(
                Modifier.size(180.dp, 96.dp).clip(RoundedCornerShape(24.dp)).background(p.accent)
                    .clickable { a.radio(RadioCmd.WebToggle) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Ico(if (playing) Icons.stop else Icons.play, 34.dp, p.onAccent)
                Txt(if (playing) "Parar" else "Tocar", 14, color = p.onAccent)
            }
            ControlButton(Icons.next, "Próxima") { a.radio(RadioCmd.WebStep(true)) }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            // O catálogo é aberto (CC0) e pede o crédito
            Txt("Lista: Radio Browser", 13, color = p.text3)
        }
    }
}

@Composable
private fun StatusChip(status: WebStatus) {
    val p = LocalPalette.current
    val (text, color, dot) = when (status) {
        WebStatus.LIVE -> Triple("Ao vivo", p.text, true)
        WebStatus.CONNECTING -> Triple("Conectando…", p.text2, false)
        WebStatus.NO_WIFI, WebStatus.LOST -> Triple("Sem Wi-Fi", p.danger, false)
        WebStatus.ERROR -> Triple("Sem resposta", p.danger, false)
        WebStatus.OFF -> Triple("Parada", p.text2, false)
    }
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(p.surface2).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF16A34A)))
        Txt(text, 15, color = color, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun WebFavoritesGrid(s: HomeState, a: HomeActions) {
    val p = LocalPalette.current
    if (s.webFavorites.isEmpty()) {
        Txt("Nenhuma favorita online ainda. Toque na estrela de uma estação em Explorar.", 18, color = p.text2, maxLines = 3)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(s.webFavorites, key = { it.id }) { st ->
            val current = s.web.station?.id == st.id
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (current) p.accentSoft else p.surface2)
                    .then(if (current) Modifier.border(2.dp, p.accent, RoundedCornerShape(20.dp)) else Modifier)
                    .clickable { a.radio(RadioCmd.WebPlay(st)) }
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Txt(st.name, 19, weight = FontWeight.SemiBold, maxLines = 2, lineHeight = 1.15f)
                Txt(WebRadio.subtitle(st), 14, color = p.text2)
            }
        }
    }
}

/** Explorar: filtros e a lista do catálogo (mais ouvidas primeiro). Funciona andando, como a procura do FM. */
@Composable
private fun WebExplore(s: HomeState, a: HomeActions) {
    val p = LocalPalette.current
    val b = s.webBrowse
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WebFilter.entries.forEach { f ->
                val on = f == b.filter
                Box(
                    Modifier
                        .height(52.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (on) p.accentSoft else p.surface2)
                        .then(if (on) Modifier.border(2.dp, p.accent, RoundedCornerShape(18.dp)) else Modifier)
                        .clickable { a.radio(RadioCmd.WebPickFilter(f)) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Txt(f.label, 15, color = if (on) p.accentInk else p.text2, weight = FontWeight.SemiBold)
                }
            }
        }
        val note = when {
            b.loading && b.stations.isEmpty() -> "Buscando estações…"
            b.stations.isEmpty() && !s.online -> "Sem Wi-Fi: a lista aparece quando o aparelho tiver internet."
            b.stations.isEmpty() && b.error != null -> b.error
            b.stations.isEmpty() && b.filter == WebFilter.NEAR && b.place == null -> "Sem a posição do GPS ainda: veja em Todas."
            b.stations.isEmpty() -> "Nenhuma estação neste filtro."
            b.filter == WebFilter.NEAR && b.place != null -> "Estações de ${b.place} e arredores"
            else -> null
        }
        note?.let { Txt(it, 16, color = p.text2, maxLines = 2) }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(b.stations, key = { it.id }) { st ->
                val fav = s.webFavorites.any { it.id == st.id }
                val current = s.web.station?.id == st.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (current) p.accentSoft else p.surface2)
                        .clickable { a.radio(RadioCmd.WebPlay(st)) }
                        .padding(start = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Txt(st.name, 20, weight = FontWeight.SemiBold)
                        Txt(WebRadio.subtitle(st), 15, color = p.text2)
                    }
                    Box(
                        Modifier.size(72.dp).clip(CircleShape).clickable { a.radio(RadioCmd.WebFavorite(st)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Ico(if (fav) Icons.starFill else Icons.star, 32.dp, if (fav) p.accent else p.text2)
                    }
                }
            }
        }
    }
}
