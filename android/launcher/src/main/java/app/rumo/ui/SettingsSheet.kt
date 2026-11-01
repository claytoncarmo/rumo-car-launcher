package app.rumo.ui

import android.os.Build

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.rumo.core.Channel
import app.rumo.core.DataChange
import app.rumo.core.DataStatus
import app.rumo.core.RegionInfo
import app.rumo.core.RadarSettings
import app.rumo.core.installedIds
import app.rumo.core.planChange
import app.rumo.core.UpdateKind
import app.rumo.core.compareWithInstalled
import app.rumo.core.formatBuiltAt
import app.rumo.core.formatSize
import app.rumo.core.installedChannelLabel
import app.rumo.device.ChannelInfo
import app.rumo.device.DataInfo
import app.rumo.device.PowerService
import kotlinx.coroutines.delay

/**
 * O que a consulta às releases encontrou: um canal por vez (estável e nightly) e os dados do mapa. Sem conexão, o que
 * já tinha sido encontrado continua na tela e [offline] avisa que não é de agora.
 */
data class SettingsState(
    val checking: Boolean = false,
    val offline: Boolean = false,
    val stable: ChannelInfo? = null,
    val nightly: ChannelInfo? = null,
    val data: DataInfo? = null,
    /** Estados marcados na lista de dados; null enquanto o usuário não mexeu (vale o que está instalado). */
    val selected: Set<String>? = null,
)

/** Estados que o usuário quer no aparelho: os marcados ou, sem escolha ainda, os instalados. */
fun SettingsState.wanted(): Set<String> {
    val regions = data?.regions.orEmpty()
    return selected?.intersect(regions.map { it.region.id }.toSet()) ?: installedIds(regions)
}

/** Quanto esperar (ms) depois que a conexão volta antes de consultar: a rede acabou de subir e o DNS ainda pode falhar. */
private const val RECONNECT_WAIT_MS = 1500L

/** Download em andamento: [key] é o canal (`STABLE`/`NIGHTLY`) ou `data`. */
data class Transfer(val key: String, val label: String, val done: Long, val total: Long)

/** As abas das configurações da home. */
private enum class SettingsTab(val label: String) {
    SETTINGS("Configurações"),
    POINTS("Pontos"),
    UPDATES("Atualizações"),
    DEBUG("Debug"),
}

/**
 * Configurações da home (engrenagem › "Configurações da home", com o carro parado), em quatro abas: Configurações (avisos
 * de radar e reinício do Android), Pontos (ver, editar e apagar os pontos marcados no mapa), Atualizações (app por canal e dados do mapa) e Debug (diagnósticos e versão).
 */
@Composable
fun SettingsSheet(s: HomeState, a: HomeActions) {
    val p = LocalPalette.current
    var tab by remember { mutableStateOf(SettingsTab.SETTINGS) }
    // Ao entrar na aba Atualizações consulta sozinho; sem conexão, tenta de novo quando ela volta (com um respiro para
    // a rede firmar antes da consulta)
    LaunchedEffect(tab) { if (tab == SettingsTab.UPDATES) a.checkUpdates() }
    LaunchedEffect(s.online, tab) {
        if (tab == SettingsTab.UPDATES && s.online && s.settings.offline) {
            delay(RECONNECT_WAIT_MS)
            a.checkUpdates()
        }
    }
    Column(Modifier.fillMaxSize().card(p).swallowTouches()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Txt("Configurações da home", 30, weight = FontWeight.SemiBold)
                Txt("Rumo ${s.appVersion} · ${installedChannelLabel(s.appVersion)}", 16, color = p.text2)
            }
            Box(
                Modifier.size(76.dp, 64.dp).clip(RoundedCornerShape(20.dp)).background(p.surface2).clickable { s.sheet = Sheet.NONE },
                contentAlignment = Alignment.Center,
            ) {
                Ico(Icons.x, 30.dp, p.text)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsTab.entries.forEach { TabPill(it.label, tab == it) { tab = it } }
        }
        if (tab == SettingsTab.POINTS) {
            Box(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, bottom = 22.dp)) { PointsTab(s, a) }
            return@Column
        }
        Row(
            Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, bottom = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            TabColumn { if (tab == SettingsTab.SETTINGS) RadarSection(s, a) else if (tab == SettingsTab.UPDATES) AppSection(s, a) else DiagnosticsSection(s, a) }
            TabColumn { if (tab == SettingsTab.SETTINGS) SystemSection(a) else if (tab == SettingsTab.UPDATES) DataSection(s, a) else VersionSection(s) }
        }
    }
}

