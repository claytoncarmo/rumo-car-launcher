package app.rumo.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.rumo.core.AppRelease
import app.rumo.core.Channel
import app.rumo.core.Favorite
import app.rumo.core.MarkSpot
import app.rumo.core.PT_BR
import app.rumo.core.Radar
import app.rumo.core.RadarAlert
import app.rumo.core.RadarSettings
import app.rumo.core.radarLabel
import app.rumo.core.RoadMatch
import app.rumo.core.UserPoint
import app.rumo.core.compassOf
import app.rumo.core.directionOf
import app.rumo.core.formatDistance
import app.rumo.core.formatDuration
import app.rumo.core.formatKm
import app.rumo.core.formatRadioFreq
import app.rumo.device.AppEntry
import app.rumo.device.Apps
import app.rumo.device.GpsInfo
import app.rumo.device.GpsStatus
import app.rumo.device.Satellites
import app.rumo.device.RadioNow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class Sheet { NONE, APPS, AUDIO, PHONE, RADIO, SETTINGS, KEYS, MARK }

/**
 * De onde vem o som: pelo serviço sourceinfo do multimídia (avisa a cada troca de fonte, inclusive pelo volante)
 * ou, sem ele, estimado pelo último app de áudio que foi para a tela. Nem o ZLINK nem o rádio informam o que tocam.
 */
enum class Source { NONE, ZLINK, RADIO, OTHER }

/** Pasta Rumo (mapa, vias e radares): procurando, sem permissão para ler, não encontrada, pronta. */
enum class MapStatus { LOADING, NO_PERMISSION, MISSING, READY }

/** Estado da tela inicial: o HomeActivity alimenta; a interface lê e troca a folha aberta. */
class HomeState {
    var now by mutableStateOf(LocalDateTime.now())
    var gps by mutableStateOf(GpsInfo())
    var theme by mutableStateOf(ThemeMode.AUTO)
    /** Farol aceso (serviço carstatus do multimídia); null se o aparelho não informa. */
    var headlight by mutableStateOf<Boolean?>(null)
    /** Resultado do teste do farol: o que mudou no sistema. */
    var probe by mutableStateOf<List<String>?>(null)
    var probeTitle by mutableStateOf("Teste do farol")
    var mapStatus by mutableStateOf(MapStatus.LOADING)
    /** Um map.pmtiles por estado instalado (vazio: sem mapa) e a pasta das fontes dos nomes de rua. */
    var mapFiles by mutableStateOf<List<File>>(emptyList())
    var fontsDir by mutableStateOf<File?>(null)
    /** Radares e outros pontos de atenção dos estados instalados, desenhados no mapa. */
    var radars by mutableStateOf<List<Radar>>(emptyList())
    /** Pontos marcados pelo usuário, como radares (no mapa, numa fonte à parte). */
    var mineRadars by mutableStateOf<List<Radar>>(emptyList())
    /** Pontos marcados pelo usuário (só neste aparelho) e o lugar do "+" que abriu a folha de marcar. */
    var myPoints by mutableStateOf<List<UserPoint>>(emptyList())
    var markSpot by mutableStateOf<MarkSpot?>(null)
    var mapStart by mutableStateOf<MapStart?>(null)
    var road by mutableStateOf<RoadMatch?>(null)
    var radar by mutableStateOf<RadarAlert?>(null)
    var over by mutableStateOf(false)
    var apps by mutableStateOf<List<AppEntry>>(emptyList())
    var source by mutableStateOf(Source.NONE)
    var usageAccess by mutableStateOf(false)
    /** A fonte vem do serviço sourceinfo do multimídia (certa); sem ele, é estimada pelo último app aberto. */
    var sourceLive by mutableStateOf(false)
    /** Estação do rádio (serviço fmradio do sistema): frequência, memória e texto RDS. */
    var radio by mutableStateOf<RadioNow?>(null)
    /** Favoritas do Rumo (lista própria, com nomes). */
    var favorites by mutableStateOf<List<Favorite>>(emptyList())
    var radioScan by mutableStateOf<RadioScan?>(null)
    /** Nomes (RDS) vistos por frequência nas últimas horas, de todas as estações. */
    var radioNames by mutableStateOf<Map<Int, String>>(emptyMap())
    /** O play do card parou o rádio (mudo do fmradio). */
    var radioMuted by mutableStateOf(false)
    /** Memórias do aparelho (kHz), para trazer para as favoritas. */
    var devicePresets by mutableStateOf<List<Int>>(emptyList())
    /** Registro dos comandos do rádio (teste): sai no relatório com toque longo no título da tela de rádio. */
    var radioLog by mutableStateOf<List<String>>(emptyList())
    /** Há assistente de voz instalado: sem ele, o botão do microfone some. */
    var hasVoice by mutableStateOf(false)
    var sheet by mutableStateOf(Sheet.NONE)
    /**
     * Controle de volume flutuante (arrastar o dedo no menu da esquerda para a direita): aberto, nível do volume de
     * música e contador de toques, que reinicia a contagem para recolher sozinho.
     */
    var volumeOpen by mutableStateOf(false)
    var volume by mutableIntStateOf(0)
    var volumeMax by mutableIntStateOf(1)
    var volumeTick by mutableIntStateOf(0)
    /** Teste dos botões: as teclas que o Android entregou ao Rumo, a mais nova primeiro. */
    var keyLog by mutableStateOf<List<String>>(emptyList())
    var toast by mutableStateOf<String?>(null)
    /** Versão instalada (nome e código), para comparar com as publicadas. */
    var appVersion = ""
    var appCode = 0L
    /** Configurações da home: o que a consulta às releases achou e o download em andamento (app ou dados). */
    var settings by mutableStateOf(SettingsState())
    /** Satélites do GPS (só o aviso "procurando satélites" mostra): à parte, para não redesenhar o mapa a cada mudança. */
    var satellites by mutableStateOf(Satellites())
    /** O aparelho tem rede (Wi-Fi ou dados): quando volta, a aba Atualizações consulta de novo se estava sem conexão. */
    var online by mutableStateOf(true)
    /** Avisos de radar: distância (m) em que começam e margem (km/h) acima do limite do radar antes de alertar. */
    var radarZoneM by mutableIntStateOf(RadarSettings.ZONE_DEFAULT)
    var radarMarginKmh by mutableIntStateOf(RadarSettings.MARGIN_DEFAULT)
    /** Menu da engrenagem aberto (configurações da home ou do Android). */
    var settingsMenu by mutableStateOf(false)
    /**
     * Versão mais nova que a instalada, no canal da instalada (estável só avisa estável; nightly, nightly): acende o
     * ícone ao lado do tema. [updateOpen]: o popup com as novidades está aberto.
     */
    var update by mutableStateOf<AppRelease?>(null)
    var updateOpen by mutableStateOf(false)
    var transfer by mutableStateOf<Transfer?>(null)
}

