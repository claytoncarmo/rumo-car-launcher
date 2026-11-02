package app.rumo

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.rumo.core.AppRelease
import app.rumo.core.AudioOwner
import app.rumo.core.Channel
import app.rumo.core.msUntilUpdateCheck
import app.rumo.core.newerThanInstalled
import app.rumo.core.noticeChannel
import app.rumo.core.parseAppRelease
import app.rumo.core.toJson
import app.rumo.core.DataPlan
import app.rumo.core.DownloadCancelled
import app.rumo.core.planChange
import app.rumo.core.redact
import app.rumo.core.HttpFailure
import app.rumo.core.Favorite
import app.rumo.core.Favorites
import app.rumo.core.RadioNames
import app.rumo.core.fineStep
import app.rumo.core.formatRadioFreq
import app.rumo.device.RadioControl
import app.rumo.ui.RadioCmd
import app.rumo.ui.RadioScan
import app.rumo.core.OverSpeed
import app.rumo.core.audioOwnerOf
import app.rumo.device.RadioProbe
import app.rumo.device.RadioNow
import app.rumo.device.RadioStation
import app.rumo.device.SourceInfo
import app.rumo.core.MarkSpot
import app.rumo.core.RadarAlert
import app.rumo.core.RadarKind
import app.rumo.core.RadarSettings
import app.rumo.core.RadarWatch
import app.rumo.core.directionOf
import app.rumo.core.nearestWayHint
import app.rumo.core.radioPlayStarts
import app.rumo.core.shouldResumeRadio
import app.rumo.core.UserPoint
import app.rumo.core.UserPoints
import app.rumo.core.RoadMatch
import app.rumo.core.RoadMatcher
import app.rumo.device.Aidl
import app.rumo.device.Alerts
import app.rumo.device.BroadcastHunt
import app.rumo.device.CarStatus
import app.rumo.device.CarSourceProbe
import app.rumo.device.AppEntry
import app.rumo.device.Apps
import app.rumo.device.Gps
import app.rumo.device.GpsInfo
import app.rumo.device.GpsStatus
import app.rumo.device.MapData
import app.rumo.device.InstallResult
import app.rumo.device.Installer
import app.rumo.device.PowerService
import app.rumo.device.Reboot
import app.rumo.device.RebootProbe
import app.rumo.device.Updater
import app.rumo.device.keepingLast
import app.rumo.device.UserPointsStore
import app.rumo.core.PT_BR
import app.rumo.device.NightSignal
import app.rumo.ui.SettingsState
import app.rumo.ui.wanted
import app.rumo.ui.DATA_KEY
import app.rumo.ui.HomeActions
import app.rumo.ui.HomeScreen
import app.rumo.ui.HomeState
import app.rumo.ui.MapStart
import app.rumo.ui.MapStatus
import app.rumo.ui.Sheet
import app.rumo.ui.Source
import app.rumo.ui.ThemeMode
import app.rumo.ui.Transfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDate
import java.util.concurrent.Executors
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.roundToInt

/** Tela inicial do Rumo. GPS (e, depois, os alertas) só funcionam com ela na tela. */
class HomeActivity : ComponentActivity(), HomeActions {

    private val state = HomeState()
    private lateinit var gps: Gps
    private lateinit var alerts: Alerts

    // Vias e radares: uma thread só, que lê o roads.db e guarda o estado do casamento com a via
    private val drive = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    // Ouvintes e leituras do sourceinfo e do fmradio: uma thread só, para ligar e desligar sempre na ordem pedida
    // (sair e voltar rápido ao Rumo não deixa um ouvinte ligado com o ZLINK na tela)
    private val radioIpc = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    // Uma ligação com o serviço do rádio por vez, e um comando de sintonia por vez (busca, passo, estação, play)
    private val radioConnect = Mutex()
    private val radioCommands = Mutex()
    private var data: MapData? = null