@Composable
private fun RowScope.TabColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
private fun TabPill(text: String, on: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(if (on) p.accent else p.surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, 19, color = if (on) p.onAccent else p.text, weight = FontWeight.SemiBold)
    }
}

@Composable
internal fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.surface2).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Txt(title, 15, color = p.text2, weight = FontWeight.SemiBold, caps = true)
        content()
    }
}

@Composable
private fun AppSection(s: HomeState, a: HomeActions) {
    val p = LocalPalette.current
    val settings = s.settings
    SectionCard("Aplicativo") {
        Txt(
            when {
                settings.checking -> "Consultando as versões publicadas…"
                settings.offline && (settings.stable?.release != null || settings.nightly?.release != null) ->
                    "Sem conexão com o GitHub: mostrando a última consulta. Busca de novo quando a internet voltar."
                settings.offline -> "Sem conexão com o GitHub. Busca de novo quando a internet voltar."
                else -> "Versões publicadas no GitHub"
            },
            16, color = p.text2, maxLines = 3,
        )
        ChannelRow(Channel.STABLE, settings.stable, s, a)
        ChannelRow(Channel.NIGHTLY, settings.nightly, s, a)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Buscar de novo", enabled = !settings.checking && s.transfer == null, primary = false) { a.checkUpdates() }
        }
        Txt(
            "A nightly sai a cada mudança no código e pode ser instável. O Android não deixa voltar para uma versão mais antiga que a instalada.",
            14, color = p.text3, maxLines = 4,
        )
    }
}

@Composable
private fun ChannelRow(channel: Channel, info: ChannelInfo?, s: HomeState, a: HomeActions) {
    val p = LocalPalette.current
    val release = info?.release
    val kind = release?.let { compareWithInstalled(s.appCode, it) }
    val detail = when {
        info == null -> "—"
        release == null -> info.problem ?: "Nada publicado"
        else -> listOf(release.versionName, formatBuiltAt(release.builtAt), formatSize(release.size))
            .filter { it.isNotEmpty() }.joinToString(" · ")
    }
    val transfer = s.transfer?.takeIf { it.key == channel.name }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt(channel.label, 22, weight = FontWeight.SemiBold)
                Txt(detail, 16, color = p.text2, maxLines = 2)
                if (kind == UpdateKind.OLDER) Txt("Mais antiga que a instalada", 14, color = p.text3)
            }
            Spacer(Modifier.width(12.dp))
            PillButton(
                text = when (kind) {
                    UpdateKind.NEWER -> "Instalar"
                    UpdateKind.SAME -> "Instalada"
                    UpdateKind.OLDER -> "Indisponível"
                    null -> "—"
                },
                enabled = kind == UpdateKind.NEWER && s.transfer == null,
                primary = true,
            ) { a.installApp(channel) }
        }
        if (transfer != null) TransferBar(transfer, a)
    }
}

@Composable
private fun DataSection(s: HomeState, a: HomeActions) {
    val p = LocalPalette.current
    val settings = s.settings
    val data = settings.data
    val wanted = settings.wanted()
    val change = data?.let { planChange(it.regions, it.common, wanted) }
    val transfer = s.transfer?.takeIf { it.key == DATA_KEY }
    SectionCard("Mapas por estado") {
        Txt(
            when {
                data == null -> if (settings.checking) "Consultando…" else "—"
                data.problem != null -> data.problem
                settings.offline -> "Sem conexão com o GitHub: mostrando a última consulta."
                else -> "Marque os estados que quer no carro e toque no botão. Vários funcionam juntos, inclusive ao cruzar a divisa."
            },
            16, color = p.text2, maxLines = 3,
        )
        if (data != null && data.problem == null) {
            val groups = data.regions.groupBy { it.region.group }
            for ((group, regions) in groups) {
                if (group.isNotBlank()) Txt(group, 14, color = p.text3, weight = FontWeight.SemiBold, caps = true)
                regions.forEach { RegionRow(it, it.region.id in wanted, s.transfer == null) { a.toggleRegion(it.region.id) } }
            }
        }
        if (transfer != null) {
            TransferBar(transfer, a)
        } else if (change != null) {
            PillButton(
                text = applyLabel(change),
                enabled = !change.isEmpty && s.transfer == null,
                primary = true,
            ) { a.applyData() }
        }
        Txt(
            "Use o wi-fi do carro ou o ponto de acesso do celular. Se a conexão cair, o download continua de onde parou. Mapa e vias do OpenStreetMap (© colaboradores); radares do MapaRadar (CC BY-NC-ND 4.0).",
            14, color = p.text3, maxLines = 4,
        )
    }
}