interface HomeActions {
    fun openZlink()
    fun openRadio()
    fun openRadioScreen()
    fun radio(cmd: RadioCmd)
    fun openApp(app: AppEntry)
    fun appDetails(app: AppEntry)
    fun uninstall(app: AppEntry)
    fun askUsageAccess()
    fun mediaKey(keyCode: Int)
    fun voice()
    /** Abre os ajustes do Android. */
    fun openAndroidSettings()
    fun askLocation()
    fun askFiles()
    fun chooseTheme(mode: ThemeMode)
    fun probeHeadlight()
    fun probeMedia()
    /** Configurações da home (só com o carro parado): consulta as versões e os dados ao abrir. */
    fun openSettings()
    fun checkUpdates()
    fun installApp(channel: Channel)
    /** Abre o popup das novidades da versão nova (só com o carro parado). */
    fun openUpdate()
    /** Baixa a versão nova do aviso e abre o instalador do Android. */
    fun installUpdate()
    /** Marca ou desmarca um estado na lista de dados do mapa (só muda a escolha; [applyData] baixa e remove). */
    fun toggleRegion(id: String)
    /** Baixa os estados marcados que faltam ou estão desatualizados e remove os desmarcados. */
    fun applyData()
    fun cancelTransfer()
    /** Reinicia o Android (só parado): permissão/root se houver; senão pelo menu de energia (serviço de acessibilidade). */
    fun reboot()
    fun accessibilitySettings()
    /** Volume de música: [delta] positivo sobe um passo, negativo desce, 0 só lê o nível para a tela. */
    fun volume(delta: Int)
    /** Teste dos botões: liga/desliga o registro das teclas e dos avisos de tela enquanto a folha está aberta. */
    fun keyTest(on: Boolean)
    /** Diagnóstico: procura ganchos de reinício do fabricante (reboot-test.txt). */
    fun probeReboot()
    /** Toca o aviso de radar e os bipes (do longe ao perto) por uns segundos; se o som falhar, mostra o erro. */
    fun testSound()
    /** Distância (m) em que o aviso de radar começa; guardada nas preferências. */
    fun setRadarZone(meters: Int)
    /** Margem (km/h) acima do limite do radar antes do alerta sonoro; guardada nas preferências. */
    fun setRadarMargin(kmh: Int)
    /** Guarda um ponto no lugar de [spot] (radar, lombada…); [bothWays] ignora o sentido do carro. */
    fun addPoint(spot: MarkSpot, kind: Int, limit: Int, bothWays: Boolean)
    /** Apaga o ponto [index] da lista dos pontos do usuário. */
    fun removePoint(index: Int)
    /** Troca o ponto [index] (tipo, limite ou sentido editados), no mesmo lugar da lista. */
    fun updatePoint(index: Int, point: UserPoint)
    /** Move o ponto [index] [meters] ao longo da rua (negativo anda para trás). */
    fun movePoint(index: Int, meters: Int)
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DATE = DateTimeFormatter.ofPattern("EEE, d 'de' MMM", PT_BR)
private val DOCK_WIDTH = 112.dp
private const val VOLUME_DRAG_DP = 56
private const val VOLUME_HIDE_MS = 4_000L
private const val VOLUME_REPEAT_AFTER_MS = 400L
private const val VOLUME_REPEAT_MS = 160L
private val CARD_RADIUS = 28.dp
private val CARD = RoundedCornerShape(CARD_RADIUS)

/**
 * Folhas por cima dos cards (apps, rádio, testes): segura o toque nas partes sem botão, que antes "atravessava" e
 * abria o ZLINK pelo card do mapa embaixo.
 */
@Composable
internal fun Modifier.swallowTouches(): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}