    // Pontos marcados pelo usuário: lidos uma vez (na thread drive) e guardados a cada mudança
    private val pointsStore by lazy {
        UserPointsStore(
            listOf(
                File(File(Environment.getExternalStorageDirectory(), MapData.FOLDER), UserPoints.FILE),
                File(filesDir, UserPoints.FILE),
            ),
        )
    }
    @Volatile private var minePoints: List<UserPoint>? = null
    private fun minePointsLoaded(): List<UserPoint> = minePoints ?: pointsStore.load().also { minePoints = it }
    private val matcher = RoadMatcher()
    private val radars = RadarWatch()
    private val overSpeed = OverSpeed()
    private var driveJob: Job? = null
    private var pendingFix: GpsInfo? = null
    private var systemNight = false
    private var probeJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var savedAt = 0L
    private val sourceInfo = SourceInfo { name ->
        runOnUiThread {
            lastRawOwner = name
            applyAudioOwner(name)
        }
    }
    private val radioNames = RadioNames()
    private val radioStation = RadioStation(radioNames)
    // Último pacote que o sourceinfo informou, inclusive vazio (rádio parado)
    private var lastRawOwner: String? = null
    private var sourceInfoMissing = false
    private val radioControl by lazy {
        RadioControl(this, prefs.getInt(PREF_RADIO_UNIT, 0).takeIf { it > 0 }) { unit ->
            prefs.edit().putInt(PREF_RADIO_UNIT, unit).apply()
        }
    }
    @Volatile private var scanCancelled = false
    /** Outro comando pediu o rádio durante a procura: ela para sem voltar à estação de antes. */
    @Volatile private var scanInterrupted = false
    @Volatile private var transferCancelled = false
    private val updater by lazy { Updater(prefs) }
    private var savedOwner: String? = null
    private val prefs by lazy { getSharedPreferences("rumo", MODE_PRIVATE) }
    // A posição é gravada a cada 15 s: num arquivo pequeno à parte, para não regravar todas as preferências
    private val positionPrefs by lazy { getSharedPreferences("rumo_position", MODE_PRIVATE) }
    // Nomes das rádios: o texto RDS muda a cada ~2 s; grava no máximo a cada minuto (e ao sair da tela)
    private var radioNamesDirty = false
    private var radioNamesSavedAt = 0L

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            gps.start()
        }
        if (result[Manifest.permission.READ_EXTERNAL_STORAGE] == true) loadMapData()
    }

    private val packagesChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = loadApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A biblioteca nativa do mapa (9,5 MB) demora a carregar: começa já, numa thread à parte, em vez de na hora de
        // criar o mapa na thread principal. Se o nome mudar numa versão do MapLibre, só deixa de adiantar.
        Thread({
            try {
                System.loadLibrary("maplibre")
            } catch (e: Throwable) {
            }
        }, "rumo-mapa").start()
        Aidl.packages = applicationContext.packageManager
        gps = Gps(this, ::onGps) { state.satellites = it }
        alerts = Alerts(this)
        state.favorites = Favorites.decode(prefs.getString(PREF_FAVORITES, null))
        lifecycleScope.launch { state.myPoints = withContext(drive) { minePointsLoaded() } }
        state.radarZoneM = RadarSettings.zone(prefs.getInt(PREF_RADAR_ZONE, RadarSettings.ZONE_DEFAULT))
        state.radarMarginKmh = RadarSettings.margin(prefs.getInt(PREF_RADAR_MARGIN, RadarSettings.MARGIN_DEFAULT))
        radars.zoneM = state.radarZoneM
        radars.marginKmh = state.radarMarginKmh
        state.radioMuted = prefs.getBoolean(PREF_RADIO_MUTED, false)
        radioNames.load(prefs.getString(PREF_RADIO_NAMES, null))
        state.radioNames = radioNames.valid()
        prefs.getString(PREF_SCAN, null)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.isNotEmpty() }?.let { state.radioScan = RadioScan(false, it) }
        state.theme = prefs.getString(PREF_THEME, null)
            ?.let { saved -> ThemeMode.entries.find { it.name == saved } } ?: ThemeMode.AUTO
        systemNight = resources.configuration.isNightBitSet()
        // Até a versão 0.1.1 a posição ficava nas preferências gerais
        val position = if (positionPrefs.contains(PREF_LAT)) positionPrefs else prefs
        if (position.contains(PREF_LAT)) {
            state.mapStart = MapStart(
                position.getFloat(PREF_LAT, 0f).toDouble(),
                position.getFloat(PREF_LON, 0f).toDouble(),
                position.getFloat(PREF_BEARING, 0f).toDouble(),
            )
        }
        packageManager.getPackageInfo(packageName, 0).let {
            state.appVersion = it.versionName.orEmpty()
            state.appCode = PackageInfoCompat.getLongVersionCode(it)
        }
        // A versão nova achada na última consulta continua avisando depois de reiniciar, até ser instalada
        state.update = newerThanInstalled(state.appCode, prefs.getString(PREF_UPDATE_FOUND, null)?.let(::parseAppRelease))
        hideSystemBars()
        setContent { HomeScreen(state, this) }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        // Avisos de pacote só vêm do sistema; "exportado" evita a permissão extra que o modo não exportado cria no Android 9
        ContextCompat.registerReceiver(this, packagesChanged, filter, ContextCompat.RECEIVER_EXPORTED)
        loadApps()

        // Estação do rádio (fmradio), perguntada com o Rumo na tela: a cada segundo com o rádio como fonte ou a tela de
        // rádio aberta; senão, de vez em quando, só para notar a troca de estação feita no app do rádio
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refreshRadio()
                    delay(if (state.source == Source.RADIO || state.sheet == Sheet.RADIO) RADIO_EVERY_MS else RADIO_IDLE_EVERY_MS)
                }
            }
        }

        // Farol aceso → tema escuro no automático (como o Android Auto). Só pergunta com o Rumo na tela.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    state.headlight = withContext(Dispatchers.IO) { CarStatus.headlight() }
                    delay(HEADLIGHT_EVERY_MS)
                }
            }
        }

        // Versão nova no canal da instalada, de 6 em 6 horas. Só com o Rumo na tela (nada roda em segundo plano): ao
        // voltar, confere pela hora da última consulta o que falta. Falhou (sem rede): tenta de novo em meia hora.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (noticeChannel(state.appVersion) == null) return@repeatOnLifecycle // build local
                while (true) {
                    val wait = msUntilUpdateCheck(prefs.getLong(PREF_UPDATE_CHECKED, 0L), System.currentTimeMillis())
                    delay(maxOf(wait, UPDATE_FIRST_DELAY_MS))
                    if (!checkUpdateNotice()) delay(UPDATE_RETRY_MS)
                }
            }
        }

        val missing = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_EXTERNAL_STORAGE)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }

    override fun onStart() {
        super.onStart()
        gps.start()
        watchNetwork()
        // A pasta Rumo pode ter sido copiada com o Rumo fora da tela
        if (state.mapStatus != MapStatus.READY) loadMapData()
        // Ouvinte do fmradio, só para o texto RDS (sem ele o rádio não decodifica o texto)
        radioIpc.executor.execute { radioStation.start() }
        // Fonte de áudio pelo serviço sourceinfo: quem toca agora e, com o Rumo na tela, cada troca.
        // Resposta vazia (acontece com o rádio tocando): vale a última fonte que ele informou.
        lifecycleScope.launch {
            val now = withContext(radioIpc) { sourceInfo.start() }
            if (now == null) {
                // Aparelho sem o serviço: estima pelo último app de áudio aberto
                sourceInfoMissing = true
                detectSource()
                resumeRadio()
                return@launch
            }
            state.sourceLive = true
            applyAudioOwner(now.ifBlank { prefs.getString(PREF_SOURCE, null).orEmpty() })
            resumeRadio()
        }
    }

    /**
     * O serviço do rádio só existe enquanto o Rumo está ligado nele: ao atualizar o app (ou o Android encerrar o
     * processo) ele morre junto e o rádio fica mudo, com o card ainda dizendo que toca. Com o rádio como fonte, liga de
     * novo, o que põe o rádio para tocar.
     */
    private fun resumeRadio() {
        if (shouldResumeRadio(state.source == Source.RADIO, state.radioMuted, radioControl.connected)) {
            lifecycleScope.launch { ensureRadio() }
        }
    }

    // Alertas só com o Rumo na tela: com o Android Auto aberto, nada toca
    override fun onStop() {
        scanCancelled = true
        // A ligação com o serviço do rádio fica aberta: se o rádio foi ligado pela tela do Rumo, o serviço só
        // existe por causa dela, e desligar ao sair (ao abrir o ZLINK) parava o rádio. Os ouvintes saem na mesma
        // thread em que entraram, depois deles (e mesmo que a tela seja destruída logo em seguida).
        radioIpc.executor.execute {
            sourceInfo.stop()
            radioStation.stop()
        }
        savePosition(state.gps)
        flushRadioNames(now = true)
        gps.stop()
        unwatchNetwork()
        alerts.stop()
        state.radar = null
        super.onStop()
    }

    /** Acompanha se há rede, para a aba Atualizações consultar de novo quando a conexão volta. */
    private fun watchNetwork() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        state.online = manager.activeNetwork != null
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = runOnUiThread { state.online = true }
            override fun onLost(network: Network) = runOnUiThread { state.online = false }
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: RuntimeException) {
            // Sem acompanhar a rede, só o botão "Buscar de novo" e a entrada na aba consultam
        }
    }

    private fun unwatchNetwork() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // Já tinha sido solto
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        detectSource()
    }

    override fun onDestroy() {
        stopKeyTest()
        unregisterReceiver(packagesChanged)
        alerts.stop()
        radioControl.disconnect()
        radioIpc.executor.execute {
            sourceInfo.stop()
            radioStation.stop()
        }
        radioIpc.close()
        val d = data
        drive.executor.execute { d?.close() }
        drive.close()
        super.onDestroy()
    }

    // O tema automático segue o modo noturno do sistema; o aviso mostra se o farol liga esse modo neste aparelho
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = newConfig.isNightBitSet()
        if (night != systemNight) {
            systemNight = night
            state.toast = if (night) "Modo noturno do sistema ligado" else "Modo noturno do sistema desligado"
        }
    }

    override fun chooseTheme(mode: ThemeMode) {
        state.theme = mode
        prefs.edit().putString(PREF_THEME, mode.name).apply()
        state.toast = when (mode) {
            ThemeMode.AUTO ->
                if (state.headlight != null) "Tema automático: escuro com o farol aceso"
                else "Tema automático: escuro das 18h às 6h ou com o modo noturno do sistema"
            ThemeMode.DARK -> "Tema escuro"
            ThemeMode.LIGHT -> "Tema claro"
        }
    }

    /** Última posição e rumo, para o mapa abrir no lugar certo depois de reiniciar (antes do GPS fixar). */
    private fun savePosition(g: GpsInfo) {
        val lat = g.lat ?: return
        val lon = g.lon ?: return
        savedAt = SystemClock.elapsedRealtime()
        val e = positionPrefs.edit().putFloat(PREF_LAT, lat.toFloat()).putFloat(PREF_LON, lon.toFloat())
        g.bearing?.let { e.putFloat(PREF_BEARING, it) }
        e.apply()
    }

    /**
     * Teste do farol: ouve os avisos (broadcasts) que parecem do farol; fotografa os ajustes do sistema. Não se
     * registra mais na MCU (mcu_service): o teste antigo fazia isso e os botões do aparelho pararam logo depois; dá [PROBE_SECONDS] para acender e apagar o farol; mostra o que chegou e o que mudou e
     * salva o relatório completo em headlight-test.txt (memória interna, pasta Rumo, e pendrive/cartão quando der).
     */
    override fun probeHeadlight() {
        if (probeJob?.isActive == true) return
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissions.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            state.toast = "Permita salvar arquivos e repita o toque longo"
            return
        }
        probeJob = lifecycleScope.launch {
            state.toast = "Teste do farol: procurando os avisos nos apps do aparelho…"
            val hunt = withContext(Dispatchers.IO) { BroadcastHunt.hunt(this@HomeActivity, Apps.ZLINK) }
            val candidates = hunt.actions
            state.toast = "Teste do farol: lendo os serviços do carro…"
            val (framework, services) = withContext(Dispatchers.IO) {
                CarSourceProbe.describe(this@HomeActivity, hunt.classes) to CarSourceProbe.services()
            }
            val interfaces = withContext(Dispatchers.IO) { CarSourceProbe.serviceInterfaces(this@HomeActivity, services) }
            val (before, propsBefore) = withContext(Dispatchers.IO) {
                NightSignal.snapshot(this@HomeActivity) to CarSourceProbe.props(hunt.props)
            }
            val actions = (candidates.map { it.action } + BroadcastHunt.KNOWN).distinct()
            val received = mutableListOf<String>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    received += "${intent.action}${describe(intent)}"
                }
            }
            val filter = IntentFilter().apply { actions.forEach(::addAction) }
            // Aviso "fixo" (sticky) volta na hora com o estado atual
            ContextCompat.registerReceiver(this@HomeActivity, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
                ?.let { received += "(estado atual) ${it.action}${describe(it)}" }

            try {
                for (left in PROBE_SECONDS downTo 1) {
                    state.toast = "Acenda e depois apague o farol · $left s · ${received.size} avisos"
                    delay(1_000)
                }
            } finally {
                unregisterReceiver(receiver)
            }
            state.toast = "Comparando…"
            val (after, propsAfter) = withContext(Dispatchers.IO) {
                NightSignal.snapshot(this@HomeActivity) to CarSourceProbe.props(hunt.props)
            }
            state.toast = null
            // O GPS e o contador de mensagens da MCU mudam sempre: só atrapalham a leitura
            val changed = NightSignal.diff(before, after).filterNot { it.startsWith("prop:vendor.gps.") || it.startsWith("prop:log.mcud.") }
            val summary = buildList {
                add("Avisos recebidos (${received.size}):")
                addAll(received.ifEmpty { listOf("nenhum") })
                add("Ajustes que mudaram (${changed.size}):")
                addAll(changed.ifEmpty { listOf("nenhum") })
            }
            val full = summary + buildList {
                add("Propriedades de luz (antes → depois):")
                addAll(hunt.props.map { "$it: ${propsBefore[it]} → ${propsAfter[it]}" }.ifEmpty { listOf("nenhuma") })
                add("Serviços do sistema ligados ao carro (${services.size}) e suas interfaces:")
                addAll(interfaces.ifEmpty { listOf("nenhum") })
                add("Classes do android.carsource (${hunt.classes.size}):")
                addAll(framework.ifEmpty { listOf("nenhuma") })
                add("Avisos encontrados nos apps (${candidates.size}):")
                addAll(candidates.map { "${it.action} · ${it.pkg}" })
            }
            showReport("Teste do farol", "headlight-test.txt", summary, full)
        }
    }

    /**
     * Teste do rádio: o sourceinfo diz a fonte, mas não a estação. O app de rádio do aparelho tem um serviço para
     * outros apps (FMPlugService, IRadioServiceAPI): o teste liga nele, lê todas as funções de leitura (get…/is…),
     * inscreve o ouvinte dele (IRadioCallBack) e, por [MEDIA_PROBE_SECONDS], anota o que muda enquanto o motorista
     * troca de estação. Também escuta os avisos (broadcasts) do app de rádio. Salva radio-test.txt.
     */
    override fun probeMedia() {
        if (probeJob?.isActive == true) return
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissions.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            state.toast = "Permita salvar arquivos e repita o toque longo"
            return
        }
        probeJob = lifecycleScope.launch {
            state.toast = "Teste do rádio: lendo o app de rádio…"
            val hunt = withContext(Dispatchers.IO) { BroadcastHunt.radio(this@HomeActivity) }
            val radio = RadioProbe(this@HomeActivity)
            val (classes, systemRadio) = withContext(Dispatchers.IO) {
                val services = CarSourceProbe.services().filter { "radio" in it.lowercase() || "fm" in it.lowercase() }
                radio.describe() to CarSourceProbe.serviceInterfaces(this@HomeActivity, services)
            }

            val timeline = mutableListOf<String>()
            val t0 = SystemClock.elapsedRealtime()
            val log = { e: String ->
                val t = (SystemClock.elapsedRealtime() - t0) / 100 / 10.0
                runOnUiThread { timeline += String.format(PT_BR, "+%.1f s  %s", t, e) }
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = log("aviso · ${intent.action}${describe(intent)}")
            }
            val filter = IntentFilter().apply { hunt.actions.take(MAX_ACTIONS).forEach(::addAction) }
            ContextCompat.registerReceiver(this@HomeActivity, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

            state.toast = "Teste do rádio: ligando no serviço do rádio…"
            val bound = radio.bind()
            val (first, listening) = withContext(Dispatchers.IO) {
                // Sem o ouvinte do fmradio: ele parece guardar um só e tiraria o do app de rádio
                radio.read() + radio.systemRead() to "app de rádio: ${radio.listen { log("ouvinte · $it") }}"
            }
            val status = listOf(radio.permissions(), "serviço: $bound", "ouvintes: $listening")
            var last = first
            try {
                for (left in MEDIA_PROBE_SECONDS downTo 1) {
                    if (left % 2 == 0) {
                        val now = withContext(Dispatchers.IO) { radio.read() + radio.systemRead() }
                        (now - last.toSet()).forEach { log("leitura · $it") }
                        last = now
                    }
                    state.toast = "Com o rádio tocando, troque de estação (card e sintonizador) · $left s"
                    delay(1_000)
                }
            } finally {
                unregisterReceiver(receiver)
                withContext(NonCancellable + Dispatchers.IO) { radio.stop() }
            }
            state.toast = null
            val summary = status + listOf("Leitura inicial (${first.size}):") + first.ifEmpty { listOf("nada") } +
                listOf("Linha do tempo (${timeline.size}):") + timeline.ifEmpty { listOf("nada") }
            val full = summary + listOf("Classes do rádio:") + classes +
                listOf("Serviços de rádio do sistema:") + systemRadio.ifEmpty { listOf("nenhum") }
            showReport("Teste do rádio", "radio-test.txt", summary, full)
        }
    }

    private suspend fun showReport(title: String, file: String, summary: List<String>, full: List<String>) {
        val saved = withContext(Dispatchers.IO) { saveReport(title, file, full.map(::redact)) }
        // Na tela, só o que interessa; o resto vai para o arquivo
        state.probeTitle = title
        state.probe = summary.map(::redact) + listOf(
            if (saved.isEmpty()) "Não consegui salvar o relatório completo." else "Relatório completo salvo em:",
        ) + saved
    }

    /** Grava o relatório na pasta Rumo da memória interna e na pasta do Rumo em cada pendrive/cartão. */
    private fun saveReport(title: String, file: String, lines: List<String>): List<String> {
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val text = "Rumo $version · ${title.lowercase(PT_BR)} · ${java.time.LocalDateTime.now()}\n\n" +
            lines.joinToString("\n") + "\n"
        @Suppress("DEPRECATION")
        val targets = listOf(File(Environment.getExternalStorageDirectory(), "Rumo")) +
            getExternalFilesDirs(null).drop(1).filterNotNull()
        return targets.mapNotNull { dir ->
            try {
                dir.mkdirs()
                File(dir, file).also { it.writeText(text) }.path
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Extras de um aviso, como texto. Qualquer app pode mandar o aviso: um extra que não abre vira só um marcador. */
    private fun describe(intent: Intent): String {
        return try {
            val extras = intent.extras ?: return ""
            @Suppress("DEPRECATION")
            extras.keySet().joinToString(prefix = " {", postfix = "}") { "$it=${extras.get(it)}" }
        } catch (e: RuntimeException) {
            " {extras ilegíveis: ${e.javaClass.simpleName}}"
        }
    }

    private fun Configuration.isNightBitSet() =
        uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // Botão Início com o Rumo já na tela: volta para os cards (no teste dos botões, só anota)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (state.sheet == Sheet.KEYS) {
            keyLog("Início: o Android pediu a tela inicial")
            return
        }
        state.sheet = Sheet.NONE
        state.volumeOpen = false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (state.sheet == Sheet.KEYS && event.repeatCount == 0) {
            val device = event.device?.name ?: "dispositivo ${event.deviceId}"
            val action = if (event.action == KeyEvent.ACTION_DOWN) "↓" else "↑"
            keyLog("${KeyEvent.keyCodeToString(event.keyCode)} $action · $device")
        }
        return super.dispatchKeyEvent(event)
    }

    override fun volume(delta: Int) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        try {
            if (delta != 0) {
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    0,
                )
            }
        } catch (e: SecurityException) {
            state.toast = "O Android não deixou mudar o volume"
        }
        state.volume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        state.volumeMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    private var keyTestStart = 0L
    private var keyTestReceiver: BroadcastReceiver? = null
    /** Processo do getevent e o número do teste que o abriu (fechar e abrir rápido não deixa um processo esquecido). */
    private val geteventLock = Any()
    private var getevent: java.lang.Process? = null
    private var keyTestRun = 0

    override fun keyTest(on: Boolean) {
        stopKeyTest()
        if (!on) return
        val run = synchronized(geteventLock) { ++keyTestRun }
        keyTestStart = SystemClock.elapsedRealtime()
        state.keyLog = emptyList()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = keyLog(
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> "A tela apagou (botão Power?)"
                    Intent.ACTION_SCREEN_ON -> "A tela acendeu"
                    else -> intent.action.orEmpty()
                },
            )
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        keyTestReceiver = receiver
        lifecycleScope.launch(Dispatchers.IO) { readGetevent(run) }
    }

    private fun stopKeyTest() {
        keyTestReceiver?.let { try { unregisterReceiver(it) } catch (e: IllegalArgumentException) {} }
        keyTestReceiver = null
        val process = synchronized(geteventLock) {
            keyTestRun++
            getevent.also { getevent = null }
        }
        process?.destroy()
    }

    /** Segunda fonte: as teclas direto do driver de entrada (`getevent`). Sem permissão, o próprio erro vai para a lista. */
    private fun readGetevent(run: Int) {
        val process = try {
            ProcessBuilder("getevent", "-l").redirectErrorStream(true).start()
        } catch (e: IOException) {
            keyLog("getevent: indisponível neste aparelho")
            return
        }
        val current = synchronized(geteventLock) {
            (run == keyTestRun).also { if (it) getevent = process }
        }
        if (!current) {
            // O teste foi fechado (ou reaberto) enquanto o processo abria
            process.destroy()
            return
        }
        var other = 0
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    // O toque na tela também gera EV_ABS aos montes: só as teclas e as primeiras mensagens de erro
                    if ("EV_KEY" in line || other++ < GETEVENT_NOTES) keyLog("getevent: ${line.trim()}")
                }
            }
        } catch (e: IOException) {
            // O teste terminou: o processo foi encerrado
        }
        if (synchronized(geteventLock) { getevent === process }) keyLog("getevent: terminou (sem acesso aos dispositivos de entrada?)")
    }

    private fun keyLog(line: String) {
        val t = (SystemClock.elapsedRealtime() - keyTestStart) / 100 / 10.0
        val text = String.format(PT_BR, "+%.1f s  %s", t, line)
        runOnUiThread { state.keyLog = (listOf(text) + state.keyLog).take(MAX_KEY_LOG) }
    }

    override fun openZlink() {
        val intent = packageManager.getLaunchIntentForPackage(Apps.ZLINK)
        if (intent == null) {
            state.toast = "ZLINK não encontrado neste aparelho"
            return
        }
        state.source = Source.ZLINK
        start(intent)
    }

    override fun openRadio() {
        val radio = Apps.findRadio(state.apps)
        if (radio == null) {
            state.sheet = Sheet.AUDIO
            state.toast = "Não reconheci o rádio pelo nome: escolha na lista"
            return
        }
        openApp(radio)
    }

    /** Tela de rádio do Rumo: liga no serviço do rádio e lê as memórias do aparelho (para trazer às favoritas). */
    override fun openRadioScreen() {
        state.sheet = Sheet.RADIO
        // Abrir a tela de rádio põe o rádio para tocar: sem mudo
        if (state.radioMuted) setRadioMuted(false)
        lifecycleScope.launch {
            ensureRadio()
            val presets = withContext(Dispatchers.IO) {
                val band = radioControl.uiBand()
                // Teste: as memórias de cada banda, para entender o formato
                for (b in 0..4) radioLog("memórias da banda $b: ${radioControl.presets(b)?.joinToString() ?: "nada"}")
                radioLog("banda atual: $band")
                band?.let { radioControl.presets(it) }
            }
            state.devicePresets = presets?.filter { it >= 30_000 }?.distinct().orEmpty()
            refreshRadio()
        }
    }

    override fun radio(cmd: RadioCmd) {
        // Qualquer comando de sintonia, o play ou abrir o app do rádio põem o rádio para tocar de novo
        if (cmd is RadioCmd.Play || cmd is RadioCmd.Tune || cmd is RadioCmd.Seek || cmd is RadioCmd.Step || cmd is RadioCmd.OpenApp) {
            if (state.radioMuted) setRadioMuted(false)
            if (lastRawOwner == "") lastRawOwner = null
        }
        when (cmd) {
            RadioCmd.OpenApp -> openRadio()
            RadioCmd.Play -> tunerCommand {
                radioLog(withContext(Dispatchers.IO) { radioControl.playAudio() })
            }
            is RadioCmd.Seek -> tunerCommand {
                val f = withContext(Dispatchers.IO) { radioControl.seekAndWait(cmd.up, ::radioLog) }
                radioLog("busca parou em ${f?.let(::formatRadioFreq) ?: "?"}")
                refreshRadio()
            }
            // Ajuste fino: de 0,2 em 0,2 pelas frequências de final ímpar (as das estações no Brasil)
            is RadioCmd.Step -> tunerCommand {
                // Lida já na vez deste comando: dois toques seguidos andam 0,4
                refreshRadio()
                val from = state.radio?.khz ?: return@tunerCommand
                withContext(Dispatchers.IO) { radioControl.tune(fineStep(from, cmd.up), ::radioLog) }
                refreshRadio()
            }
            is RadioCmd.Tune -> tunerCommand {
                // Escolher uma estação com outra fonte tocando: pede o rádio como fonte (teste do requestPlayAudio)
                if (state.source != Source.RADIO) radioLog(withContext(Dispatchers.IO) { radioControl.playAudio() })
                val ok = withContext(Dispatchers.IO) { radioControl.tune(cmd.khz, ::radioLog) }
                if (!ok) state.toast = "Não consegui sintonizar ${formatRadioFreq(cmd.khz)}"
                refreshRadio()
            }
            is RadioCmd.ToggleFavorite -> {
                val had = state.favorites.any { it.khz == cmd.khz }
                saveFavorites(Favorites.toggle(state.favorites, cmd.khz))
                state.toast = if (had) "${formatRadioFreq(cmd.khz)} saiu das favoritas" else "${formatRadioFreq(cmd.khz)} nas favoritas"
            }
            is RadioCmd.Rename -> saveFavorites(Favorites.rename(state.favorites, cmd.khz, cmd.name))
            is RadioCmd.Remove -> saveFavorites(Favorites.remove(state.favorites, cmd.khz))
            RadioCmd.ImportPresets -> {
                saveFavorites(state.devicePresets.fold(state.favorites) { list, khz -> Favorites.add(list, khz) })
                state.toast = "Memórias do aparelho nas favoritas"
            }
            RadioCmd.Scan -> {
                if (state.radioScan?.running == true) return
                scanCancelled = false
                scanInterrupted = false
                state.radioScan = RadioScan(true, emptyList())
                tunerCommand(interruptsScan = false) {
                    val found = withContext(Dispatchers.IO) {
                        radioControl.scan(
                            onFound = { f -> runOnUiThread { state.radioScan = state.radioScan?.let { it.copy(found = it.found + f) } } },
                            cancelled = { scanCancelled },
                            stay = { scanInterrupted },
                            log = ::radioLog,
                        )
                    }
                    state.radioScan = RadioScan(false, found)
                    // A lista fica guardada até a próxima procura
                    prefs.edit().putString(PREF_SCAN, found.joinToString(",")).apply()
                }
            }
            RadioCmd.StopScan -> scanCancelled = true
            RadioCmd.SaveLog -> lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) { saveReport("Teste dos comandos do rádio", "radio-commands-test.txt", state.radioLog) }
                state.toast = if (saved.isEmpty()) "Não consegui salvar o registro" else "Registro salvo: ${saved.first()}"
            }
        }
    }

    private suspend fun ensureRadio() = radioConnect.withLock {
        if (radioControl.connected) return@withLock
        // Sem registerRadioClientBinder: os comandos funcionam sem ele, e o "cliente saiu" pode parar o rádio
        radioLog(radioControl.connect())
    }

    /**
     * Um comando de sintonia por vez: dois juntos (uma busca e um toque numa favorita) brigavam pelo sintonizador.
     * Um comando durante a procura de estações a interrompe, sem voltar à estação de antes: vale o que o motorista
     * pediu por último.
     */
    private fun tunerCommand(interruptsScan: Boolean = true, block: suspend () -> Unit) {
        if (interruptsScan && state.radioScan?.running == true) {
            scanInterrupted = true
            scanCancelled = true
        }
        lifecycleScope.launch {
            radioCommands.withLock {
                ensureRadio()
                block()
            }
        }
    }

    private fun saveFavorites(list: List<Favorite>) {
        state.favorites = list
        prefs.edit().putString(PREF_FAVORITES, Favorites.encode(list)).apply()
    }

    /** Registro dos comandos do rádio, com a hora (pode vir de qualquer thread). */
    private fun radioLog(line: String) {
        val stamped = "${java.time.LocalTime.now().withNano(0)}  $line"
        runOnUiThread { state.radioLog = (state.radioLog + stamped).takeLast(RADIO_LOG_LINES) }
    }

    override fun openApp(app: AppEntry) {
        sourceOf(app.pkg)?.let { state.source = it }
        state.sheet = Sheet.NONE
        start(Apps.launchIntent(app))
    }

    override fun appDetails(app: AppEntry) = start(Apps.detailsIntent(app))

    // Depois de desinstalar, o aviso de pacote removido recarrega a gaveta
    override fun uninstall(app: AppEntry) = start(Apps.uninstallIntent(app))

    override fun askUsageAccess() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            state.toast = "Ative o Rumo na lista"
        } catch (e: ActivityNotFoundException) {
            state.toast = "Este aparelho não tem a tela de Acesso ao uso"
        }
    }

    // ZLINK: todas as teclas. Rádio: o play liga e desliga (tocando: mudo do fmradio, porque a tecla play/pausa não
    // chega ao app de rádio; parado: tira o mudo e pede o rádio como fonte); com favoritas do Rumo, anterior/próxima
    // andam por elas em vez das memórias do aparelho.
    override fun mediaKey(keyCode: Int) {
        if (state.source == Source.RADIO && keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            // O sourceinfo informa vazio quando o rádio não está tocando
            if (radioPlayStarts(state.radioMuted, lastRawOwner, radioControl.connected)) radio(RadioCmd.Play) else setRadioMuted(true)
            return
        }
        if (state.source == Source.RADIO && state.favorites.isNotEmpty()) {
            Favorites.step(state.favorites, state.radio?.khz, keyCode == KeyEvent.KEYCODE_MEDIA_NEXT)
                ?.let { radio(RadioCmd.Tune(it.khz)) }
            return
        }
        val allowed = when (state.source) {
            Source.ZLINK -> true
            Source.RADIO -> keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            else -> false
        }
        if (!allowed) {
            state.toast = if (state.source == Source.RADIO) {
                "No rádio, só anterior e próxima"
            } else {
                "Abra o Android Auto ou o rádio para usar os botões"
            }
            return
        }
        sendMediaKey(keyCode)
    }

    /**
     * Mudo do rádio pelo play do card. Fica guardado: se o Rumo deixou o rádio mudo, ele mesmo tira (ao tocar de
     * novo, sintonizar, abrir o app do rádio ou passar para outra fonte), mesmo depois de reiniciar.
     */
    private fun setRadioMuted(on: Boolean) {
        // Calar o rádio para a procura de estações (sem voltar à estação de antes)
        if (on && state.radioScan?.running == true) {
            scanInterrupted = true
            scanCancelled = true
        }
        state.radioMuted = on
        prefs.edit().putBoolean(PREF_RADIO_MUTED, on).apply()
        lifecycleScope.launch {
            radioLog(withContext(Dispatchers.IO) { radioControl.mute(on) })
            // O mudo do fmradio sozinho não calou o rádio no aparelho. O serviço do rádio só existe enquanto o Rumo
            // está ligado nele (ao atualizar o app ele morre e o rádio cala): soltar a ligação o encerra. Para voltar,
            // o play liga de novo (ensureRadio), o que põe o rádio para tocar.
            if (on) {
                radioControl.disconnect()
                radioLog("ligação com o serviço do rádio solta")
            }
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private suspend fun refreshRadio() {
        val now = withContext(radioIpc) { radioStation.read() } ?: return
        onRadio(now)
        if (radioStation.namesChanged) {
            radioStation.namesChanged = false
            saveRadioNames()
        }
    }

    /** Nomes das rádios: na tela na hora; nas preferências no máximo a cada minuto (e ao sair da tela, no onStop). */
    private fun saveRadioNames() {
        state.radioNames = radioNames.valid()
        radioNamesDirty = true
        flushRadioNames(now = false)
    }

    private fun flushRadioNames(now: Boolean) {
        if (!radioNamesDirty) return
        val t = SystemClock.elapsedRealtime()
        if (!now && t - radioNamesSavedAt < RADIO_NAMES_EVERY_MS) return
        radioNamesDirty = false
        radioNamesSavedAt = t
        prefs.edit().putString(PREF_RADIO_NAMES, radioNames.encode()).apply()
    }

    /**
     * Estação lida no fmradio. Troca de estação (outra frequência) quer dizer que o rádio é quem toca: corrige a
     * fonte se ela ainda estiver errada (ex.: o sourceinfo respondeu vazio ao abrir).
     */
    private fun onRadio(now: RadioNow) {
        val tuned = state.radio?.khz != null && state.radio?.khz != now.khz
        state.radio = now
        if (tuned && state.source != Source.RADIO) applyAudioOwner(RADIO_PKG)
    }

    override fun voice() {
        val intent = Apps.voiceIntent(this)
        if (intent == null) {
            state.hasVoice = false
            return
        }
        start(intent)
    }

    override fun openAndroidSettings() = start(Intent(Settings.ACTION_SETTINGS))

    override fun askLocation() = permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))

    override fun askFiles() = permissions.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))

    /** Configurações da home: só com o carro parado, para não pedir atenção do motorista. */
    override fun openSettings() {
        if ((state.gps.speedKmh ?: 0) >= PARKED_BELOW_KMH) {
            state.toast = "Pare o carro para abrir as configurações da home"
            return
        }
        state.sheet = Sheet.SETTINGS
    }

    override fun setRadarZone(meters: Int) {
        val zone = RadarSettings.zone(meters)
        state.radarZoneM = zone
        radars.zoneM = zone
        prefs.edit().putInt(PREF_RADAR_ZONE, zone).apply()
    }

    override fun setRadarMargin(kmh: Int) {
        val margin = RadarSettings.margin(kmh)
        state.radarMarginKmh = margin
        radars.marginKmh = margin
        prefs.edit().putInt(PREF_RADAR_MARGIN, margin).apply()
    }

    override fun addPoint(spot: MarkSpot, kind: Int, limit: Int, bothWays: Boolean) {
        if (minePoints == null) {
            state.toast = "Carregando os pontos salvos: toque de novo"
            return
        }
        state.sheet = Sheet.NONE
        lifecycleScope.launch {
            // Encaixa o ponto na rua: o GPS e o toque no "+" (um pouco depois do radar) deixam o ponto de lado da pista
            val onRoad = withContext(drive) {
                data?.let { d -> nearestWayHint(d.runsNear(spot.lat, spot.lon, SNAP_SEARCH_M), spot.lat, spot.lon, SNAP_MAX_M) }
            }
            val point = UserPoint(
                onRoad?.lat?.takeUnless { it.isNaN() } ?: spot.lat,
                onRoad?.lon?.takeUnless { it.isNaN() } ?: spot.lon,
                kind,
                if (kind in RadarKind.ENFORCING) limit else 0,
                if (bothWays) -1 else spot.bearing ?: -1,
                LocalDate.now().toString(),
            )
            savePoints(UserPoints.add(state.myPoints, point))
            state.toast = "${UserPoints.label(point)} salvo"
        }
    }

    override fun removePoint(index: Int) {
        if (minePoints == null) return
        savePoints(UserPoints.remove(state.myPoints, index))
    }

    override fun updatePoint(index: Int, point: UserPoint) {
        if (minePoints == null) return
        savePoints(UserPoints.replace(state.myPoints, index, point))
    }

    /**
     * Move o ponto ao longo da rua: no sentido do próprio ponto ou, nos dois sentidos, no do trecho da rua mais perto
     * (do mapa). Sem rua por perto, não move.
     */
    override fun movePoint(index: Int, meters: Int) {
        if (minePoints == null) return
        val point = state.myPoints.getOrNull(index) ?: return
        lifecycleScope.launch {
            // Eixo (sentido do ponto ou, nos dois sentidos, o da rua mais perto) e o ponto novo, encaixado na rua
            val moved = withContext(drive) {
                val d = data
                val axis: Int? = if (point.bearing >= 0) {
                    point.bearing
                } else {
                    d?.let { nearestWayHint(it.runsNear(point.lat, point.lon, MOVE_WAY_SEARCH_M), point.lat, point.lon) }
                        ?.bearing?.roundToInt()?.mod(360)
                }
                if (axis == null) return@withContext null
                val raw = UserPoints.move(point, meters, axis)
                // Numa curva, andar em linha reta tira o ponto da rua: volta para a rua mais perto
                val onRoad = d?.let { nearestWayHint(it.runsNear(raw.lat, raw.lon, MOVE_WAY_SEARCH_M), raw.lat, raw.lon, SNAP_MOVE_M) }
                val snapped = if (onRoad != null && !onRoad.lat.isNaN()) raw.copy(lat = onRoad.lat, lon = onRoad.lon) else raw
                axis to snapped
            }
            if (moved == null) {
                state.toast = "Sem rua do mapa perto deste ponto para mover"
                return@launch
            }
            // A lista pode ter mudado enquanto a rua era procurada
            if (state.myPoints.getOrNull(index) != point) return@launch
            val (axis, newPoint) = moved
            savePoints(UserPoints.replace(state.myPoints, index, newPoint))
            val heading = if (meters >= 0) axis else (axis + 180) % 360
            state.toast = "Ponto movido ${abs(meters)} m para o ${directionOf(heading.toFloat())}"
        }
    }

    /** Põe a lista nova no mapa e nos avisos e a grava (em ordem: tudo na thread drive). */
    private fun savePoints(list: List<UserPoint>) {
        state.myPoints = list
        lifecycleScope.launch {
            val saved = withContext(drive) {
                minePoints = list
                data?.setMine(UserPoints.toRadars(list))
                // O radar à frente pode ser um que acabou de sair da lista
                radars.reset()
                pointsStore.save(list)
            }
            data?.let { state.mineRadars = it.mine }
            if (saved == 0) state.toast = "Não foi possível guardar os pontos"
        }
    }

    override fun reboot() {
        if ((state.gps.speedKmh ?: 0) >= PARKED_BELOW_KMH) {
            state.toast = "Pare o carro para reiniciar"
            return
        }
        state.toast = "Reiniciando o Android…"
        lifecycleScope.launch {
            val problem = withContext(Dispatchers.IO) { Reboot.request(this@HomeActivity) } ?: return@launch
            // Sem permissão nem root: pelo menu de energia, com o serviço de acessibilidade
            when {
                !PowerService.connected -> {
                    state.toast = "Ligue o serviço \"Rumo\" em Acessibilidade e toque em Reiniciar de novo"
                    accessibilitySettings()
                }
                !PowerService.openPowerMenu() -> state.toast = "O Android não abriu o menu de energia ($problem)"
                else -> {
                    state.toast = "Abrindo o menu de energia…"
                    delay(POWER_MENU_WAIT_MS)
                    if (PowerService.waiting) {
                        PowerService.disarm()
                        state.toast = "Não achei \"Reiniciar\" no menu de energia deste aparelho"
                    }
                }
            }
        }
    }

    override fun accessibilitySettings() = start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    /** Teste do reinício: procura no aparelho um gancho que reinicie (componentes, textos, serviços). Salva reboot-test.txt. */
    override fun probeReboot() {
        if (probeJob?.isActive == true) return
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissions.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            state.toast = "Permita salvar arquivos e toque de novo"
            return
        }
        probeJob = lifecycleScope.launch {
            state.toast = "Teste do reinício: procurando nos apps e serviços do aparelho…"
            val report = withContext(Dispatchers.IO) { RebootProbe.run(this@HomeActivity) }
            state.toast = null
            showReport("Teste do reinício", "reboot-test.txt", report.summary, report.full)
        }
    }

    override fun checkUpdates() {
        if (state.settings.checking || state.transfer != null) return
        state.settings = state.settings.copy(checking = true)
        lifecycleScope.launch {
            val (stable, nightly, data) = coroutineScope {
                val stable = async { updater.fetchChannel(Channel.STABLE) }
                val nightly = async { updater.fetchChannel(Channel.NIGHTLY) }
                val data = async { updater.fetchData() }
                Triple(stable.await(), nightly.await(), data.await())
            }
            // Sem conexão fica o que já se sabia (a tela não esvazia); o resto do estado (escolha de estados) continua
            val last = state.settings
            state.settings = last.copy(
                checking = false,
                offline = stable.offline || nightly.offline || data.offline,
                stable = stable.keepingLast(last.stable),
                nightly = nightly.keepingLast(last.nightly),
                data = data.keepingLast(last.data),
            )
            // A consulta manual também vale para o aviso do topo, no canal da versão instalada
            val mine = when (noticeChannel(state.appVersion)) {
                Channel.STABLE -> stable
                Channel.NIGHTLY -> nightly
                null -> null
            }
            mine?.release?.let(::noteRelease)
        }
    }

    /**
     * Consulta o canal da versão instalada para o aviso do topo; true se deu certo (com ou sem versão nova). A consulta
     * só mexe no aviso: baixar e instalar continuam pedindo o toque do usuário.
     */
    private suspend fun checkUpdateNotice(): Boolean {
        val channel = noticeChannel(state.appVersion) ?: return true
        val release = updater.fetchChannel(channel).release ?: return false
        noteRelease(release)
        return true
    }

    /** Guarda a hora da consulta e a versão nova (se for), para o aviso sobreviver a reiniciar o Rumo. */
    private fun noteRelease(release: AppRelease) {
        val found = newerThanInstalled(state.appCode, release)
        state.update = found
        if (found == null) state.updateOpen = false
        val editor = prefs.edit().putLong(PREF_UPDATE_CHECKED, System.currentTimeMillis())
        if (found != null) editor.putString(PREF_UPDATE_FOUND, found.toJson()) else editor.remove(PREF_UPDATE_FOUND)
        editor.apply()
    }

    override fun openUpdate() {
        if ((state.gps.speedKmh ?: 0) >= PARKED_BELOW_KMH) {
            state.toast = "Pare o carro para ver a atualização"
            return
        }
        state.updateOpen = true
    }

    override fun installUpdate() {
        val release = state.update ?: return
        val channel = noticeChannel(state.appVersion) ?: return
        startInstall(channel, release)
    }

    override fun installApp(channel: Channel) {
        val info = if (channel == Channel.STABLE) state.settings.stable else state.settings.nightly
        startInstall(channel, info?.release ?: return)
    }

    private fun startInstall(channel: Channel, release: AppRelease) {
        if (state.transfer != null) return
        transferCancelled = false
        val key = channel.name
        val label = "${channel.label} ${release.versionName}"
        state.transfer = Transfer(key, label, 0, release.size)
        lifecycleScope.launch {
            try {
                val apk = updater.downloadApp(
                    release, Installer.folder(this@HomeActivity),
                    onProgress = throttled { done -> state.transfer = Transfer(key, label, done, release.size) },
                    cancelled = { transferCancelled },
                )
                state.transfer = null
                state.toast = when (Installer.install(this@HomeActivity, apk)) {
                    InstallResult.OPENED -> "Confirme a instalação na tela do Android"
                    InstallResult.NEEDS_PERMISSION -> "Libere \"instalar apps desconhecidos\" para o Rumo e toque em Instalar de novo"
                    InstallResult.UNAVAILABLE -> "Este aparelho não tem o instalador de apps"
                }
            } catch (e: IOException) {
                state.transfer = null
                state.toast = transferError(e)
            }
        }
    }

    override fun toggleRegion(id: String) {
        if (state.transfer != null) return
        val settings = state.settings
        val wanted = settings.wanted()
        state.settings = settings.copy(selected = if (id in wanted) wanted - id else wanted + id)
    }

    override fun applyData() {
        val data = state.settings.data ?: return
        if (state.transfer != null) return
        val change = planChange(data.regions, data.common, state.settings.wanted())
        if (change.isEmpty) return
        // Android 9: gravar em /sdcard/Rumo pede a permissão de escrita
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissions.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE))
            state.toast = "Permita salvar arquivos e toque no botão de novo"
            return
        }
        transferCancelled = false
        val label = "Dados do mapa"
        state.transfer = Transfer(DATA_KEY, label, 0, change.bytes)
        // Trocar ou tirar um mapa com ele aberto: o MapLibre mantém o arquivo mapeado, então a tela é recriada no fim
        val touchesMaps = state.mapFiles.isNotEmpty() &&
            (change.download.any { it.dest.endsWith(".pmtiles") } || change.remove.isNotEmpty())
        lifecycleScope.launch {
            try {
                if (change.download.isNotEmpty()) {
                    updater.downloadData(
                        DataPlan(change.download, emptyList(), 0),
                        onProgress = throttled { done -> state.transfer = Transfer(DATA_KEY, label, done, change.bytes) },
                        cancelled = { transferCancelled },
                    )
                }
                change.remove.forEach { updater.removeRegion(it) }
                updater.removeOldLayout()
                state.transfer = null
                state.toast = "Dados do mapa prontos"
                // O plano guardado ficou velho (o que faltava baixar já está no aparelho): só vale uma consulta nova
                state.settings = state.settings.copy(data = null, selected = null)
                if (touchesMaps) {
                    recreate()
                } else {
                    loadMapData()
                    checkUpdates()
                }
            } catch (e: IOException) {
                state.transfer = null
                state.toast = transferError(e)
                // O que já foi baixado vale: consulta de novo para saber o que falta (sem conexão, fica a lista de antes)
                checkUpdates()
            }
        }
    }

    override fun cancelTransfer() {
        transferCancelled = true
    }

    /** Repassa o progresso para a tela no máximo a cada [PROGRESS_EVERY_MS] (o download chama a cada 64 KB). */
    private fun throttled(update: (Long) -> Unit): (Long) -> Unit {
        var last = 0L
        return { done ->
            val now = SystemClock.elapsedRealtime()
            if (now - last >= PROGRESS_EVERY_MS) {
                last = now
                runOnUiThread { update(done) }
            }
        }
    }

    private fun transferError(e: IOException) = when (e) {
        is DownloadCancelled -> "Download cancelado"
        is HttpFailure -> "O GitHub respondeu erro ${e.code}"
        is UnknownHostException, is SocketTimeoutException, is ConnectException -> "Sem conexão: o download continua de onde parou"
        else -> "Falha no download: ${e.message}"
    }

    /**
     * Junta os radares do MapaRadar (arquivo original baixado) com os do OSM, no aparelho. Os do OSM já estão na tela;
     * os da junção entram quando ficam prontos (a primeira vez demora alguns segundos, depois vem do cache).
     */
    private fun joinMapaRadar(found: MapData) {
        lifecycleScope.launch {
            val merged = withContext(Dispatchers.IO) {
                try {
                    // O cache tinha outro nome (radares.bin) antes de o código passar para o inglês
                    File(filesDir, "radares.bin").delete()
                    found.loadMapaRadar(File(filesDir, "radars.bin"))
                } catch (e: Exception) {
                    null
                }
            } ?: return@launch
            // Os dados podem ter sido recarregados enquanto isso
            if (data !== found) return@launch
            found.setMapaRadar(merged)
            state.radars = found.base
        }
    }

    private fun loadMapData() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            state.mapStatus = MapStatus.NO_PERMISSION
            return
        }
        lifecycleScope.launch {
            val found = withContext(drive) {
                data?.close()
                matcher.reset()
                radars.reset()
                MapData.open(this@HomeActivity)?.also { it.setMine(UserPoints.toRadars(minePointsLoaded())) }.also { data = it }
            }
            state.mapFiles = found?.mapFiles.orEmpty()
            state.fontsDir = found?.fontsDir
            state.radars = found?.base.orEmpty()
            state.mineRadars = found?.mine.orEmpty()
            if (found != null) joinMapaRadar(found)
            state.mapStatus = if (found == null) MapStatus.MISSING else MapStatus.READY
            state.road = null
            state.radar = null
        }
    }

    /**
     * A cada posição do GPS (1 por segundo): via atual e limite, velocidade acima dele, radar à frente e os sons.
     * Mudanças só de estado ([fix] falso) não refazem a conta. Se a conta anterior ainda não acabou, a posição fica
     * guardada e é a próxima a ser calculada (as do meio são puladas, nunca a última).
     */
    private fun onGps(info: GpsInfo, fix: Boolean) {
        state.gps = info
        val lat = info.lat
        val lon = info.lon
        if (info.status != GpsStatus.OK || lat == null || lon == null) {
            state.road = null
            state.radar = null
            state.over = false
            alerts.stop()
            return
        }
        if (!fix) return
        // Nomes das rádios valem até 50 km do lugar onde foram vistos: longe disso a lista zera
        if (radioNames.onPosition(lat, lon)) saveRadioNames()
        // O carro desliga sem aviso: guarda a posição de tempos em tempos, não só ao sair da tela
        if (SystemClock.elapsedRealtime() - savedAt > SAVE_EVERY_MS) savePosition(info)
        if (driveJob?.isActive == true) {
            pendingFix = info
            return
        }
        drive(info, lat, lon)
    }

    private fun drive(info: GpsInfo, lat: Double, lon: Double) {
        val speed = info.speedKmh ?: 0
        driveJob = lifecycleScope.launch {
            val (road, radar, entered) = withContext(drive) {
                val d = data
                if (d == null) {
                    Triple<RoadMatch?, RadarAlert?, Boolean>(null, null, false)
                } else {
                    val road = matcher.update(lat, lon, info.bearing, speed, d.runsNear(lat, lon, RoadMatcher.MAX_DISTANCE_M + 10))
                    val entered = radars.update(lat, lon, info.bearing, speed, d.radars)
                    Triple(road, radars.current, entered)
                }
            }
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
            state.road = road
            state.radar = radar
            state.over = overSpeed.update(info.speedKmh, road?.limit)
            if (entered && radar?.radar?.chimes == true) alerts.chime()
            alerts.alarm(radar?.takeIf { it.sound }?.closeness(radars.zoneM))
            // Chegou uma posição enquanto esta era calculada: calcula a mais recente (se o GPS ainda estiver bom)
            val next = pendingFix
            pendingFix = null
            val nextLat = next?.lat
            val nextLon = next?.lon
            if (next != null && nextLat != null && nextLon != null && state.gps.status == GpsStatus.OK) drive(next, nextLat, nextLon)
        }
    }

    private fun start(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            state.toast = "Não foi possível abrir o app"
        }
    }

    private fun loadApps() {
        lifecycleScope.launch {
            state.apps = withContext(Dispatchers.Default) { Apps.load(this@HomeActivity) }
            state.hasVoice = Apps.voiceIntent(this@HomeActivity) != null
            detectSource()
        }
    }

    /** Fonte que um app representa, ou null se não for de áudio (abrir os ajustes não muda a fonte). */
    private fun sourceOf(pkg: String): Source? {
        if (pkg == Apps.ZLINK) return Source.ZLINK
        val app = state.apps.find { it.pkg == pkg } ?: return null
        return when {
            Apps.isRadio(app) -> Source.RADIO
            Apps.isAudio(app) -> Source.OTHER
            else -> null
        }
    }

    /**
     * Só em aparelho sem o serviço sourceinfo: com "Acesso ao uso", pega o último app de áudio que foi para a tela.
     * Com o sourceinfo ele atrapalharia (ao abrir, marcava o ZLINK com o rádio tocando).
     */
    @Suppress("DEPRECATION") // MOVE_TO_FOREGROUND virou ACTIVITY_RESUMED no Android 10, com o mesmo valor
    private fun detectSource() {
        state.usageAccess = hasUsageAccess()
        if (!state.usageAccess || !sourceInfoMissing) return
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 12 * 3_600_000L, now)
        val e = UsageEvents.Event()
        var found: Source? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) sourceOf(e.packageName)?.let { found = it }
        }
        found?.let { state.source = it }
    }

    /**
     * Pacote ou classe vindos do sourceinfo → fonte do card; vazio não muda nada. Guarda o último valor válido:
     * quando o sourceinfo responder vazio ao abrir, é ele que vale.
     */
    private fun applyAudioOwner(name: String) {
        state.source = when (audioOwnerOf(name) ?: return) {
            AudioOwner.ZLINK -> Source.ZLINK
            AudioOwner.RADIO -> Source.RADIO
            AudioOwner.OTHER -> sourceOf(name) ?: Source.OTHER
        }
        // Outra fonte tocando: o mudo que o Rumo pôs no rádio não pode ficar para depois
        if (state.source != Source.RADIO && state.radioMuted) setRadioMuted(false)
        // O ZLINK repete o aviso quase a cada segundo: só grava quando muda
        if (name != savedOwner) {
            savedOwner = name
            prefs.edit().putString(PREF_SOURCE, name).apply()
        }
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private companion object {
        /** Distância do aviso de radar (padrão do protótipo). */
        /** Raio em que se procura a rua do ponto a mover. */
        const val MOVE_WAY_SEARCH_M = 45.0

        /** Ao marcar um ponto, a rua mais perto que vale para encaixá-lo (raio da busca e distância máxima). */
        const val SNAP_SEARCH_M = 40.0
        const val SNAP_MAX_M = 30.0

        /** Depois de mover um ponto, só volta para uma rua a até esta distância. */
        const val SNAP_MOVE_M = 12.0
        const val PREF_RADAR_ZONE = "radar_zone_m"
        const val PREF_RADAR_MARGIN = "radar_margin_kmh"
        const val PREF_THEME = "theme"
        /** Quanto esperar o "Reiniciar" aparecer no menu de energia antes de avisar que não achou. */
        const val POWER_MENU_WAIT_MS = 7_000L
        const val MAX_KEY_LOG = 60
        const val GETEVENT_NOTES = 3
        const val PROBE_SECONDS = 20
        const val PREF_LAT = "last_lat"
        const val PREF_LON = "last_lon"
        const val PREF_BEARING = "last_bearing"
        const val SAVE_EVERY_MS = 15_000L
        const val HEADLIGHT_EVERY_MS = 2_000L
        const val MEDIA_PROBE_SECONDS = 60
        const val PREF_SOURCE = "last_source"
        const val RADIO_PKG = "com.hcn.autoradio"
        const val RADIO_EVERY_MS = 1_000L
        const val RADIO_IDLE_EVERY_MS = 5_000L
        const val RADIO_NAMES_EVERY_MS = 60_000L
        const val PREF_RADIO_UNIT = "radio_goto_unit"
        const val PREF_FAVORITES = "favorites"
        const val PREF_RADIO_NAMES = "radio_names"
        const val PREF_SCAN = "radio_found"
        const val PREF_RADIO_MUTED = "radio_muted"
        const val RADIO_LOG_LINES = 300
        const val MAX_ACTIONS = 2_000
        /** As configurações da home só abrem abaixo desta velocidade. */
        const val PARKED_BELOW_KMH = 5
        const val PROGRESS_EVERY_MS = 250L
        /** Hora (ms) da última consulta de versão nova que deu certo, e a versão achada (formato do rumo.json). */
        const val PREF_UPDATE_CHECKED = "update_checked_at"
        const val PREF_UPDATE_FOUND = "update_found"
        /** Ao abrir o Rumo, espera um pouco antes da consulta de versão nova, para não competir com a abertura. */
        const val UPDATE_FIRST_DELAY_MS = 15_000L
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