private fun applyLabel(change: DataChange): String = when {
    change.isEmpty -> "Nada a fazer"
    change.remove.isEmpty() -> "Baixar ${formatSize(change.bytes)}"
    change.download.isEmpty() -> "Remover ${change.remove.size} ${if (change.remove.size == 1) "estado" else "estados"}"
    else -> "Baixar ${formatSize(change.bytes)} e remover ${change.remove.size}"
}

@Composable
private fun RegionRow(info: RegionInfo, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    val detail = when (info.plan.status) {
        DataStatus.NOT_INSTALLED -> formatSize(info.region.totalSize)
        DataStatus.UPDATE -> "Instalado · atualização de ${formatSize(info.plan.bytes)}"
        DataStatus.UP_TO_DATE -> "Instalado · ${formatSize(info.region.totalSize)}"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (checked) p.accent else p.surface3),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Ico(Icons.check, 26.dp, p.onAccent)
        }
        Column(Modifier.weight(1f)) {
            Txt(info.region.name, 20, weight = FontWeight.SemiBold)
            Txt(detail, 14, color = p.text2)
        }
    }
}

@Composable
private fun DiagnosticsSection(s: HomeState, a: HomeActions) {
    val p = LocalPalette.current
    SectionCard("Diagnósticos") {
        Txt("Gravam um relatório em texto na pasta Rumo (memória interna e pendrive).", 16, color = p.text2, maxLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Teste do farol (20 s)", enabled = true, primary = false) { a.probeHeadlight() }
            PillButton("Teste do rádio (60 s)", enabled = true, primary = false) { a.probeMedia() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Teste do reinício", enabled = true, primary = false) { a.probeReboot() }
            PillButton("Teste dos botões", enabled = true, primary = false) { s.sheet = Sheet.KEYS }
        }
    }
}

/** Distância do aviso e margem de velocidade dos radares (core/Radars.kt, `RadarSettings`). */
@Composable
private fun RadarSection(s: HomeState, a: HomeActions) {
    val p = LocalPalette.current
    SectionCard("Avisos de radar") {
        StepperRow(
            title = "Distância do aviso",
            detail = "O aviso e os bipes começam a esta distância do radar. Lombada, pedágio e câmera de semáforo avisam a no máximo ${RadarSettings.NEAR_ZONE} m.",
            value = "${s.radarZoneM} m",
            canDecrease = s.radarZoneM > RadarSettings.ZONE_MIN,
            canIncrease = s.radarZoneM < RadarSettings.ZONE_MAX,
            onDecrease = { a.setRadarZone(s.radarZoneM - RadarSettings.ZONE_STEP) },
            onIncrease = { a.setRadarZone(s.radarZoneM + RadarSettings.ZONE_STEP) },
        )
        StepperRow(
            title = "Margem de velocidade",
            detail = "Vale só para o som: com ${s.radarMarginKmh} km/h, num radar de 50 km/h os bipes começam acima de ${50 + s.radarMarginKmh} km/h. O aviso em vermelho aparece assim que passar do limite.",
            value = "${s.radarMarginKmh} km/h",
            canDecrease = s.radarMarginKmh > 0,
            canIncrease = s.radarMarginKmh < RadarSettings.MARGIN_MAX,
            onDecrease = { a.setRadarMargin(s.radarMarginKmh - 1) },
            onIncrease = { a.setRadarMargin(s.radarMarginKmh + 1) },
        )
        Row {
            PillButton(
                "Restaurar o padrão",
                enabled = s.radarZoneM != RadarSettings.ZONE_DEFAULT || s.radarMarginKmh != RadarSettings.MARGIN_DEFAULT,
                primary = false,
            ) {
                a.setRadarZone(RadarSettings.ZONE_DEFAULT)
                a.setRadarMargin(RadarSettings.MARGIN_DEFAULT)
            }
        }
        Txt("Padrão: ${RadarSettings.ZONE_DEFAULT} m e ${RadarSettings.MARGIN_DEFAULT} km/h de margem.", 14, color = p.text3)
    }
}

@Composable
internal fun StepperRow(
    title: String,
    detail: String,
    value: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(title, 22, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            RoundStepButton(Icons.minus, canDecrease, onDecrease)
            Txt(value, 26, weight = FontWeight.Bold, numeric = true, align = TextAlign.Center, modifier = Modifier.width(120.dp))
            RoundStepButton(Icons.plus, canIncrease, onIncrease)
        }
        Txt(detail, 15, color = p.text2, maxLines = 4)
    }
}