internal fun Modifier.card(p: Palette, color: Color = p.surface) =
    clip(CARD).background(color).border(1.dp, p.line, CARD)

@Composable
fun HomeScreen(s: HomeState, a: HomeActions) {
    LaunchedEffect(Unit) {
        while (true) {
            s.now = LocalDateTime.now()
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    LaunchedEffect(s.toast) {
        if (s.toast != null) {
            delay(3_500)
            s.toast = null
        }
    }
    // O mapa (MapLibre: biblioteca nativa, contexto GL, estilo) é o que mais pesa para abrir. Só entra depois que o
    // primeiro quadro foi desenhado: a tela inicial aparece logo e o mapa chega em seguida (antes, os dados costumavam
    // ficar prontos antes da primeira composição e o mapa entrava nela, atrasando o primeiro quadro)
    var mapAllowed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        mapAllowed = true
    }
    // Na tela inicial, Voltar só fecha a folha aberta
    BackHandler {
        s.sheet = Sheet.NONE
        s.volumeOpen = false
        s.settingsMenu = false
        s.updateOpen = false
    }

    val night = s.theme.night(s.now.hour, isSystemInDarkTheme(), s.headlight)
    val p = if (night) Night else Day
    CompositionLocalProvider(LocalPalette provides p) {
        Box(Modifier.fillMaxSize().background(p.bg)) {
            Row(Modifier.fillMaxSize()) {
                Dock(s, a)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    StatusBar(s, a)
                    Box(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        HomeCards(s, a, night, mapAllowed)
                        when (s.sheet) {
                            Sheet.NONE -> {}
                            Sheet.RADIO -> RadioSheet(s, a)
                            Sheet.SETTINGS -> SettingsSheet(s, a)
                            Sheet.KEYS -> KeyTestSheet(s, a)
                            Sheet.MARK -> MarkSheet(s, a)
                            else -> AppSheet(s, a)
                        }
                        s.probe?.let { ProbeSheet(s.probeTitle, it) { s.probe = null } }
                    }
                }
            }
            if (s.volumeOpen) VolumeControl(s, a, Modifier.align(Alignment.CenterStart).padding(start = DOCK_WIDTH + 12.dp))
            if (s.settingsMenu) SettingsMenu(s, a)
            if (s.updateOpen) UpdatePopup(s, a)
            s.toast?.let { Toast(it, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

/**
 * Menu da engrenagem: as configurações da home (versão, atualização, mapas, diagnósticos) ou as do Android. Tocar fora
 * fecha. Fica por cima de tudo, então o toque que o fecha não chega ao que estiver embaixo.
 */
@Composable
private fun SettingsMenu(s: HomeState, a: HomeActions) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                s.settingsMenu = false
            },
        )
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 68.dp, end = 20.dp).width(440.dp).card(p).swallowTouches().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SettingsMenuItem(Icons.home, "Configurações da home", "Versão, atualização, mapas e diagnósticos") {
                s.settingsMenu = false
                a.openSettings()
            }
            SettingsMenuItem(Icons.gear, "Configurações do sistema", "Wi-fi, Bluetooth, som e o resto do sistema") {
                s.settingsMenu = false
                a.openAndroidSettings()
            }
        }
    }
}

@Composable
private fun SettingsMenuItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(p.surface2), contentAlignment = Alignment.Center) {
            Ico(icon, 30.dp, p.text)
        }
        Column(Modifier.weight(1f)) {
            Txt(title, 22, weight = FontWeight.SemiBold)
            Txt(subtitle, 15, color = p.text2, maxLines = 2)
        }
    }
}

