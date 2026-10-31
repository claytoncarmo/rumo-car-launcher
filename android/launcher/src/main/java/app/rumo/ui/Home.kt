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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.rumo.core.Channel
import app.rumo.core.Favorite
import app.rumo.core.PT_BR
import app.rumo.core.RadarAlert
import app.rumo.core.RoadMatch
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
import app.rumo.device.RadioNow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class Sheet { NONE, APPS, AUDIO, PHONE, RADIO, ABOUT, KEYS }

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
    var mapFile by mutableStateOf<File?>(null)
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
    /** Registro dos comandos do rádio (teste): a última linha aparece na tela de rádio. */
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
    /** Painel Rumo: o que a consulta às releases achou e o download em andamento (app ou dados). */
    var about by mutableStateOf(AboutState())
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
    fun systemSettings()
    fun askLocation()
    fun askFiles()
    fun chooseTheme(mode: ThemeMode)
    fun probeHeadlight()
    fun probeMedia()
    /** Painel Rumo (só com o carro parado): consulta as versões e os dados ao abrir. */
    fun openAbout()
    fun checkUpdates()
    fun installApp(channel: Channel)
    fun downloadData()
    fun cancelTransfer()
    /** Reinicia o Android (só parado): permissão/root se houver; senão pelo menu de energia (serviço de acessibilidade). */
    fun reboot()
    fun accessibilitySettings()
    /** Volume de música: [delta] positivo sobe um passo, negativo desce, 0 só lê o nível para a tela. */
    fun volume(delta: Int)
    /** Teste dos botões: liga/desliga o registro das teclas e dos avisos de tela enquanto a folha está aberta. */
    fun keyTest(on: Boolean)
    /** Diagnóstico: procura ganchos de reinício do fabricante (teste-reinicio.txt). */
    fun probeReboot()
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DATE = DateTimeFormatter.ofPattern("EEE, d 'de' MMM", PT_BR)
private val DOCK_WIDTH = 112.dp
private const val VOLUME_DRAG_DP = 56
private const val VOLUME_HIDE_MS = 4_000L
private const val VOLUME_REPEAT_AFTER_MS = 400L
private const val VOLUME_REPEAT_MS = 160L
private const val CLOCK_TAPS = 7
private const val CLOCK_TAP_GAP_MS = 1_500L
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
    // Na tela inicial, Voltar só fecha a folha aberta
    BackHandler {
        s.sheet = Sheet.NONE
        s.volumeOpen = false
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
                        HomeCards(s, a, night)
                        when (s.sheet) {
                            Sheet.NONE -> {}
                            Sheet.RADIO -> RadioSheet(s, a)
                            Sheet.ABOUT -> AboutSheet(s, a)
                            Sheet.KEYS -> KeyTestSheet(s, a)
                            else -> AppSheet(s, a)
                        }
                        s.probe?.let { ProbeSheet(s.probeTitle, it) { s.probe = null } }
                    }
                }
            }
            if (s.volumeOpen) VolumeControl(s, a, Modifier.align(Alignment.CenterStart).padding(start = DOCK_WIDTH + 12.dp))
            s.toast?.let { Toast(it, Modifier.align(Alignment.BottomCenter)) }
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
        VolumeButton(Icons.plus, 1, s, a)
        Txt(s.volume.toString(), 44, weight = FontWeight.Bold, numeric = true, lineHeight = 1f)
        val fraction = if (s.volumeMax > 0) (s.volume.toFloat() / s.volumeMax).coerceIn(0f, 1f) else 0f
        Box(Modifier.size(14.dp, 110.dp).clip(RoundedCornerShape(7.dp)).background(p.surface3), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(fraction).background(p.accent))
        }
        Txt("de ${s.volumeMax}", 14, color = p.text3)
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
        // 7 toques seguidos no relógio abrem o painel Rumo (versão, atualização e dados do mapa)
        var taps by remember { mutableIntStateOf(0) }
        var lastTap by remember { mutableLongStateOf(0L) }
        Txt(
            s.now.format(TIME), 36, weight = FontWeight.SemiBold, numeric = true, lineHeight = 1f,
            modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                val t = System.currentTimeMillis()
                taps = if (t - lastTap > CLOCK_TAP_GAP_MS) 1 else taps + 1
                lastTap = t
                if (taps >= CLOCK_TAPS) {
                    taps = 0
                    a.openAbout()
                }
            },
        )
        Spacer(Modifier.width(12.dp))
        Txt(s.now.format(DATE), 18, color = p.text2)
        Spacer(Modifier.weight(1f))
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
            Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).clickable { a.systemSettings() },
            contentAlignment = Alignment.Center,
        ) {
            Ico(Icons.gear, 28.dp, p.text)
        }
    }
}

/** Layout "Mapa em foco" do protótipo: mapa à esquerda; mídia e cartão inteligente à direita. */
@Composable
private fun HomeCards(s: HomeState, a: HomeActions, night: Boolean) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MapCard(s, a, night, Modifier.weight(1.6f).fillMaxHeight())
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
private fun MapCard(s: HomeState, a: HomeActions, night: Boolean, modifier: Modifier) {
    val p = LocalPalette.current
    val g = s.gps
    val mapFile = s.mapFile
    val road = s.road
    Box(modifier.card(p, p.mapBlock)) {
        if (mapFile != null) DriveMap(mapFile, g, s.mapStart, night)
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
                .align(if (mapFile != null) BiasAlignment(0f, 2 * CAR_Y - 1) else Alignment.Center)
                .size(68.dp)
                .clip(CircleShape)
                .background(if (mapFile != null) p.accent else p.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Ico(
                Icons.nav, 42.dp,
                if (mapFile != null) p.onAccent else p.text,
                Modifier.rotate(if (mapFile != null) 0f else g.bearing ?: 0f),
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
                        modifier = Modifier.clickable { a.openAbout() },
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
        // A placa de limite fica na direita (a mão do motorista tampava a da esquerda); o radar, na esquerda
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp)) {
            if (g.status != GpsStatus.OK) {
                GpsChip(g, a, Modifier.align(Alignment.CenterStart))
            } else {
                s.radar?.let { RadarPill(it, Modifier.align(Alignment.CenterStart).padding(end = SIGN_SIZE + 12.dp)) }
                road?.let { LimitSign(it.limit, it.estimated, Modifier.align(Alignment.CenterEnd)) }
            }
        }
        if (mapFile != null) {
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
                if (r.alarm) "Reduza · radar ${r.radar.limit} km/h" else "Radar · ${r.radar.limit} km/h",
                17, color = if (r.alarm) Color.White else p.text2,
            )
        }
    }
}

@Composable
private fun GpsChip(g: GpsInfo, a: HomeActions, modifier: Modifier) {
    val p = LocalPalette.current
    val text = when (g.status) {
        GpsStatus.NO_PERMISSION -> "Toque para liberar a localização"
        GpsStatus.OFF -> "GPS desligado"
        GpsStatus.SEARCHING -> "Procurando satélites · ${g.satellitesUsed}/${g.satellitesSeen}"
        GpsStatus.OK -> "GPS ±${g.accuracyM ?: "?"} m · ${g.satellitesUsed} satélites"
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