@Composable
private fun RoundStepButton(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.size(60.dp).alpha(if (enabled) 1f else 0.35f).clip(CircleShape).background(p.surface3).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Ico(icon, 28.dp, p.text)
    }
}

/** Versão instalada e do aparelho, para quem for conferir um relatório de teste. */
@Composable
private fun VersionSection(s: HomeState) {
    val p = LocalPalette.current
    SectionCard("Esta versão") {
        Txt("Rumo ${s.appVersion}", 22, weight = FontWeight.SemiBold)
        Txt("Canal ${installedChannelLabel(s.appVersion)} · código ${s.appCode}", 16, color = p.text2)
        Txt("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MODEL}", 16, color = p.text2, maxLines = 2)
    }
}

/** Reiniciar o Android: o aparelho não tem botão para isso. Pede um segundo toque para não reiniciar sem querer. */
@Composable
private fun SystemSection(a: HomeActions) {
    val p = LocalPalette.current
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(confirming) {
        if (confirming) {
            delay(CONFIRM_MS)
            confirming = false
        }
    }
    SectionCard("Sistema") {
        Txt("Reinicia o Android do aparelho (como desligar e ligar). Funciona só com o carro parado.", 16, color = p.text2, maxLines = 3)
        PillButton(
            text = if (confirming) "Toque de novo para reiniciar" else "Reiniciar o Android",
            enabled = true,
            primary = confirming,
        ) {
            if (confirming) {
                confirming = false
                a.reboot()
            } else {
                confirming = true
            }
        }
        Txt(
            if (PowerService.connected) "Serviço de acessibilidade \"Rumo\" ligado: o botão abre o menu de energia e toca em Reiniciar."
            else "Este aparelho não deixa apps reiniciarem. Ligue o serviço \"Rumo\" em Acessibilidade para o botão usar o menu de energia.",
            14, color = p.text3, maxLines = 4,
        )
        if (!PowerService.connected) {
            Row { PillButton("Abrir Acessibilidade", enabled = true, primary = false) { a.accessibilitySettings() } }
        }
    }
}

@Composable
private fun TransferBar(t: Transfer, a: HomeActions) {
    val p = LocalPalette.current
    val fraction = if (t.total > 0) (t.done.toFloat() / t.total).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(p.surface3)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(p.accent))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(
                "${t.label} · ${formatSize(t.done)} de ${formatSize(t.total)} · ${(fraction * 100).toInt()}%",
                15, color = p.text2, modifier = Modifier.weight(1f), maxLines = 2,
            )
            PillButton("Cancelar", enabled = true, primary = false) { a.cancelTransfer() }
        }
    }
}

@Composable
internal fun PillButton(text: String, enabled: Boolean, primary: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .height(56.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(28.dp))
            .background(if (primary) p.accent else p.surface3)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, 18, color = if (primary) p.onAccent else p.text, weight = FontWeight.SemiBold)
    }
}

/** Quanto tempo o botão de reiniciar espera o segundo toque. */
private const val CONFIRM_MS = 5_000L

/** Chave do download dos dados em [Transfer]. */
const val DATA_KEY = "data"