/**
 * Controle de volume grande, flutuante, para acertar dirigindo (as teclas do sistema são pequenas). Segurar repete.
 * Recolhe sozinho depois de [VOLUME_HIDE_MS] sem toque.
 */
@Composable
private fun VolumeControl(s: HomeState, a: HomeActions, modifier: Modifier) {
    val p = LocalPalette.current
    LaunchedEffect(s.volumeTick) {
        delay(VOLUME_HIDE_MS)
        s.volumeOpen = false
    }
    Column(
        modifier.width(132.dp).card(p).swallowTouches().padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Número e barra em cima e os dois botões embaixo: o dedo no "+" não tampa o volume
        Txt(s.volume.toString(), 44, weight = FontWeight.Bold, numeric = true, lineHeight = 1f)
        val fraction = if (s.volumeMax > 0) (s.volume.toFloat() / s.volumeMax).coerceIn(0f, 1f) else 0f
        Box(Modifier.size(14.dp, 110.dp).clip(RoundedCornerShape(7.dp)).background(p.surface3), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(fraction).background(p.accent))
        }
        Txt("de ${s.volumeMax}", 14, color = p.text3)
        VolumeButton(Icons.plus, 1, s, a)
        VolumeButton(Icons.minus, -1, s, a)
    }
}

@Composable
private fun VolumeButton(icon: ImageVector, delta: Int, s: HomeState, a: HomeActions) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(108.dp, 104.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(p.surface2)
            .pointerInput(delta) {
                detectTapGestures(onPress = {
                    a.volume(delta)
                    s.volumeTick++
                    // Segurando, repete depois de um instante
                    coroutineScope {
                        val repeat = launch {
                            delay(VOLUME_REPEAT_AFTER_MS)
                            while (true) {
                                a.volume(delta)
                                s.volumeTick++
                                delay(VOLUME_REPEAT_MS)
                            }
                        }
                        tryAwaitRelease()
                        repeat.cancel()
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Ico(icon, 44.dp, p.text)
    }
}

@Composable
private fun Dock(s: HomeState, a: HomeActions) {
    val p = LocalPalette.current
    Column(
        Modifier
            .width(DOCK_WIDTH)
            .fillMaxHeight()
            .drawBehind { drawLine(p.line, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f) }
            // Arrastar o dedo para a direita no menu abre o controle de volume
            .pointerInput(Unit) {
                val threshold = VOLUME_DRAG_DP.dp.toPx()
                var total = 0f
                var opened = false
                detectHorizontalDragGestures(
                    onDragStart = {
                        total = 0f
                        opened = false
                    },
                    onHorizontalDrag = { _, dx ->
                        total += dx
                        if (!opened && total > threshold) {
                            opened = true
                            s.volumeOpen = true
                            s.volumeTick++
                            a.volume(0)
                        }
                    },
                )
            }
            .padding(top = 14.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DockItem(Icons.home, "Início", s.sheet == Sheet.NONE) { s.sheet = Sheet.NONE }
        DockItem(Icons.cast, "Android Auto", false) { a.openZlink() }
        DockItem(Icons.radio, "Rádio", s.sheet == Sheet.RADIO) { a.openRadioScreen() }
        DockItem(Icons.music, "Música", s.sheet == Sheet.AUDIO) { s.sheet = Sheet.AUDIO }
        DockItem(Icons.phone, "Telefone", s.sheet == Sheet.PHONE) { s.sheet = Sheet.PHONE }
        DockItem(Icons.apps, "Apps", s.sheet == Sheet.APPS) { s.sheet = Sheet.APPS }
        Spacer(Modifier.weight(1f))
        if (s.hasVoice) {
            Box(
                Modifier.size(80.dp).clip(CircleShape).background(p.accent).clickable { a.voice() },
                contentAlignment = Alignment.Center,
            ) {
                Ico(Icons.mic, 34.dp, p.onAccent)
            }
        }
    }
}

@Composable
private fun DockItem(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val color = if (on) p.accentInk else p.text2
    Column(
        Modifier
            .size(92.dp, 88.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (on) p.surface2 else Color.Transparent)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Ico(icon, 32.dp, color)
        Txt(label, 15, color = color, weight = FontWeight.SemiBold, maxLines = 2, align = TextAlign.Center, lineHeight = 1.05f)
    }
}

@OptIn(ExperimentalFoundationApi::class) // combinedClickable
@Composable
private fun StatusBar(s: HomeState, a: HomeActions) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(start = 22.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(s.now.format(TIME), 36, weight = FontWeight.SemiBold, numeric = true, lineHeight = 1f)
        Spacer(Modifier.width(12.dp))
        Txt(s.now.format(DATE), 18, color = p.text2)
        Spacer(Modifier.weight(1f))
        // Versão nova publicada: toque abre as novidades
        if (s.update != null) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(p.accent).clickable { a.openUpdate() },
                contentAlignment = Alignment.Center,
            ) {
                Ico(Icons.download, 28.dp, p.onAccent)
            }
            Spacer(Modifier.width(8.dp))
        }
        // Tema: automático → escuro → claro. Toque longo: teste do farol
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .combinedClickable(onLongClick = { a.probeHeadlight() }) { a.chooseTheme(s.theme.next()) },
            contentAlignment = Alignment.Center,
        ) {
            val icon = when (s.theme) {
                ThemeMode.AUTO -> Icons.themeAuto
                ThemeMode.DARK -> Icons.moon
                ThemeMode.LIGHT -> Icons.sun
            }
            Ico(icon, 28.dp, p.text)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).clickable { s.settingsMenu = !s.settingsMenu },
            contentAlignment = Alignment.Center,
        ) {
            Ico(Icons.gear, 28.dp, p.text)
        }
    }
}

