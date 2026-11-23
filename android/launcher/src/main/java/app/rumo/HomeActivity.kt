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
import android.os.Build
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
import app.rumo.core.UPDATE_RETRY_MS
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
import app.rumo.device.BluetoothProbe
import app.rumo.device.CrashReport
import app.rumo.device.ObdDevice
import app.rumo.core.Maintenance
import app.rumo.core.Odometer
import app.rumo.core.OilPlan
import app.rumo.core.OilType
import app.rumo.core.TripDiary
import app.rumo.core.DrivingStyle
import app.rumo.core.TripReading
import app.rumo.device.ObdLink
import app.rumo.device.ObdScan
import app.rumo.device.SavedFiles
import app.rumo.core.BrState
import app.rumo.core.WebFilter
import app.rumo.core.WebRadio
import app.rumo.core.WebStation
import app.rumo.core.WebStatus
import app.rumo.device.WebPlayback
import app.rumo.device.WebRadioCatalog
import app.rumo.device.WebRadioPlayer
import app.rumo.ui.RadioBand
import app.rumo.ui.WebNow
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
import app.rumo.core.radarLabel
import app.rumo.core.RadarWatch
import app.rumo.core.directionOf
import app.rumo.core.nearestWayHint
import app.rumo.core.radioPlayStarts
import app.rumo.core.shouldResumeRadio
import app.rumo.core.UserPoint
import app.rumo.core.UserPoints
import app.rumo.core.Backup
import app.rumo.ui.BackupFound
import app.rumo.core.RoadMatch
import app.rumo.core.RoadMatcher
import app.rumo.device.Aidl
import app.rumo.device.AlertLog
import app.rumo.shared.Alerts
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
    private val alertLog = AlertLog()
    // Registro do scanner com a chave desligada (Rumo/obd-ignicao.log): quando o carro e o scanner deixam de responder
    private val obdLog = AlertLog("obd-ignicao.log", "obd-ignicao-anterior.log")
    private var obdLogStatus: app.rumo.device.ObdStatus? = null
    private var obdLogBeatAt = 0L
    private var obdLogCopyAt = 0L
    // Para o registro dos alertas: só as mudanças (radar que entrou ou saiu, bipes pedidos ou não, GPS que caiu ou voltou)
    private var loggedRadar: Long? = null
    private var loggedBeeps = false
    private var loggedGps: GpsStatus? = null

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
            // Um som do Rumo ou do app do online (aviso) na frente: o rádio do aparelho não está tocando (o play do card
            // deve ligá-lo, não calá-lo)
            lastRawOwner = if (name == "app.rumo" || name.startsWith("app.rumo.")) "" else name
            // Só as mudanças (o ZLINK repete o aviso quase a cada segundo)
            if (name != loggedOwner) {
                loggedOwner = name
                radioLog("sourceinfo: \"$name\"")
            }
            applyAudioOwner(name)
        }
    }
    private val radioNames = RadioNames()
    private val radioStation = RadioStation(radioNames)
    // Último pacote que o sourceinfo informou, inclusive vazio (rádio parado)
    private var lastRawOwner: String? = null
    private var sourceInfoMissing = false
    private val radioControl by lazy {
        RadioControl(
            this,
            prefs.getInt(PREF_RADIO_UNIT, 0).takeIf { it > 0 },
            onLost = { radioLog("o serviço do app de rádio caiu (onServiceDisconnected)") },
        ) { unit ->
            prefs.edit().putInt(PREF_RADIO_UNIT, unit).apply()
        }
    }
    // Rádio online: o player (no serviço de mídia, ligado na primeira vez que precisa), o catálogo e as listas dele
    private var webPlayerOrNull: WebRadioPlayer? = null
    private val webPlayer: WebRadioPlayer
        get() = webPlayerOrNull ?: WebRadioPlayer(this, ::onWebPlayback).also {
            it.onConnected = ::onCompanionConnected
            it.onLog = { line -> alertLog.add("[app do online] $line") }
            webPlayerOrNull = it
        }
    private val webCatalog by lazy { WebRadioCatalog(File(filesDir, "web-radio")) }
    private var webCountry: List<WebStation> = emptyList()
    private var webNear: List<WebStation> = emptyList()
    private var webState: BrState? = null
    private var webLoading = false
    /** Quando o rádio online foi desligado por inteiro (o FM espera um pouco depois disso). */
    private var webReleasedAt = 0L
    private var webRadioFetched = false
    private var loggedOwner: String? = null
    // Registro dos comandos do rádio em arquivo, gravado numa thread só (na ordem), e o da sessão anterior
    private val radioLogFile by lazy { File(filesDir, "radio-log.txt") }
    private val logWriter = Executors.newSingleThreadExecutor()
    @Volatile private var previousRadioLog: List<String> = emptyList()
    // Scanner OBD2 (Bluetooth): a ligação com o carro. A leitura completa só roda com a folha do carro aberta.
    private val obd by lazy {
        ObdLink(this, detail = { state.sheet == Sheet.CAR }, onEvent = { obdLog.add(it) }, onSnapshot = { snap ->
            state.obd = snap
            onObdSnapshot(snap)
        })
    }
    // Km do painel pelo contador do carro (PID 31); o km só vale depois de o dono informar o do painel
    private lateinit var odometer: Odometer
    // Diário de viagens (arquivo interno, uma viagem por linha)
    private val diaryFile by lazy { File(filesDir, "trips.csv") }
    private lateinit var diary: TripDiary
    // Restauração de backup em curso: a tela é recriada e a nova aplica o backup antes de ler qualquer coisa
    @Volatile private var restoring = false
    private val restorePending by lazy { File(filesDir, "restore-pending.txt") }
    private var diarySavedAt = 0L
    private var odometerKnown = false
    private var cancelObdSearch: (() -> Unit)? = null
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
        override fun onReceive(context: Context, intent: Intent) {
            loadApps()
            // Instalou ou atualizou o app do rádio online: com o Rumo na tela, liga nele (os avisos passam a sair por ele)
            refreshWebRadioApp(fetch = false)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) webPlayer.attach()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Backup escolhido na tela anterior: aplica antes de ler as configurações abaixo
        val restoreNote = applyPendingRestore()
        // Fechou com erro: o relatório vai para a pasta Rumo e o pendrive, com os últimos comandos do rádio
        CrashReport.install(this)
        // Sessão anterior que não passou pelo onStop: o processo foi encerrado com o Rumo na tela
        val endedOutside = prefs.getBoolean(PREF_SESSION_OPEN, false)
        // Primeiro da fila do registro: guarda o da sessão anterior e começa um arquivo novo
        logWriter.execute {
            previousRadioLog = try {
                radioLogFile.takeIf { it.isFile }?.readLines().orEmpty()
            } catch (e: Exception) {
                emptyList()
            }
            radioLogFile.delete()
        }
        CrashReport.extra = { state.radioLog.takeLast(CRASH_LOG_LINES) + "fonte: ${state.source} · tela de rádio: ${state.radioBand} · online: ${state.web.status}" }
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
        alerts = Alerts(this, alertLog::add)
        state.favorites = Favorites.decode(prefs.getString(PREF_FAVORITES, null))
        lifecycleScope.launch { state.myPoints = withContext(drive) { minePointsLoaded() } }
        state.radarZoneM = RadarSettings.zone(prefs.getInt(PREF_RADAR_ZONE, RadarSettings.ZONE_DEFAULT))
        state.radarMarginKmh = RadarSettings.margin(prefs.getInt(PREF_RADAR_MARGIN, RadarSettings.MARGIN_DEFAULT))
        radars.zoneM = state.radarZoneM
        radars.marginKmh = state.radarMarginKmh
        state.radioMuted = prefs.getBoolean(PREF_RADIO_MUTED, false)
        prefs.getString(PREF_OBD_ADDRESS, null)?.let { state.obdScanner = ObdDevice(prefs.getString(PREF_OBD_NAME, null), it) }
        state.obdFuelFactor = prefs.getFloat(PREF_OBD_FUEL, 1f).toDouble().coerceIn(0.5, 2.0)
        obd.fuelFactor = state.obdFuelFactor
        state.cardPage = prefs.getInt(PREF_CARD_PAGE, 0).coerceIn(0, 1)
        state.alertsViaRumo = prefs.getBoolean(PREF_ALERTS_VIA_RUMO, false)
        state.tankLiters = prefs.getInt(PREF_TANK_LITERS, TANK_DEFAULT_L).coerceIn(TANK_MIN_L, TANK_MAX_L)
        state.oilPlan = loadOilPlan()
        odometerKnown = prefs.contains(PREF_ODO_KM)
        odometer = Odometer(
            prefs.getString(PREF_ODO_KM, null)?.toDoubleOrNull() ?: 0.0,
            prefs.getInt(PREF_ODO_COUNTER, -1).takeIf { it >= 0 },
        )
        state.odometerKm = if (odometerKnown) odometer.km else null
        diary = TripDiary(TripDiary.decode(runCatching { diaryFile.takeIf { it.exists() }?.readText() }.getOrNull()))
        state.trips = diary.trips.toList()
        state.webFavorites = WebRadio.decode(prefs.getString(PREF_WEB_FAVORITES, null))
        state.web = WebNow(WebRadio.decode(prefs.getString(PREF_WEB_LAST, null)).firstOrNull())
        if (prefs.getString(PREF_RADIO_BAND, null) == RadioBand.WEB.name) {
            state.radioBand = RadioBand.WEB
        }
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
        state.updateCheckedAt = prefs.getLong(PREF_UPDATE_CHECKED, 0L)
        // O registro dos comandos do rádio vai para um arquivo (sobrevive a um fechamento); o da sessão anterior entra no
        // relatório de fechamento, se houver
        lifecycleScope.launch {
            val crashed = withContext(Dispatchers.IO) {
                // Espera a fila do registro ler o arquivo da sessão anterior (pedido no começo do onCreate)
                logWriter.submit {}.get()
                CrashReport.collect(this@HomeActivity, previousRadioLog, endedOutside)
            }
            if (crashed) state.toast = "O Rumo foi fechado da última vez: relatório em Rumo/${CrashReport.FILE}"
        }
        restoreNote?.let { state.toast = it }
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

        // Versão nova no canal da instalada, de 4 em 4 horas. Só com o Rumo na tela (nada roda em segundo plano): ao
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

        // Gravar na pasta Rumo (registros, relatórios, dados do mapa) pede a permissão de escrita até o Android 9; sem ela, só
        // as cópias no pendrive saíam
        val wanted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_EXTERNAL_STORAGE) +
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptyList()
        val missing = wanted
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }

    override fun onStart() {
        super.onStart()
        prefs.edit().putBoolean(PREF_SESSION_OPEN, true).commit()
        // Ligado no app do rádio online enquanto na tela: os avisos saem por ele
        webPlayer.attach()
        startObd()
        maintNotice()
        alertLog.add("tela: Rumo na tela (${state.appVersion}) ${alerts.audioState()}")
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
        prefs.edit().putBoolean(PREF_SESSION_OPEN, false).apply()
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
        alarm(null)
        webPlayerOrNull?.detach()
        // Fora da tela, o Rumo larga o scanner (nada em segundo plano); a viagem do consumo fica na memória
        obd.stop()
        saveDiary()
        obdLog.copyTo(this, getExternalFilesDirs(null).drop(1).filterNotNull())
        cancelObdSearch?.invoke()
        state.radar = null
        loggedGps = null
        alertLog.add("tela: Rumo saiu da tela (sem GPS e sem alertas até voltar)")
        alertLog.copyTo(this, getExternalFilesDirs(null).drop(1).filterNotNull())
        super.onStop()
    }

    /** Acompanha se há rede, para a aba Atualizações consultar de novo quando a conexão volta. */
    private fun watchNetwork() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        onNetwork(manager.activeNetwork != null)
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = runOnUiThread { onNetwork(true) }
            // Trocar de rede também avisa a perda da antiga: só vale como "sem rede" se não sobrou nenhuma
            override fun onLost(network: Network) = runOnUiThread { onNetwork(manager.activeNetwork != null) }
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
        refreshWebRadioApp(fetch = false)
    }

    override fun onDestroy() {
        stopKeyTest()
        unregisterReceiver(packagesChanged)
        alerts.stop()
        radioControl.disconnect()
        // O serviço do rádio online continua tocando sem a tela; a próxima tela liga nele de novo
        webPlayerOrNull?.release()
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
        return targets.mapNotNull { dir -> File(dir, file).takeIf { SavedFiles.write(this, it, text) }?.path }
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
        refreshWebRadioApp(fetch = state.radioBand == RadioBand.WEB)
        if (state.radioBand == RadioBand.WEB) {
            // Abrir a tela põe o rádio online para tocar, como o FM
            if (!webPlaying()) playWeb(null)
            loadWebCatalog()
            return
        }
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
        val tunerCmd = cmd is RadioCmd.Play || cmd is RadioCmd.Tune || cmd is RadioCmd.Seek || cmd is RadioCmd.Step || cmd is RadioCmd.OpenApp
        // O FM volta: o rádio online para (os dois juntos se sobrepõem)
        if ((tunerCmd || cmd is RadioCmd.Scan) && (state.radioBand == RadioBand.WEB || webPlaying())) leaveWeb()
        if (tunerCmd) {
            if (state.radioMuted) setRadioMuted(false)
            if (lastRawOwner == "") lastRawOwner = null
        }
        when (cmd) {
            RadioCmd.OpenApp -> openRadio()
            RadioCmd.Play -> tunerCommand {
                if (cameFromWeb()) return@tunerCommand
                settleRadio()
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
                if (state.source != Source.RADIO && !cameFromWeb()) {
                    settleRadio()
                    radioLog(withContext(Dispatchers.IO) { radioControl.playAudio() })
                }
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
                val lines = state.radioLog + listOf("", "Sessão anterior:") + previousRadioLog
                val saved = withContext(Dispatchers.IO) {
                    val log = listOf("", "Log do Android (só as linhas do Rumo):") + CrashReport.logcat(REPORT_LOGCAT_LINES)
                    saveReport("Teste dos comandos do rádio", "radio-commands-test.txt", lines + log)
                }
                state.toast = if (saved.isEmpty()) "Não consegui salvar o registro" else "Registro salvo: ${saved.first()}"
            }
            is RadioCmd.Band -> when {
                cmd.web && state.radioBand != RadioBand.WEB -> {
                    refreshWebRadioApp(fetch = true)
                    if (state.radioScan?.running == true) scanCancelled = true
                    playWeb(null)
                    loadWebCatalog()
                }
                !cmd.web && state.radioBand == RadioBand.WEB -> {
                    radioLog("tela de rádio: volta para o FM")
                    leaveWeb()
                    radio(RadioCmd.Play)
                    lifecycleScope.launch { refreshRadio() }
                }
            }
            is RadioCmd.WebPlay -> playWeb(cmd.station)
            RadioCmd.WebToggle -> if (webPlaying()) stopWeb() else playWeb(null)
            is RadioCmd.WebStep -> webStep(cmd.forward)
            is RadioCmd.WebFavorite -> {
                val had = state.webFavorites.any { it.id == cmd.station.id }
                state.webFavorites = WebRadio.toggle(state.webFavorites, cmd.station)
                prefs.edit().putString(PREF_WEB_FAVORITES, WebRadio.encode(state.webFavorites)).apply()
                state.toast = if (had) "${cmd.station.name} saiu das favoritas" else "${cmd.station.name} nas favoritas"
            }
            is RadioCmd.WebPickFilter -> {
                state.webBrowse = state.webBrowse.copy(filter = cmd.filter)
                publishWebBrowse()
            }
            is RadioCmd.ListenFm -> radio(RadioCmd.Tune(cmd.khz))
        }
    }

    // ---- rádio online ----

    private fun webPlaying() = state.web.status == WebStatus.CONNECTING || state.web.status == WebStatus.LIVE

    private fun setRadioBand(band: RadioBand) {
        if (state.radioBand == band) return
        state.radioBand = band
        prefs.edit().putString(PREF_RADIO_BAND, band.name).apply()
    }

    /**
     * Toca [station] (ou a última, a primeira favorita, a primeira da lista). Cala o FM antes, soltando a ligação com o
     * serviço do rádio (é o que o cala de fato). Sem rede, não tenta: fica "sem Wi-Fi" e volta sozinho quando ela vier.
     */
    private fun playWeb(station: WebStation?) {
        setRadioBand(RadioBand.WEB)
        val s = station ?: state.web.station ?: state.webFavorites.firstOrNull() ?: state.webBrowse.stations.firstOrNull()
        if (s == null) {
            state.web = WebNow(null, WebStatus.OFF)
            return
        }
        if (s.id != state.web.station?.id) prefs.edit().putString(PREF_WEB_LAST, WebRadio.encode(listOf(s))).apply()
        // Sem o app do rádio online, não cala o FM: a tela mostra como instalar
        state.webRadioInstalled = WebRadioPlayer.installedVersion(this)
        if (state.webRadioInstalled == null) {
            state.web = WebNow(s, WebStatus.OFF)
            return
        }
        if (state.source == Source.RADIO || radioControl.connected) {
            if (!state.radioMuted) setRadioMuted(true) else radioControl.disconnect()
        }
        state.source = Source.WEB
        if (!state.online) {
            webPlayerOrNull?.stop()
            state.web = WebNow(s, WebStatus.NO_WIFI)
            return
        }
        state.web = WebNow(s, WebStatus.CONNECTING)
        radioLog("online: tocar ${s.name} (${s.codec} ${s.kbps} kbps${if (s.hls) ", HLS" else ""})")
        webPlayer.play(s)
    }

    private fun stopWeb(status: WebStatus = WebStatus.OFF) {
        if (webPlayerOrNull != null) radioLog("online: parar ($status)")
        webPlayerOrNull?.stop()
        state.web = state.web.copy(status = status, title = null)
    }

    /**
     * Volta para o FM: desliga o rádio online (o app dele solta o player e se encerra) e a tela mostra o FM.
     */
    private fun leaveWeb() {
        stopWeb()
        // Para o player, mas a ligação fica (os avisos de radar saem pelo app do online)
        webPlayerOrNull?.let {
            it.stop()
            webReleasedAt = SystemClock.elapsedRealtime()
        }
        setRadioBand(RadioBand.FM)
        if (state.source == Source.WEB) state.source = Source.RADIO
    }

    /** Anterior/próxima: pelas favoritas online; sem favoritas, pela lista do Explorar. */
    private fun webStep(forward: Boolean) {
        val list = state.webFavorites.ifEmpty { state.webBrowse.stations }
        WebRadio.step(list, state.web.station?.id, forward)?.let(::playWeb)
            ?: run { state.toast = "Favorite estações online para trocar entre elas" }
    }

    private fun onWebPlayback(p: WebPlayback) {
        val w = state.web
        val station = p.stationId?.let { id ->
            w.station?.takeIf { it.id == id } ?: (state.webFavorites + webNear + webCountry).firstOrNull { it.id == id }
        } ?: w.station
        // Parado pelo Rumo por falta de Wi-Fi: o "sem Wi-Fi" fica até a rede voltar
        val status = if (p.status == WebStatus.OFF && (w.status == WebStatus.LOST || w.status == WebStatus.NO_WIFI)) w.status else p.status
        if (status != w.status) radioLog("online: ${w.status} → $status")
        state.web = WebNow(station, status, p.title.takeIf { status == WebStatus.LIVE })
        if ((status == WebStatus.CONNECTING || status == WebStatus.LIVE) && state.source != Source.WEB) {
            state.source = Source.WEB
            setRadioBand(RadioBand.WEB)
        }
        if (status == WebStatus.ERROR && w.status != WebStatus.ERROR && state.sheet != Sheet.RADIO) {
            state.toast = "A rádio online não respondeu"
        }
    }

    /** A rede caiu ou voltou: para o rádio online (sem Wi-Fi) ou retoma o que estava tocando. */
    private fun onNetwork(online: Boolean) {
        if (state.online == online) return
        state.online = online
        if (!online && webPlaying()) {
            stopWeb(WebStatus.LOST)
            state.toast = "O Wi-Fi caiu: rádio online parado"
        } else if (online && state.source == Source.WEB && (state.web.status == WebStatus.LOST || state.web.status == WebStatus.NO_WIFI)) {
            playWeb(state.web.station)
        }
        if (online && state.radioBand == RadioBand.WEB) loadWebCatalog()
    }

    /**
     * Lista do Explorar: primeiro a cópia guardada (aparece na hora, até sem rede); com rede e a cópia velha (mais de um
     * dia), consulta o catálogo: as mais ouvidas do país e as do estado onde o carro está.
     */
    private fun loadWebCatalog() {
        if (webLoading) return
        webLoading = true
        lifecycleScope.launch {
            val g = state.gps
            val lat = g.lat ?: state.mapStart?.lat
            val lon = g.lon ?: state.mapStart?.lon
            webState = if (lat != null && lon != null) {
                val region = withContext(drive) { data?.regionAt(lat, lon) }
                WebRadio.stateOf(region) ?: WebRadio.nearestState(lat, lon)
            } else {
                null
            }
            val st = webState
            val stateKey = st?.let { "state-${it.code.lowercase()}" }
            withContext(Dispatchers.IO) {
                if (webCountry.isEmpty()) webCountry = webCatalog.cached(KEY_COUNTRY).orEmpty()
                webNear = stateKey?.let(webCatalog::cached).orEmpty()
            }
            publishWebBrowse()
            val stale = !webCatalog.fresh(KEY_COUNTRY) || (stateKey != null && !webCatalog.fresh(stateKey))
            if (state.online && stale) {
                state.webBrowse = state.webBrowse.copy(loading = true, error = null)
                val error = withContext(Dispatchers.IO) {
                    try {
                        webCountry = webCatalog.fetch(WebRadio.countryQuery(), KEY_COUNTRY)
                        if (st != null && stateKey != null) webNear = webCatalog.fetch(WebRadio.stateQuery(st), stateKey)
                        null
                    } catch (e: IOException) {
                        "Não consegui abrir a lista de estações. Tente de novo mais tarde."
                    }
                }
                state.webBrowse = state.webBrowse.copy(loading = false, error = error)
                publishWebBrowse()
            }
            webLoading = false
        }
    }

    private fun publishWebBrowse() {
        val b = state.webBrowse
        val g = state.gps
        val list = when (b.filter) {
            WebFilter.NEAR -> (webNear + WebRadio.near(webCountry, webState, g.lat ?: state.mapStart?.lat, g.lon ?: state.mapStart?.lon))
                .distinctBy { it.id }
            else -> WebRadio.filter(webCountry, b.filter)
        }
        state.webBrowse = b.copy(stations = list, place = webState?.name)
    }

    /**
     * O serviço do app de rádio acabou de nascer (o Rumo o tinha soltado para calar o FM, ao ir para o rádio online
     * ou no play do card): espera ele se ajeitar antes de pedir o áudio. Ao voltar do online para o FM, o serviço caía.
     */
    /**
     * Logo depois de desligar o rádio online: o `requestPlayAudio` fazia o firmware encerrar o Rumo (o último app de
     * mídia) ao pôr o rádio como fonte. Nesse caso não pede o áudio: a ligação com o serviço do rádio já o põe para
     * tocar (como ao abrir a tela de rádio).
     */
    private fun cameFromWeb(): Boolean {
        val recent = webReleasedAt > 0 && SystemClock.elapsedRealtime() - webReleasedAt < CAME_FROM_WEB_MS
        if (recent) radioLog("vindo do online: sem requestPlayAudio (só a ligação com o serviço)")
        return recent
    }

    private suspend fun settleRadio() {
        val since = SystemClock.elapsedRealtime() - radioControl.connectedAt
        if (since < RADIO_SETTLE_MS) {
            radioLog("espera o serviço do rádio (${RADIO_SETTLE_MS - since} ms)")
            delay(RADIO_SETTLE_MS - since)
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
        logWriter.execute {
            try {
                if (radioLogFile.length() > RADIO_LOG_FILE_MAX) radioLogFile.delete()
                radioLogFile.appendText(stamped + "\n")
            } catch (e: Exception) {
            }
        }
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
        // Rádio online: o play toca e para; anterior e próxima andam pelas favoritas online
        if (state.source == Source.WEB) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> radio(RadioCmd.WebToggle)
                KeyEvent.KEYCODE_MEDIA_NEXT -> radio(RadioCmd.WebStep(true))
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> radio(RadioCmd.WebStep(false))
            }
            return
        }
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
        // Com o rádio online parado, trocar de estação no app do rádio devolve a fonte ao FM
        if (tuned && state.source == Source.WEB && !webPlaying()) {
            radioLog("estação trocada no app do rádio: fonte volta ao FM")
            setRadioBand(RadioBand.FM)
            state.source = Source.RADIO
        }
        if (tuned && state.source != Source.RADIO && state.source != Source.WEB) applyAudioOwner(RADIO_PKG)
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

    @Volatile private var soundTesting = false

    /** Aviso de entrada e bipes do longe ao perto (a cada 0,5 s, 7 s no total), só para ouvir se o som sai. */
    override fun testSound() {
        if (soundTesting) return
        soundTesting = true
        alertLog.add("teste do som: iniciado ${alerts.audioState()}")
        lifecycleScope.launch {
            try {
                state.toast = "Teste do som: o aviso e os bipes, do longe ao perto"
                alerts.lastError = null
                // Pelo mesmo caminho dos avisos de verdade (com o online tocando, pelo app dele)
                chime()
                delay(1500)
                for (i in 0..SOUND_TEST_STEPS) {
                    alarm(i.toFloat() / SOUND_TEST_STEPS)
                    delay(SOUND_TEST_STEP_MS)
                }
            } finally {
                alarm(null)
                soundTesting = false
            }
            delay(400)
            val error = alerts.lastError
            state.toast = if (error == null) "O som saiu? Se não, o aparelho não tocou: veja o volume" else "O som falhou: $error"
        }
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

    override fun probeBluetooth() {
        if (probeJob?.isActive == true) return
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            permissions.launch(needed.toTypedArray())
            state.toast = "Permita o acesso e toque de novo"
            return
        }
        probeJob = lifecycleScope.launch {
            state.toast = "Teste do Bluetooth: com o scanner ligado no carro, até 2 minutos…"
            val report = withContext(Dispatchers.IO) {
                BluetoothProbe.run(this@HomeActivity) { msg -> runOnUiThread { state.toast = msg } }
            }
            state.toast = null
            showReport("Teste do Bluetooth", "obd-bluetooth-test.txt", report.summary, report.full)
        }
    }

    // ---- carro (scanner OBD2 pelo Bluetooth) ----

    /** Liga no scanner escolhido (se houver e se o Bluetooth for permitido). */
    private fun startObd() {
        val scanner = state.obdScanner ?: return
        if (obd.running) return
        obd.start(scanner.address)
    }

    override fun openCar(view: Int) {
        state.carView = view
        state.sheet = Sheet.CAR
        startObd()
    }

    override fun setTankLiters(liters: Int) {
        state.tankLiters = liters.coerceIn(TANK_MIN_L, TANK_MAX_L)
        prefs.edit().putInt(PREF_TANK_LITERS, state.tankLiters).apply()
    }

    override fun carSearch() {
        if ((state.gps.speedKmh ?: 0) >= PARKED_BELOW_KMH) {
            state.toast = "Procurar o scanner só com o carro parado"
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            state.toast = "Permita a localização (o Android a exige para procurar) e toque de novo"
            return
        }
        cancelObdSearch?.invoke()
        state.obdFound = ObdScan.bonded()
        state.obdSearching = true
        // A procura e a conexão com o scanner brigam pelo rádio Bluetooth: para a conexão enquanto procura
        obd.stop()
        cancelObdSearch = ObdScan.start(
            this, OBD_SEARCH_MS,
            onFound = { d -> if (state.obdFound.none { it.address == d.address }) state.obdFound = state.obdFound + d },
            onDone = {
                state.obdSearching = false
                cancelObdSearch = null
                if (state.obdFound.isEmpty()) state.toast = "Nenhum dispositivo achado: o scanner está ligado?"
                startObd()
            },
        )
    }

    override fun carChoose(device: ObdDevice) {
        cancelObdSearch?.invoke()
        state.obdScanner = device
        state.obdFound = emptyList()
        prefs.edit().putString(PREF_OBD_ADDRESS, device.address).putString(PREF_OBD_NAME, device.name).apply()
        state.toast = "Scanner ${device.name ?: ""} escolhido"
        obd.stop()
        startObd()
    }

    override fun carForget() {
        cancelObdSearch?.invoke()
        obd.stop()
        state.obdScanner = null
        state.obdFound = emptyList()
        prefs.edit().remove(PREF_OBD_ADDRESS).remove(PREF_OBD_NAME).apply()
    }

    override fun carReconnect() {
        obd.stop()
        startObd()
    }

    override fun carFuelFactor(percent: Int) {
        val f = (Math.round((state.obdFuelFactor + percent / 100.0) * 100) / 100.0).coerceIn(0.5, 2.0)
        state.obdFuelFactor = f
        obd.fuelFactor = f
        prefs.edit().putFloat(PREF_OBD_FUEL, f.toFloat()).apply()
    }

    override fun carResetTrip() {
        obd.resetTrip()
        state.obd = state.obd.let { s -> app.rumo.device.ObdSnapshot(s.status, s.message, s.voltage, s.values, s.supported, s.milOn, s.dtcCount, s.dtcs, s.litersPerHour, s.kmPerLiter, null, 0.0, 0.0) }
        state.toast = "Viagem do consumo zerada"
    }

    override fun setAlertsViaRumo(on: Boolean) {
        state.alertsViaRumo = on
        prefs.edit().putBoolean(PREF_ALERTS_VIA_RUMO, on).apply()
        alertLog.add("teste: avisos com o FM pelo Rumo ${if (on) "ligado" else "desligado"}")
        state.toast = if (on) "Teste ligado: com o FM, os avisos saem pelo Rumo" else "Teste desligado: os avisos saem pelo app do rádio online"
    }

    // ---- manutenção (troca de óleo) ----

    private fun loadOilPlan(): OilPlan? {
        val type = prefs.getString(PREF_OIL_TYPE, null)?.let { t -> OilType.entries.find { it.name == t } } ?: return null
        val date = prefs.getString(PREF_OIL_LAST_DATE, null)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: return null
        return OilPlan(
            type,
            prefs.getInt(PREF_OIL_INTERVAL_KM, type.intervalKm),
            prefs.getInt(PREF_OIL_INTERVAL_MONTHS, type.intervalMonths),
            date,
            prefs.getInt(PREF_OIL_LAST_KM, 0),
            prefs.getString(PREF_OIL_VISCOSITY, null),
        )
    }

    private fun saveOilPlan(plan: OilPlan?) {
        state.oilPlan = plan
        val e = prefs.edit()
        if (plan == null) {
            e.remove(PREF_OIL_TYPE).remove(PREF_OIL_INTERVAL_KM).remove(PREF_OIL_INTERVAL_MONTHS).remove(PREF_OIL_LAST_DATE).remove(PREF_OIL_LAST_KM)
                .remove(PREF_OIL_VISCOSITY)
        } else {
            e.putString(PREF_OIL_TYPE, plan.type.name)
                .putInt(PREF_OIL_INTERVAL_KM, plan.intervalKm)
                .putInt(PREF_OIL_INTERVAL_MONTHS, plan.intervalMonths)
                .putString(PREF_OIL_LAST_DATE, plan.lastDate.toString())
                .putInt(PREF_OIL_LAST_KM, plan.lastKm)
                .putString(PREF_OIL_VISCOSITY, plan.viscosity)
        }
        e.apply()
    }

    /** Cada leitura do contador do carro anda o km do painel (e grava quando muda). */
    /** Registra as mudanças de estado do scanner e, fora de leitura (carro mudo, sem conexão), uma batida a cada 30 s. */
    private fun logObd(snap: app.rumo.device.ObdSnapshot) {
        val now = SystemClock.elapsedRealtime()
        val changed = snap.status != obdLogStatus
        if (!changed && (snap.status == app.rumo.device.ObdStatus.LIVE || now - obdLogBeatAt < OBD_BEAT_MS)) return
        obdLogStatus = snap.status
        obdLogBeatAt = now
        val volts = snap.voltage?.let { String.format(PT_BR, "%.1f V", it) } ?: "sem tensão"
        val what = when (snap.status) {
            app.rumo.device.ObdStatus.LIVE -> "carro respondendo, rotação ${snap.number(0x0C)?.toInt() ?: "--"}, $volts"
            app.rumo.device.ObdStatus.CAR_OFF -> "scanner responde, carro mudo (ignição desligada), $volts"
            app.rumo.device.ObdStatus.CONNECTING -> "conectando (${snap.message ?: "--"}), último $volts"
            app.rumo.device.ObdStatus.ERROR -> "scanner sem conexão (${snap.message ?: "--"}), último $volts"
            app.rumo.device.ObdStatus.OFF -> "parado (Rumo fora da tela ou sem scanner)"
        }
        obdLog.add("${if (changed) "estado" else "batida"}: $what")
        // Uma cópia no pendrive a cada mudança e a cada 5 min: o multimídia pode desligar sem aviso
        if (changed || now - obdLogCopyAt > OBD_COPY_MS) {
            obdLogCopyAt = now
            obdLog.copyTo(this, getExternalFilesDirs(null).drop(1).filterNotNull())
        }
    }

    // ---- velocidade do carro (OBD) no lugar da do GPS, e o jeito de dirigir ----

    private var carSpeed: Int? = null
    private var carSpeedAt = 0L
    private val drivingStyle = DrivingStyle()
    private var drivingAt = 0L

    /** A velocidade do carro, se o scanner a leu há pouco; senão null (vale a do GPS). */
    private fun carSpeedFresh(): Int? = carSpeed?.takeIf { SystemClock.elapsedRealtime() - carSpeedAt < CAR_SPEED_FRESH_MS }

    private fun withCarSpeed(info: GpsInfo): GpsInfo = carSpeedFresh()?.let { if (it != info.speedKmh) info.copy(speedKmh = it) else info } ?: info

    /** Cada leitura do carro: a velocidade passa a valer na hora (sem esperar o GPS) e a nota do jeito de dirigir anda. */
    private fun onCarSpeed(snap: app.rumo.device.ObdSnapshot) {
        val now = SystemClock.elapsedRealtime()
        val v = snap.number(0x0D) ?: return
        carSpeed = v.toInt()
        carSpeedAt = now
        if (state.gps.speedKmh != carSpeed) {
            state.gps = state.gps.copy(speedKmh = carSpeed)
            state.over = overSpeed.update(carSpeed, state.road?.limit)
        }
        val dt = if (drivingAt == 0L) 0.0 else (now - drivingAt) / 1000.0
        drivingAt = now
        drivingStyle.update(dt, v, snap.number(0x0C), snap.number(0x11))
        state.drivingScore = drivingStyle.score
        state.drivingLabel = drivingStyle.label
    }

    private fun onObdSnapshot(snap: app.rumo.device.ObdSnapshot) {
        logObd(snap)
        if (snap.status == app.rumo.device.ObdStatus.LIVE) {
            onCarSpeed(snap)
            val now = System.currentTimeMillis()
            val closed = diary.onReading(
                TripReading(
                    now, snap.number(0x0C), snap.number(0x0D), snap.litersPerHour, snap.number(0x05),
                    snap.number(0x1F)?.toInt(), snap.number(0x31)?.toInt(),
                ),
            )
            state.trips = diary.trips.toList()
            if (closed || now - diarySavedAt > DIARY_SAVE_MS) saveDiary()
        }
        val counter = snap.number(0x31)?.toInt() ?: return
        if (counter == odometer.lastCounter) return
        val moved = odometer.onCounter(counter)
        val e = prefs.edit().putInt(PREF_ODO_COUNTER, counter)
        if (odometerKnown && moved) {
            e.putString(PREF_ODO_KM, odometer.km.toString())
            state.odometerKm = odometer.km
        }
        e.apply()
    }

    /** Grava o diário fora da thread principal (a última viagem vai junto, mesmo em andamento). */
    private fun saveDiary() {
        // Restaurando um backup: o diário do backup é que vale, não o da memória
        if (!::diary.isInitialized || restoring) return
        diarySavedAt = System.currentTimeMillis()
        val text = TripDiary.encode(diary.trips)
        logWriter.execute { SavedFiles.write(null, diaryFile, text) }
    }

    // ---- backup das configurações (pasta Rumo da memória interna e pendrive) ----

    /** Onde o backup mora: a pasta Rumo da memória interna (sobrevive a desinstalar o app) e a do app em cada pendrive. */
    private fun backupDirs(): List<File> {
        @Suppress("DEPRECATION")
        val internal = File(Environment.getExternalStorageDirectory(), MapData.FOLDER)
        return listOf(internal) + getExternalFilesDirs(null).drop(1).filterNotNull()
    }

    /** Os backups achados, do mais novo ao mais velho; o mesmo backup em dois lugares aparece uma vez. */
    private fun findBackups(): List<BackupFound> {
        class Hit(val path: String, val at: String, val version: String, val where: String, val settings: Int, val files: Int)
        val hits = ArrayList<Hit>()
        backupDirs().forEachIndexed { i, dir ->
            dir.listFiles()?.filter { it.isFile && Backup.isBackupName(it.name) }?.forEach { f ->
                val c = runCatching { Backup.decode(f.readText()) }.getOrNull() ?: return@forEach
                hits += Hit(f.path, c.at, c.appVersion, if (i == 0) "memória interna" else "pendrive", c.prefs.size, c.files.size)
            }
        }
        val shown = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
        return hits.groupBy { it.at + it.version }.values
            .sortedByDescending { it.first().at }
            .take(4)
            .map { same ->
                val h = same.first()
                val label = runCatching { LocalDateTime.parse(h.at).format(shown) }.getOrDefault(h.at)
                val where = same.map { it.where }.distinct().joinToString(" e ")
                BackupFound(h.path, label, "$where · ${h.version} · ${h.settings} configurações e ${h.files} arquivos")
            }
    }

    override fun backupScan() {
        lifecycleScope.launch { state.backupFound = withContext(Dispatchers.IO) { findBackups() } }
    }

    override fun backupSave() {
        if (state.backupBusy) return
        state.backupBusy = true
        // O que está na memória é lido aqui, na thread principal (a gravação é fora dela)
        val allPrefs = prefs.all.filterKeys { it in Backup.PREF_KEYS }
        val trips = state.trips
        val version = state.appVersion
        lifecycleScope.launch {
            val note = withContext(Dispatchers.IO) {
                val files = LinkedHashMap<String, String>()
                pointsStore.load().takeIf { it.isNotEmpty() }?.let { files[UserPoints.FILE] = UserPoints.encode(it) }
                if (trips.isNotEmpty()) files[diaryFile.name] = TripDiary.encode(trips)
                val text = Backup.encode(version, LocalDateTime.now().withNano(0).toString(), allPrefs, files)
                val dirs = backupDirs()
                val saved = dirs.mapIndexedNotNull { i, dir ->
                    // O backup anterior fica de lado: um backup feito sem querer (com tudo no padrão) não apaga o bom
                    val target = File(dir, Backup.FILE)
                    if (target.isFile) SavedFiles.copy(this@HomeActivity, target, File(dir, Backup.FILE_PREVIOUS))
                    if (SavedFiles.write(this@HomeActivity, target, text)) i else null
                }
                when {
                    saved.isEmpty() -> "Não consegui gravar o backup."
                    saved.size > 1 -> "Backup gravado na memória interna e no pendrive: ${allPrefs.size} configurações e ${files.size} arquivos."
                    saved.first() == 0 -> "Backup gravado só na memória interna (sem pendrive): ${allPrefs.size} configurações e ${files.size} arquivos."
                    else -> "Backup gravado só no pendrive: ${allPrefs.size} configurações e ${files.size} arquivos."
                }
            }
            state.backupNote = note
            state.backupFound = withContext(Dispatchers.IO) { findBackups() }
            state.backupBusy = false
        }
    }

    override fun backupRestore(path: String) {
        if (state.backupBusy || state.backupFound.none { it.path == path }) return
        state.backupBusy = true
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val raw = runCatching { File(path).readText() }.getOrNull()
                // Só segue com um arquivo que é mesmo um backup; o texto vai para um arquivo à espera da tela nova
                raw?.takeIf { Backup.decode(it) != null }?.takeIf { SavedFiles.write(null, restorePending, it) }
            }
            if (text == null) {
                state.backupNote = "Não consegui ler esse backup."
                state.backupBusy = false
                return@launch
            }
            // A tela recriada aplica o backup antes de ler as configurações: a atual não grava nada por cima
            restoring = true
            recreate()
        }
    }

    /** Aplica o backup à espera (se houver) nas preferências e nos arquivos; devolve o aviso para a tela. */
    private fun applyPendingRestore(): String? {
        if (!restorePending.isFile) return null
        val text = runCatching { restorePending.readText() }.getOrNull()
        // Apaga antes de aplicar: se algo der errado, não tenta de novo a cada abertura
        restorePending.delete()
        val c = text?.let { Backup.decode(it) } ?: return "Backup ilegível: nada foi restaurado"
        val e = prefs.edit()
        for ((key, value) in c.prefs) {
            when (value) {
                is String -> e.putString(key, value)
                is Int -> e.putInt(key, value)
                is Long -> e.putLong(key, value)
                is Float -> e.putFloat(key, value)
                is Boolean -> e.putBoolean(key, value)
                is Set<*> -> e.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        e.commit()
        c.files[UserPoints.FILE]?.let { pointsStore.save(UserPoints.decode(it)) }
        c.files[diaryFile.name]?.let { SavedFiles.write(null, diaryFile, it) }
        return "Configurações restauradas: ${c.prefs.size} configurações e ${c.files.size} arquivos"
    }

    override fun diaryClear() {
        diary.clear()
        state.trips = emptyList()
        saveDiary()
        state.toast = "Diário de viagens apagado"
    }

    private fun setOdometer(km: Int) {
        // Conta a partir da leitura do carro agora (se o scanner está lendo), para não somar de novo o que já está no painel
        val counter = state.obd.takeIf { it.status == app.rumo.device.ObdStatus.LIVE }?.number(0x31)?.toInt()
        odometer.set(km.toDouble(), counter)
        prefs.edit().putInt(PREF_ODO_COUNTER, counter ?: -1).apply()
        odometerKnown = true
        state.odometerKm = odometer.km
        prefs.edit().putString(PREF_ODO_KM, odometer.km.toString()).apply()
    }

    /** Ao abrir o Rumo, uma vez por dia: avisa se a troca de óleo venceu ou está perto. */
    private fun maintNotice() {
        val plan = state.oilPlan ?: return
        val today = java.time.LocalDate.now()
        val st = Maintenance.status(plan, state.odometerKm, today)
        if (!st.due && !st.soon) return
        if (prefs.getLong(PREF_MAINT_NOTICE_DAY, -1) == today.toEpochDay()) return
        prefs.edit().putLong(PREF_MAINT_NOTICE_DAY, today.toEpochDay()).apply()
        state.toast = "Troca de óleo: ${Maintenance.text(st).replaceFirstChar { it.lowercase() }}"
    }

    override fun maintSave(type: OilType, intervalKm: Int, intervalMonths: Int, lastDate: java.time.LocalDate, lastKm: Int, currentKm: Int?, viscosity: String?) {
        currentKm?.let { setOdometer(it) }
        saveOilPlan(OilPlan(type, intervalKm.coerceIn(1_000, 30_000), intervalMonths.coerceIn(1, 24), lastDate, lastKm, viscosity))
        state.toast = "Troca de óleo configurada"
    }

    override fun maintDone() {
        val plan = state.oilPlan ?: return
        val km = state.odometerKm
        if (km == null) {
            state.toast = "Informe o km do painel em Editar"
            return
        }
        saveOilPlan(plan.copy(lastDate = java.time.LocalDate.now(), lastKm = Math.round(km).toInt()))
        state.toast = "Troca de óleo registrada"
    }

    override fun maintForget() {
        saveOilPlan(null)
        state.toast = "Troca de óleo apagada"
    }

    override fun cardPage(page: Int) {
        state.cardPage = page
        prefs.edit().putInt(PREF_CARD_PAGE, page).apply()
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
        val now = System.currentTimeMillis()
        state.updateCheckedAt = now
        val editor = prefs.edit().putLong(PREF_UPDATE_CHECKED, now)
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

    private fun startInstall(
        channel: Channel,
        release: AppRelease,
        key: String = channel.name,
        label: String = "${channel.label} ${release.versionName}",
        prefix: String = "rumo",
        folder: File = Installer.folder(this),
    ) {
        if (state.transfer != null) return
        transferCancelled = false
        state.transfer = Transfer(key, label, 0, release.size)
        lifecycleScope.launch {
            try {
                val apk = updater.downloadApp(
                    release, folder,
                    onProgress = throttled { done -> state.transfer = Transfer(key, label, done, release.size) },
                    cancelled = { transferCancelled },
                    prefix = prefix,
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

    // ---- app do rádio online (app.rumo.webradio) ----

    /** Canal do app do rádio online: o mesmo do Rumo instalado (build local: nightly). */
    private fun webRadioChannel() = noticeChannel(state.appVersion) ?: Channel.NIGHTLY

    /** Versão instalada e a publicada no canal (uma consulta por sessão, com rede). */
    private fun refreshWebRadioApp(fetch: Boolean) {
        state.webRadioInstalled = WebRadioPlayer.installedVersion(this)
        if (!fetch || webRadioFetched || !state.online) return
        webRadioFetched = true
        lifecycleScope.launch { state.webRadioLatest = updater.fetchWebRadio(webRadioChannel()) }
    }

    override fun installWebRadio() {
        if ((state.gps.speedKmh ?: 0) >= PARKED_BELOW_KMH) {
            state.toast = "Instalar só com o carro parado"
            return
        }
        lifecycleScope.launch {
            val release = state.webRadioLatest ?: updater.fetchWebRadio(webRadioChannel()).also { state.webRadioLatest = it }
            if (release == null) {
                state.toast = if (state.online) "Não achei o app do rádio online para baixar" else "Sem internet para baixar"
                return@launch
            }
            startInstall(
                webRadioChannel(), release,
                key = TRANSFER_WEB_RADIO,
                label = "Rádio online ${release.versionName}",
                prefix = "rumo-web-radio",
                folder = File(Installer.folder(this@HomeActivity), "web-radio"),
            )
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
    private fun onGps(raw: GpsInfo, fix: Boolean) {
        // Com o scanner lendo, a velocidade é a do carro em todo o app (cartão, vermelho, avisos de radar, parado)
        val info = withCarSpeed(raw)
        state.gps = info
        val lat = info.lat
        val lon = info.lon
        if (info.status != loggedGps) {
            loggedGps = info.status
            alertLog.add("gps: ${info.status}")
        }
        if (info.status != GpsStatus.OK || lat == null || lon == null) {
            state.road = null
            state.radar = null
            state.over = false
            alarm(null)
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

    // ---- avisos de radar: pelo Rumo ou, com o rádio online tocando, pelo app dele ----

    /** Os bipes estão tocando no app do rádio online (para calá-los lá ao voltar a tocar pelo Rumo). */
    private var remoteAlarm = false

    /**
     * Com o app do rádio online instalado, todo aviso sai dele (o Rumo fica ligado nele enquanto está na tela): um som
     * tocado pelo Rumo o faz virar a fonte, e o firmware o encerra na troca de fonte seguinte (para o online ou para o
     * FM). Sem o app (ou sem a ligação ainda), sai do Rumo, como na 0.2.0.
     */
    private fun remoteAlerts() = webPlayerOrNull?.connected == true

    /**
     * O app do online está instalado mas a ligação caiu (o firmware o encerrou na troca de fonte): o aviso espera ela
     * voltar em vez de sair pelo Rumo. Um som tocado pelo Rumo o marca como fonte, e a troca seguinte o encerra.
     */
    private fun waitsForCompanion() = state.webRadioInstalled != null && webPlayerOrNull?.connected != true

    /** Aviso de entrada pedido com a ligação caída: toca quando ela voltar (se ainda for recente). */
    private var pendingChimeAt = 0L

    private fun onCompanionConnected() {
        if (pendingChimeAt != 0L && SystemClock.elapsedRealtime() - pendingChimeAt < PENDING_CHIME_MS) {
            pendingChimeAt = 0L
            chime()
        }
        pendingChimeAt = 0L
    }

    /**
     * Teste "avisos com o FM pelo Rumo": com o rádio online parado, o aviso sai do próprio Rumo, como na 0.2.0 (o FM
     * abaixava e tocava junto). Com o online tocando, continua pelo app dele (pelo Rumo, o online parava).
     */
    private fun rumoPlaysAlerts() = state.alertsViaRumo && !(state.source == Source.WEB && webPlaying())

    private fun chime() {
        if (rumoPlaysAlerts()) {
            alertLog.add("aviso: pelo Rumo (teste)")
            alerts.chime()
            return
        }
        if (remoteAlerts()) noteAlertStart()
        if (remoteAlerts() && webPlayer.chime()) {
            alertLog.add("aviso: pelo app do rádio online")
            resumeFmAfterAlert(CHIME_RESUME_MS)
            return
        }
        if (waitsForCompanion()) {
            alertLog.add("aviso: espera a ligação com o app do rádio online")
            pendingChimeAt = SystemClock.elapsedRealtime()
            webPlayer.reconnectNow()
            return
        }
        alerts.chime()
    }

    private fun alarm(k: Float?) {
        if (rumoPlaysAlerts()) {
            // Bipes que estavam no app do online (o teste foi ligado no meio): calam lá
            if (remoteAlarm) {
                webPlayerOrNull?.alarm(null)
                remoteAlarm = false
            }
            alerts.alarm(k)
            return
        }
        // Bipes pedidos com a ligação caída: o próximo pedido (a cada posição do GPS) já sai pelo app do online
        if (k != null && waitsForCompanion()) {
            webPlayer.reconnectNow()
            return
        }
        if (k != null && remoteAlerts() && !remoteAlarm) noteAlertStart()
        if (k != null && remoteAlerts() && webPlayer.alarm(k)) {
            if (!remoteAlarm) alertLog.add("bipes: pelo app do rádio online")
            remoteAlarm = true
            resumeFmJob?.cancel()
            alerts.stop()
            return
        }
        if (remoteAlarm) {
            webPlayerOrNull?.alarm(null)
            remoteAlarm = false
            resumeFmAfterAlert(ALARM_RESUME_MS)
        }
        alerts.alarm(k)
    }

    private var resumeFmJob: Job? = null
    /** O FM tocava quando o aviso (pelo app do online) começou: volta a tocar quando ele termina. */
    private var fmBeforeAlert = false
    /** Até quando a fonte "outro app" que o aviso provoca no sourceinfo é ignorada (o card continua no FM). */
    private var alertSourceUntil = 0L

    private fun noteAlertStart() {
        // Um aviso sobre outro: vale o estado de antes do primeiro
        if (SystemClock.elapsedRealtime() > alertSourceUntil) {
            fmBeforeAlert = state.source == Source.RADIO && !state.radioMuted && state.radioBand == RadioBand.FM
        }
        alertSourceUntil = SystemClock.elapsedRealtime() + ALERT_SOURCE_HOLD_MS
    }

    /**
     * O aviso saiu pelo app do rádio online e o FM tocava: o aparelho trata aquele app como app de música e o rádio cede
     * a vez sem voltar sozinho. Terminado o aviso (e sem bipes), o Rumo manda o FM tocar de novo, como o play do card.
     */
    private fun resumeFmAfterAlert(delayMs: Long) {
        alertSourceUntil = SystemClock.elapsedRealtime() + delayMs + ALERT_SOURCE_HOLD_MS
        if (!fmBeforeAlert) return
        resumeFmJob?.cancel()
        resumeFmJob = lifecycleScope.launch {
            delay(delayMs)
            if (remoteAlarm || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
            // O motorista trocou de fonte ou parou o rádio durante o aviso: não mexe
            if (state.radioMuted || state.radioBand != RadioBand.FM || state.source == Source.ZLINK || state.source == Source.WEB) return@launch
            fmBeforeAlert = false
            radioLog("aviso pelo app do online terminou: o FM volta")
            state.source = Source.RADIO
            radio(RadioCmd.Play)
        }
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
            logRadar(radar, entered, speed)
            // Durante o teste do som, a posição do GPS não pode calar os bipes de mentira
            if (!soundTesting) {
                if (entered && radar?.radar?.chimes == true) chime()
                alarm(radar?.takeIf { it.sound }?.closeness(radars.zoneM))
            }
            // Chegou uma posição enquanto esta era calculada: calcula a mais recente (se o GPS ainda estiver bom)
            val next = pendingFix
            pendingFix = null
            val nextLat = next?.lat
            val nextLon = next?.lon
            if (next != null && nextLat != null && nextLon != null && state.gps.status == GpsStatus.OK) drive(next, nextLat, nextLon)
        }
    }

    /** Anota no registro dos alertas o que o radar pediu (aviso na entrada, bipes ligando e desligando); o que o áudio fez, a [Alerts] anota. */
    private fun logRadar(radar: RadarAlert?, entered: Boolean, speed: Int) {
        val r = radar?.radar
        if (entered && r != null) {
            alertLog.add(
                "radar: entrou id=${r.id} ${radarLabel(r.kind)} a ${radar.distanceM} m, vel=$speed limite=${r.limit} " +
                    "margem=${radars.marginKmh} aviso=${if (r.chimes) "sim" else "não"}",
            )
        } else if (r == null && loggedRadar != null) {
            alertLog.add("radar: saiu id=$loggedRadar")
        }
        loggedRadar = r?.id
        val beeping = radar?.sound == true
        if (beeping != loggedBeeps) {
            alertLog.add(if (beeping) "radar: bipes pedidos (vel=$speed, a ${radar?.distanceM} m)" else "radar: bipes não pedidos mais (vel=$speed)")
            loggedBeeps = beeping
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
        val owner = audioOwnerOf(name) ?: return
        if (state.source == Source.WEB) {
            // Com o rádio online (tocando ou parado pelo play do card), o sourceinfo pode dizer "outro app" (o som sai
            // como mídia do Android) ou ainda "rádio" (a última fonte do aparelho): a fonte continua sendo o rádio
            // online, e o play do card volta a tocá-lo. Só o ZLINK o tira (o Android Auto também pede o foco de áudio,
            // e o player pausa sozinho). A troca de estação no app do rádio é vista em onRadio.
            if (owner != AudioOwner.ZLINK) return
            if (webPlaying()) stopWeb()
        }
        // Durante um aviso (pelo app do online), a fonte "outro app" é o próprio aviso: o card continua no FM
        if (owner == AudioOwner.OTHER && SystemClock.elapsedRealtime() < alertSourceUntil) return
        state.source = when (owner) {
            AudioOwner.ZLINK -> Source.ZLINK
            AudioOwner.RADIO -> Source.RADIO
            AudioOwner.OTHER -> sourceOf(name) ?: Source.OTHER
        }
        // Outra fonte tocando: o mudo que o Rumo pôs no rádio não pode ficar para depois
        if (state.source != Source.RADIO && state.source != Source.WEB && state.radioMuted) setRadioMuted(false)
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
        const val CRASH_LOG_LINES = 40
        const val RADIO_SETTLE_MS = 500L
        const val TRANSFER_WEB_RADIO = "WEB_RADIO"
        const val CAME_FROM_WEB_MS = 15_000L
        /** Depois do aviso de entrada (~0,7 s) e dos bipes, quanto esperar para pôr o FM de volta. */
        const val CHIME_RESUME_MS = 900L
        // Depois que os bipes param: espera antes de devolver o FM (acima do limite, os bipes ligam e desligam; cada troca
        // do FM para o app do online e de volta é uma troca de fonte do aparelho, audível)
        const val ALARM_RESUME_MS = 1_500L
        const val ALERT_SOURCE_HOLD_MS = 4_000L
        /** Aviso de entrada que esperou a ligação: depois disso já não faz sentido tocar. */
        const val PENDING_CHIME_MS = 4_000L
        /** Ligado enquanto o Rumo está na tela: ainda ligado na abertura seguinte = o processo foi encerrado de fora. */
        const val PREF_SESSION_OPEN = "session_open"
        const val RADIO_LOG_FILE_MAX = 256 * 1024L
        const val REPORT_LOGCAT_LINES = 1_500
        const val PREF_OBD_ADDRESS = "obd_address"
        const val PREF_OBD_NAME = "obd_name"
        const val PREF_OBD_FUEL = "obd_fuel_factor"
        const val PREF_CARD_PAGE = "card_page"
        const val PREF_ALERTS_VIA_RUMO = "alerts_via_rumo_fm"
        const val PREF_OIL_TYPE = "oil_type"
        const val PREF_OIL_INTERVAL_KM = "oil_interval_km"
        const val PREF_OIL_INTERVAL_MONTHS = "oil_interval_months"
        const val PREF_OIL_LAST_DATE = "oil_last_date"
        const val PREF_OIL_LAST_KM = "oil_last_km"
        const val PREF_OIL_VISCOSITY = "oil_viscosity"
        const val PREF_TANK_LITERS = "tank_liters"
        const val TANK_DEFAULT_L = 45
        const val TANK_MIN_L = 20
        const val TANK_MAX_L = 120
        /** A velocidade lida do carro vale por este tempo; depois volta a valer a do GPS. */
        const val CAR_SPEED_FRESH_MS = 3_000L
        /** Com o carro lido, grava o diário a cada tanto (e ao sair da tela ou começar outra viagem). */
        const val DIARY_SAVE_MS = 60_000L
        /** Registro do scanner: batida fora de leitura e cópia no pendrive. */
        const val OBD_BEAT_MS = 30_000L
        const val OBD_COPY_MS = 5 * 60_000L
        const val PREF_ODO_KM = "odometer_km"
        const val PREF_ODO_COUNTER = "odometer_counter"
        const val PREF_MAINT_NOTICE_DAY = "maint_notice_day"
        const val OBD_SEARCH_MS = 15_000L
        const val PREF_RADIO_BAND = "radio_band"
        const val PREF_WEB_FAVORITES = "web_favorites"
        const val PREF_WEB_LAST = "web_last"
        const val KEY_COUNTRY = "country-br"
        const val MAX_ACTIONS = 2_000
        /** As configurações da home só abrem abaixo desta velocidade. */
        const val PARKED_BELOW_KMH = 5
        const val PROGRESS_EVERY_MS = 250L
        const val SOUND_TEST_STEPS = 10
        const val SOUND_TEST_STEP_MS = 500L
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
