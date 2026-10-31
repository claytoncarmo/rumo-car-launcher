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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.rumo.core.AudioOwner
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
import app.rumo.core.RadarAlert
import app.rumo.core.RadarWatch
import app.rumo.core.RoadMatch
import app.rumo.core.RoadMatcher
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
import app.rumo.device.McuTap
import app.rumo.core.PT_BR
import app.rumo.device.NightSignal
import app.rumo.ui.HomeActions
import app.rumo.ui.HomeScreen
import app.rumo.ui.HomeState
import app.rumo.ui.MapStart
import app.rumo.ui.MapStatus
import app.rumo.ui.Sheet
import app.rumo.ui.Source
import app.rumo.ui.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** Tela inicial do Rumo. GPS (e, depois, os alertas) só funcionam com ela na tela. */
class HomeActivity : ComponentActivity(), HomeActions {

    private val state = HomeState()
    private lateinit var gps: Gps
    private lateinit var alerts: Alerts

    // Vias e radares: uma thread só, que lê o vias.db e guarda o estado do casamento com a via
    private val drive = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var data: MapData? = null
    private val matcher = RoadMatcher()
    private val radars = RadarWatch(zoneM = RADAR_ZONE_M)
    private val overSpeed = OverSpeed()
    private var driveJob: Job? = null
    private var systemNight = false
    private var probeJob: Job? = null
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
    private val radioControl by lazy { RadioControl(this) }
    @Volatile private var scanCancelled = false
    private var savedOwner: String? = null
    private val prefs by lazy { getSharedPreferences("rumo", MODE_PRIVATE) }

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
        gps = Gps(this) { onGps(it) }
        alerts = Alerts(this)
        state.favorites = Favorites.decode(prefs.getString(PREF_FAVORITES, null))
        state.radioMuted = prefs.getBoolean(PREF_RADIO_MUTED, false)
        radioNames.load(prefs.getString(PREF_RADIO_NAMES, null), System.currentTimeMillis())
        state.radioNames = radioNames.valid(System.currentTimeMillis())
        prefs.getString(PREF_SCAN, null)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.isNotEmpty() }?.let { state.radioScan = RadioScan(false, it) }
        state.theme = prefs.getString(PREF_THEME, null)
            ?.let { saved -> ThemeMode.entries.find { it.name == saved } } ?: ThemeMode.AUTO
        systemNight = resources.configuration.isNightBitSet()
        if (prefs.contains(PREF_LAT)) {
            state.mapStart = MapStart(
                prefs.getFloat(PREF_LAT, 0f).toDouble(),
                prefs.getFloat(PREF_LON, 0f).toDouble(),
                prefs.getFloat(PREF_BEARING, 0f).toDouble(),
            )
        }
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

        // Estação do rádio (fmradio), perguntada a cada segundo com o Rumo na tela
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refreshRadio()
                    delay(RADIO_EVERY_MS)
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

        val missing = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_EXTERNAL_STORAGE)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }

    override fun onStart() {
        super.onStart()
        gps.start()
        // A pasta Rumo pode ter sido copiada com o Rumo fora da tela
        if (state.mapStatus != MapStatus.READY) loadMapData()
        // Ouvinte do fmradio, só para o texto RDS (sem ele o rádio não decodifica o texto)
        lifecycleScope.launch(Dispatchers.IO) { radioStation.start() }
        // Fonte de áudio pelo serviço sourceinfo: quem toca agora e, com o Rumo na tela, cada troca.
        // Resposta vazia (acontece com o rádio tocando): vale a última fonte que ele informou.
        lifecycleScope.launch {
            val now = withContext(Dispatchers.IO) { sourceInfo.start() }
            if (now == null) {
                // Aparelho sem o serviço: estima pelo último app de áudio aberto
                sourceInfoMissing = true
                detectSource()
                return@launch
            }
            state.sourceLive = true
            applyAudioOwner(now.ifBlank { prefs.getString(PREF_SOURCE, null).orEmpty() })
        }
    }

    // Alertas só com o Rumo na tela: com o Android Auto aberto, nada toca
    override fun onStop() {
        scanCancelled = true
        // A ligação com o serviço do rádio fica aberta: se o rádio foi ligado pela tela do Rumo, o serviço só
        // existe por causa dela, e desligar ao sair (ao abrir o ZLINK) parava o rádio
        lifecycleScope.launch(Dispatchers.IO) {
            sourceInfo.stop()
            radioStation.stop()
        }
        savePosition(state.gps)
        gps.stop()
        alerts.stop()
        state.radar = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        detectSource()
    }

    override fun onDestroy() {
        unregisterReceiver(packagesChanged)
        alerts.stop()
        radioControl.disconnect()
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
        val e = prefs.edit().putFloat(PREF_LAT, lat.toFloat()).putFloat(PREF_LON, lon.toFloat())
        g.bearing?.let { e.putFloat(PREF_BEARING, it) }
        e.apply()
    }

    /**
     * Teste do farol: ouve os eventos da MCU (mcu_service) e os avisos (broadcasts) que parecem do farol; fotografa
     * os ajustes do sistema; dá [PROBE_SECONDS] para acender e apagar o farol; mostra o que chegou e o que mudou e
     * salva o relatório completo em teste-farol.txt (memória interna, pasta Rumo, e pendrive/cartão quando der).
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
            state.toast = "Teste do farol: abrindo o serviço da MCU…"
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

            // Eventos da MCU, com o tempo desde o começo da contagem
            val mcuEvents = mutableListOf<String>()
            val t0 = SystemClock.elapsedRealtime()
            val tap = McuTap { e ->
                val t = (SystemClock.elapsedRealtime() - t0) / 100 / 10.0
                runOnUiThread { mcuEvents += String.format(PT_BR, "+%.1f s  %s", t, e) }
            }
            val mcuStatus = withContext(Dispatchers.IO) { tap.attach(McuTap.groups()) }
            try {
                for (left in PROBE_SECONDS downTo 1) {
                    state.toast = "Acenda e depois apague o farol · $left s · ${mcuEvents.size} eventos da MCU"
                    delay(1_000)
                }
            } finally {
                unregisterReceiver(receiver)
                withContext(NonCancellable + Dispatchers.IO) { tap.detach() }
            }
            state.toast = "Comparando…"
            val (after, propsAfter) = withContext(Dispatchers.IO) {
                NightSignal.snapshot(this@HomeActivity) to CarSourceProbe.props(hunt.props)
            }
            state.toast = null
            // O GPS e o contador de mensagens da MCU mudam sempre: só atrapalham a leitura
            val changed = NightSignal.diff(before, after).filterNot { it.startsWith("prop:vendor.gps.") || it.startsWith("prop:log.mcud.") }
            val summary = buildList {
                add("Eventos da MCU (${mcuEvents.size}) · $mcuStatus:")
                addAll(mcuEvents.ifEmpty { listOf("nenhum") })
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
            showReport("Teste do farol", "teste-farol.txt", summary, full)
        }
    }

    /**
     * Teste do rádio: o sourceinfo diz a fonte, mas não a estação. O app de rádio do aparelho tem um serviço para
     * outros apps (FMPlugService, IRadioServiceAPI): o teste liga nele, lê todas as funções de leitura (get…/is…),
     * inscreve o ouvinte dele (IRadioCallBack) e, por [MEDIA_PROBE_SECONDS], anota o que muda enquanto o motorista
     * troca de estação. Também escuta os avisos (broadcasts) do app de rádio. Salva teste-radio.txt.
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
            showReport("Teste do rádio", "teste-radio.txt", summary, full)
        }
    }

    private suspend fun showReport(title: String, file: String, summary: List<String>, full: List<String>) {
        val saved = withContext(Dispatchers.IO) { saveReport(title, file, full) }
        // Na tela, só o que interessa; o resto vai para o arquivo
        state.probeTitle = title
        state.probe = summary + listOf(
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

    private fun describe(intent: Intent): String {
        val extras = intent.extras ?: return ""
        @Suppress("DEPRECATION")
        return extras.keySet().joinToString(prefix = " {", postfix = "}") { "$it=${extras.get(it)}" }
    }

    private fun Configuration.isNightBitSet() =
        uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // Botão Início com o Rumo já na tela: volta para os cards
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        state.sheet = Sheet.NONE
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
            RadioCmd.Play -> lifecycleScope.launch {
                ensureRadio()
                radioLog(withContext(Dispatchers.IO) { radioControl.playAudio() })
            }
            is RadioCmd.Seek -> lifecycleScope.launch {
                ensureRadio()
                val f = withContext(Dispatchers.IO) { radioControl.seekAndWait(cmd.up, ::radioLog) }
                radioLog("busca parou em ${f?.let(::formatRadioFreq) ?: "?"}")
                refreshRadio()
            }
            // Ajuste fino: de 0,2 em 0,2 pelas frequências de final ímpar (as das estações no Brasil)
            is RadioCmd.Step -> lifecycleScope.launch {
                val from = state.radio?.khz ?: return@launch
                ensureRadio()
                withContext(Dispatchers.IO) { radioControl.tune(fineStep(from, cmd.up), ::radioLog) }
                refreshRadio()
            }
            is RadioCmd.Tune -> lifecycleScope.launch {
                ensureRadio()
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
                state.radioScan = RadioScan(true, emptyList())
                lifecycleScope.launch {
                    ensureRadio()
                    val found = withContext(Dispatchers.IO) {
                        radioControl.scan(
                            onFound = { f -> runOnUiThread { state.radioScan = state.radioScan?.let { it.copy(found = it.found + f) } } },
                            cancelled = { scanCancelled },
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
                val saved = withContext(Dispatchers.IO) { saveReport("Teste dos comandos do rádio", "teste-comandos.txt", state.radioLog) }
                state.toast = if (saved.isEmpty()) "Não consegui salvar o registro" else "Registro salvo: ${saved.first()}"
            }
        }
    }

    private suspend fun ensureRadio() {
        if (radioControl.connected) return
        // Sem registerRadioClientBinder: os comandos funcionam sem ele, e o "cliente saiu" pode parar o rádio
        radioLog(radioControl.connect())
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
            if (state.radioMuted || lastRawOwner == "") radio(RadioCmd.Play) else setRadioMuted(true)
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
        state.radioMuted = on
        prefs.edit().putBoolean(PREF_RADIO_MUTED, on).apply()
        lifecycleScope.launch { radioLog(withContext(Dispatchers.IO) { radioControl.mute(on) }) }
    }

    private fun sendMediaKey(keyCode: Int) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private suspend fun refreshRadio() {
        val now = withContext(Dispatchers.IO) { radioStation.read() } ?: return
        onRadio(now)
        if (radioStation.namesChanged) {
            radioStation.namesChanged = false
            val t = System.currentTimeMillis()
            state.radioNames = radioNames.valid(t)
            prefs.edit().putString(PREF_RADIO_NAMES, radioNames.encode(t)).apply()
        }
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

    override fun systemSettings() = start(Intent(Settings.ACTION_SETTINGS))

    override fun askLocation() = permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))

    override fun askFiles() = permissions.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))

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
                MapData.open(this@HomeActivity).also { data = it }
            }
            state.mapFile = found?.mapFile
            state.mapStatus = if (found == null) MapStatus.MISSING else MapStatus.READY
            state.road = null
            state.radar = null
        }
    }

    /**
     * A cada posição do GPS (1 por segundo): via atual e limite, velocidade acima dele, radar à frente e os sons.
     * Se a conta anterior ainda não acabou, pula esta posição.
     */
    private fun onGps(info: GpsInfo) {
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
        // O carro desliga sem aviso: guarda a posição de tempos em tempos, não só ao sair da tela
        if (SystemClock.elapsedRealtime() - savedAt > SAVE_EVERY_MS) savePosition(info)
        if (driveJob?.isActive == true) return
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
            if (entered) alerts.chime()
            alerts.alarm(radar?.takeIf { it.alarm }?.closeness(radars.zoneM))
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
        const val RADAR_ZONE_M = 500
        const val PREF_THEME = "tema"
        const val PROBE_SECONDS = 20
        const val PREF_LAT = "ultima_lat"
        const val PREF_LON = "ultima_lon"
        const val PREF_BEARING = "ultimo_rumo"
        const val SAVE_EVERY_MS = 15_000L
        const val HEADLIGHT_EVERY_MS = 2_000L
        const val MEDIA_PROBE_SECONDS = 60
        const val PREF_SOURCE = "ultima_fonte"
        const val RADIO_PKG = "com.hcn.autoradio"
        const val RADIO_EVERY_MS = 1_000L
        const val PREF_FAVORITES = "favoritas"
        const val PREF_RADIO_NAMES = "radio_nomes"
        const val PREF_SCAN = "radio_encontradas"
        const val PREF_RADIO_MUTED = "radio_mudo"
        const val RADIO_LOG_LINES = 300
        const val MAX_ACTIONS = 2_000
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