/** Layout "Mapa em foco" do protótipo: mapa à esquerda; mídia e cartão inteligente à direita. */
@Composable
private fun HomeCards(s: HomeState, a: HomeActions, night: Boolean, mapAllowed: Boolean) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MapCard(s, a, night, mapAllowed, Modifier.weight(1.6f).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Linha de baixo no rádio: nome dado na favorita, texto da emissora, memória; "Rádio parado" no mudo
            val radioLine = if (s.radioMuted) {
                "Rádio parado"
            } else {
                val r = s.radio
                listOfNotNull(
                    r?.let { now -> s.favorites.firstOrNull { it.khz == now.khz }?.name?.takeIf(String::isNotBlank) },
                    r?.text,
                    r?.preset?.let { "Memória ${it + 1}" },
                ).distinct().joinToString(" · ")
            }
            MediaCard(s.source, s.radio, radioLine, s.usageAccess || s.sourceLive, a, Modifier.weight(1.12f).fillMaxWidth())
            TripCard(s.gps, s.over, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/**
 * Mapa passivo: rua atual, placa de limite (à direita) e radar à frente. A velocidade acima do limite fica em
 * vermelho só no cartão Viagem.
 * Tocar em qualquer ponto abre o Android Auto. Sem a pasta Rumo, fica a seta no rumo do GPS.
 */
@Composable
private fun MapCard(s: HomeState, a: HomeActions, night: Boolean, mapAllowed: Boolean, modifier: Modifier) {
    val p = LocalPalette.current
    val g = s.gps
    val mapFiles = s.mapFiles
    val hasMap = mapFiles.isNotEmpty()
    val road = s.road
    Box(modifier.card(p, p.mapBlock)) {
        s.fontsDir?.let { fonts -> if (hasMap && mapAllowed) DriveMap(mapFiles, fonts, s.radars, s.mineRadars, g, s.mapStart, night, active = s.sheet == Sheet.NONE) }
        val fade = p.surface.copy(alpha = 0.92f)
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(0f to fade, 0.26f to Color.Transparent, 0.6f to Color.Transparent, 1f to fade))
                .clickable { a.openZlink() },
        )
        // O mapa é desenhado por baixo da janela e não respeita o recorte: os cantos arredondados são pintados por cima
        Canvas(Modifier.matchParentSize()) {
            val r = CARD_RADIUS.toPx()
            val corners = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(r)))
            }
            drawPath(corners, p.bg)
        }
        // Com o mapa, a frente do carro fica para cima (o mapa gira); sem ele, a seta gira
        Box(
            Modifier
                .align(if (hasMap) BiasAlignment(0f, 2 * CAR_Y - 1) else Alignment.Center)
                .size(68.dp)
                .clip(CircleShape)
                .background(if (hasMap) p.accent else p.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Ico(
                Icons.nav, 42.dp,
                if (hasMap) p.onAccent else p.text,
                Modifier.rotate(if (hasMap) 0f else g.bearing ?: 0f),
            )
        }
        Column(
            Modifier.align(Alignment.TopStart).padding(start = 22.dp, top = 18.dp, end = 230.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // Parado, o GPS não dá o rumo
            val heading = when {
                g.status != GpsStatus.OK -> "Aguardando o GPS"
                g.bearing != null && (g.speedKmh ?: 0) > 0 -> "Sentido ${directionOf(g.bearing)}"
                else -> "Parado"
            }
            Txt(heading, 14, color = p.text2, weight = FontWeight.SemiBold, caps = true)
            when {
                s.mapStatus == MapStatus.NO_PERMISSION ->
                    Txt(
                        "Toque para liberar a leitura do mapa", 22, color = p.accentInk, weight = FontWeight.SemiBold, maxLines = 2,
                        modifier = Modifier.clickable { a.askFiles() },
                    )
                s.mapStatus == MapStatus.MISSING ->
                    Txt(
                        "Sem dados do mapa. Toque para baixar", 22, color = p.accentInk, weight = FontWeight.SemiBold, maxLines = 2,
                        modifier = Modifier.clickable { a.openSettings() },
                    )
                road != null -> Txt(road.way.label, 28, weight = FontWeight.SemiBold, lineHeight = 1.1f)
                g.status == GpsStatus.OK && s.mapStatus == MapStatus.READY ->
                    Txt("Fora das vias do mapa", 22, color = p.text2, weight = FontWeight.SemiBold)
            }
        }
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(p.surface2)
                .clickable { a.openZlink() }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Ico(Icons.cast, 24.dp, p.text)
            Txt("Android Auto", 18, weight = FontWeight.SemiBold)
        }
        // Marcar um radar que falta: abaixo do atalho do Android Auto; guarda o lugar onde se tocou. A área de toque
        // (88 dp) é bem maior que o círculo (64 dp): dirigindo, quem erra o "+" não deve cair em outro lugar
        val lat = g.lat
        val lon = g.lon
        if (g.status == GpsStatus.OK && lat != null && lon != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 14.dp + 56.dp, end = 2.dp)
                    .size(88.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        s.markSpot = MarkSpot.of(lat, lon, g.bearing, g.speedKmh, road?.limit)
                        s.sheet = Sheet.MARK
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(p.surface2.copy(alpha = 0.8f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Ico(Icons.plus, 32.dp, p.text2)
                }
            }
        }
        // A placa de limite fica na direita (a mão do motorista tampava a da esquerda); o radar, na esquerda
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp)) {
            if (g.status != GpsStatus.OK) {
                GpsChip(g, s.satellites, a, Modifier.align(Alignment.CenterStart))
            } else {
                s.radar?.let { RadarPill(it, Modifier.align(Alignment.CenterStart).padding(end = SIGN_SIZE + 12.dp)) }
                road?.let { LimitSign(it.limit, it.estimated, Modifier.align(Alignment.CenterEnd)) }
            }
        }
        if (hasMap) {
            Txt("© OpenStreetMap", 11, color = p.text3, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 4.dp))
        }
    }
}

private val SIGN_RED = Color(0xFFD7262E)
private val SIGN_SIZE = 96.dp

/** Placa R-19 (velocidade máxima). Estimado = padrão do CTB para o tipo de via, sem o limite no OSM. */
@Composable
private fun LimitSign(limit: Int, estimated: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier.size(SIGN_SIZE).clip(CircleShape).background(Color.White).border(10.dp, SIGN_RED, CircleShape),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Txt(limit.toString(), 40, color = Color(0xFF111111), weight = FontWeight.Bold, numeric = true, lineHeight = 1f)
        Txt(if (estimated) "estimado" else "km/h", 12, color = Color(0xFF111111), weight = FontWeight.SemiBold, lineHeight = 1f)
    }
}

/** "Reduza · radar 60 km/h" acima do limite; "Lombada" ou "Radar · 60 km/h" nos demais. */
private fun radarText(r: RadarAlert): String {
    val label = radarLabel(r.radar.kind)
    val limit = if (r.radar.limit > 0) " · ${r.radar.limit} km/h" else ""
    return if (r.alarm) "Reduza · ${label.lowercase(PT_BR)}$limit" else "$label$limit"
}

/** Radar à frente: distância e limite; vermelho quando acima do limite dele (os bipes tocam junto). */
@Composable
private fun RadarPill(r: RadarAlert, modifier: Modifier) {
    val p = LocalPalette.current
    val ink = if (r.alarm) Color.White else p.text
    Row(
        modifier
            .height(96.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(if (r.alarm) p.danger else p.surface2)
            .padding(start = 14.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(if (r.alarm) Color.White else Color(0xFFFACC15)),
            contentAlignment = Alignment.Center,
        ) {
            Ico(Icons.speedCamera, 34.dp, if (r.alarm) p.danger else Color(0xFF1A1400))
        }
        Column {
            Txt(formatDistance(r.distanceM), 36, color = ink, weight = FontWeight.Bold, numeric = true, lineHeight = 1f)
            Txt(
                radarText(r),
                17, color = if (r.alarm) Color.White else p.text2,
            )
        }
    }
}

@Composable
private fun GpsChip(g: GpsInfo, sats: Satellites, a: HomeActions, modifier: Modifier) {
    val p = LocalPalette.current
    val text = when (g.status) {
        GpsStatus.NO_PERMISSION -> "Toque para liberar a localização"
        GpsStatus.OFF -> "GPS desligado"
        GpsStatus.SEARCHING -> "Procurando satélites · ${sats.used}/${sats.seen}"
        GpsStatus.OK -> "GPS ±${g.accuracyM ?: "?"} m · ${sats.used} satélites"
    }
    Row(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(p.surface2)
            .then(if (g.status == GpsStatus.NO_PERMISSION) Modifier.clickable { a.askLocation() } else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Ico(Icons.pin, 24.dp, if (g.status == GpsStatus.OK) p.accentInk else p.text2)
        Txt(text, 17, color = p.text2)
    }
}

/**
 * Nem o ZLINK nem o rádio informam a faixa: o card mostra a fonte (do serviço sourceinfo ou estimada), abre o app
 * dela e manda teclas de mídia. [detected]: a fonte já é conhecida sem pedir o Acesso ao uso.
 * No rádio só anterior/próxima (trocam as estações salvas); o play/pausa lá já gravou estações sem querer.
 */
@OptIn(ExperimentalFoundationApi::class) // combinedClickable
@Composable
private fun MediaCard(source: Source, radio: RadioNow?, radioLine: String, detected: Boolean, a: HomeActions, modifier: Modifier) {
    val p = LocalPalette.current
    val known = source == Source.ZLINK || source == Source.RADIO
    val icon = when (source) {
        Source.ZLINK -> Icons.cast
        Source.RADIO -> Icons.radio
        else -> Icons.music
    }
    val (label, title, hint) = when (source) {
        Source.ZLINK -> Triple("Android Auto", "Música do celular", "Os botões controlam o ZLINK")
        // Frequência e memória; o texto RDS só quando a emissora manda (o próprio app de rádio não mostra nomes)
        Source.RADIO -> Triple(
            "Rádio",
            radio?.khz?.let(::formatRadioFreq) ?: "Rádio do aparelho",
            radioLine,
        )
        Source.OTHER -> Triple("Mídia", "Outro app de áudio", "Abra o Android Auto ou o rádio para usar os botões")
        Source.NONE -> Triple("Mídia", "Nenhuma fonte", "Abra o Android Auto ou o rádio para usar os botões")
    }
    Column(
        modifier
            .card(p)
            // Toque longo: teste da mídia (o que o serviço sourceinfo informa)
            .combinedClickable(onLongClick = { a.probeMedia() }) { if (source == Source.RADIO) a.openRadioScreen() else a.openZlink() }
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(132.dp).clip(RoundedCornerShape(20.dp)).background(p.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Ico(icon, 56.dp, p.accentInk)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(if (known) p.accent else p.text3))
                    Txt(label, 15, color = p.text2, weight = FontWeight.SemiBold, caps = true)
                }
                Txt(title, 27, weight = FontWeight.SemiBold, maxLines = 2)
                Txt(hint, 18, color = p.text2, maxLines = 3)
            }
        }
        Spacer(Modifier.weight(1f))
        if (!detected) {
            Box(
                Modifier.height(44.dp).clip(RoundedCornerShape(14.dp)).clickable { a.askUsageAccess() }.padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Txt("Detectar a fonte automaticamente ›", 16, color = p.accentInk, weight = FontWeight.SemiBold)
            }
        }
        // Os três botões dividem a largura toda, sem vãos: tocar perto do botão não abre a tela da fonte
        Row(Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.CenterVertically) {
            MediaButton(Icons.prev, known, Modifier.weight(1f)) { a.mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
            Box(
                Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { a.mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) },
                contentAlignment = Alignment.Center,
            ) {
                // No rádio, o play pede para ele tocar (não manda tecla: o play/pausa lá gravava estações)
                Box(
                    Modifier
                        .size(108.dp, 76.dp)
                        .alpha(if (known) 1f else DISABLED)
                        .clip(RoundedCornerShape(38.dp))
                        .background(p.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Ico(Icons.playPause, 38.dp, p.onAccent)
                }
            }
            MediaButton(Icons.next, known, Modifier.weight(1f)) { a.mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
        }
    }
}

private const val DISABLED = 0.35f

@Composable
private fun MediaButton(icon: ImageVector, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxHeight().alpha(if (enabled) 1f else DISABLED).clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Ico(icon, 38.dp, LocalPalette.current.text)
    }
}

/** Página "Viagem" do cartão inteligente; as outras páginas (sugestões, clima, favoritos) vêm depois. */
@Composable
private fun TripCard(g: GpsInfo, over: Boolean, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier.card(p).padding(start = 20.dp, end = 20.dp, bottom = 14.dp)) {
        Box(Modifier.height(48.dp), contentAlignment = Alignment.CenterStart) {
            Txt("Viagem", 15, color = p.text2, weight = FontWeight.SemiBold, caps = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(
                    g.speedKmh?.toString() ?: "--", 96, color = if (over) p.danger else p.text,
                    weight = FontWeight.SemiBold, numeric = true, lineHeight = 1f,
                )
                Txt("km/h", 20, color = p.text2, modifier = Modifier.padding(bottom = 14.dp))
            }
            Box(Modifier.size(72.dp).border(3.dp, p.surface3, CircleShape), contentAlignment = Alignment.Center) {
                Txt(g.bearing?.let { compassOf(it) } ?: "--", 24, weight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(formatKm(g.tripMeters), "distância", Modifier.weight(1f))
            Stat(formatDuration(g.tripMovingMs), "em movimento", Modifier.weight(1f))
            Stat(g.tripAverageKmh?.let { "$it km/h" } ?: "--", "média", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(p.surface2).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Txt(value, 24, weight = FontWeight.SemiBold, numeric = true)
        Txt(label, 14, color = p.text2)
    }
}

/** Folha sobre os cards: todos os apps, ou só os de música ou de telefone (reconhecidos pelo nome). */
@Composable
private fun AppSheet(s: HomeState, a: HomeActions) {
    val p = LocalPalette.current
    val (title, filtered) = when (s.sheet) {
        Sheet.AUDIO -> "Música" to s.apps.filter(Apps::isAudio).sortedByDescending { it.pkg == Apps.ZLINK }
        Sheet.PHONE -> "Telefone" to s.apps.filter(Apps::isPhone)
        else -> "Apps" to s.apps
    }
    // Sem nenhum app reconhecido, mostra todos para a folha não ficar vazia
    val list = filtered.ifEmpty { s.apps }
    Column(Modifier.fillMaxSize().card(p).swallowTouches()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(title, 30, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(76.dp, 64.dp).clip(RoundedCornerShape(20.dp)).background(p.surface2).clickable { s.sheet = Sheet.NONE },
                contentAlignment = Alignment.Center,
            ) {
                Ico(Icons.x, 30.dp, p.text)
            }
        }
        if (filtered.isEmpty() && s.sheet != Sheet.APPS) {
            Txt("Nenhum app reconhecido pelo nome; mostrando todos.", 17, color = p.text2, modifier = Modifier.padding(horizontal = 28.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(124.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(list, key = { it.pkg + "/" + it.activity }) { app -> AppTile(app, a) }
        }
    }
}

/** Toque abre o app; toque longo abre o menu com detalhes e desinstalar. */
@OptIn(ExperimentalFoundationApi::class) // combinedClickable
@Composable
private fun AppTile(app: AppEntry, a: HomeActions) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .combinedClickable(onLongClick = { menu = true }) { a.openApp(app) }
                .padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(app.icon, contentDescription = null, modifier = Modifier.size(80.dp))
            Txt(app.label, 17, weight = FontWeight.SemiBold, align = TextAlign.Center)
        }
        if (menu) AppMenu(app, a) { menu = false }
    }
}

/** Menu sobre o ícone, logo abaixo dele. Apps do aparelho não mostram "Desinstalar". */
@Composable
private fun AppMenu(app: AppEntry, a: HomeActions, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val below = with(LocalDensity.current) { 100.dp.roundToPx() }
    val shape = RoundedCornerShape(22.dp)
    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, below),
        onDismissRequest = onDismiss,
        // Sem foco: a janela do menu não traz de volta a barra do sistema
        properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
    ) {
        Column(Modifier.width(300.dp).clip(shape).background(p.surface).border(1.dp, p.line, shape).padding(8.dp)) {
            MenuItem(Icons.info, "Detalhes do app") {
                onDismiss()
                a.appDetails(app)
            }
            if (!app.system) {
                MenuItem(Icons.trash, "Desinstalar", p.danger) {
                    onDismiss()
                    a.uninstall(app)
                }
            }
        }
    }
}

@Composable
internal fun MenuItem(icon: ImageVector, label: String, color: Color = LocalPalette.current.text, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Ico(icon, 26.dp, color)
        Txt(label, 19, color = color, weight = FontWeight.SemiBold)
    }
}

/** Resultado do teste do farol, para fotografar e mandar: cada linha é um ajuste que mudou. */
@Composable
private fun ProbeSheet(title: String, lines: List<String>, onClose: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().card(p).swallowTouches()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(title, 26, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(76.dp, 64.dp).clip(RoundedCornerShape(20.dp)).background(p.surface2).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Ico(Icons.x, 30.dp, p.text)
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 28.dp, end = 28.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (line in lines) Txt(line, 17, color = p.text2, maxLines = 3, lineHeight = 1.25f)
        }
    }
}

@Composable
private fun Toast(text: String, modifier: Modifier) {
    val p = LocalPalette.current
    Box(
        modifier
            .padding(bottom = 26.dp)
            .widthIn(max = 900.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(p.text)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Txt(text, 19, color = p.surface, weight = FontWeight.SemiBold, maxLines = 3)
    }
}
