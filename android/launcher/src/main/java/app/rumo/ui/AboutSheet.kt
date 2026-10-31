package app.rumo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.rumo.core.Channel
import app.rumo.core.DataStatus
import app.rumo.core.UpdateKind
import app.rumo.core.compareWithInstalled
import app.rumo.core.formatBuiltAt
import app.rumo.core.formatSize
import app.rumo.core.installedChannelLabel
import app.rumo.device.ChannelInfo
import app.rumo.device.DataInfo
import app.rumo.device.PowerService
import kotlinx.coroutines.delay

/** O que a consulta às releases encontrou: um canal por vez (estável e nightly) e os dados do mapa. */
data class AboutState(
    val checking: Boolean = false,
    val checked: Boolean = false,
    val stable: ChannelInfo? = null,
    val nightly: ChannelInfo? = null,
    val data: DataInfo? = null,
)

/** Download em andamento: [key] é o canal (`STABLE`/`NIGHTLY`) ou `dados`. */
data class Transfer(val key: String, val label: String, val done: Long, val total: Long)

/**
 * Painel "Rumo" (7 toques no relógio, com o carro parado): versão instalada, atualização do app por canal, dados do
 * mapa e os diagnósticos.
 */
@Composable
fun AboutSheet(s: HomeState, a: HomeActions) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().card(p).swallowTouches()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Txt("Rumo", 30, weight = FontWeight.SemiBold)
                Txt("Versão ${s.appVersion} · ${installedChannelLabel(s.appVersion)}", 16, color = p.text2)
            }
            Box(
                Modifier.size(76.dp, 64.dp).clip(RoundedCornerShape(20.dp)).background(p.surface2).clickable { s.sheet = Sheet.NONE },
                contentAlignment = Alignment.Center,
            ) {
                Ico(Icons.x, 30.dp, p.text)
            }
        }
        Row(
            Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, bottom = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AppSection(s, a)
                SystemSection(a)
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                DataSection(s, a)
                DiagnosticsSection(s, a)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
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
    val about = s.about
    SectionCard("Aplicativo") {
        Txt(
            if (about.checking) "Consultando as versões publicadas…" else "Versões publicadas no GitHub",
            16, color = p.text2,
        )
        ChannelRow(Channel.STABLE, about.stable, s, a)
        ChannelRow(Channel.NIGHTLY, about.nightly, s, a)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Buscar de novo", enabled = !about.checking && s.transfer == null, primary = false) { a.checkUpdates() }
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
    val about = s.about
    val data = about.data
    val region = data?.manifest?.regions?.firstOrNull()
    val plan = data?.plan
    val transfer = s.transfer?.takeIf { it.key == DATA_KEY }
    SectionCard("Dados do mapa" + (region?.let { " · ${it.name}" } ?: "")) {
        Txt(
            when {
                data == null -> if (about.checking) "Consultando…" else "—"
                data.problem != null -> data.problem
                plan == null -> ""
                plan.status == DataStatus.NOT_INSTALLED ->
                    "Não instalado. Baixar ${formatSize(plan.bytes)}: mapa, ruas, limites de velocidade e radares."
                plan.status == DataStatus.UPDATE -> "Atualização disponível: ${formatSize(plan.bytes)}."
                else -> "Em dia" + formatBuiltAt(data.manifest?.builtAt.orEmpty()).let { if (it.isEmpty()) "" else " · gerado em $it" }
            },
            18, color = p.text2, maxLines = 4,
        )
        if (transfer != null) {
            TransferBar(transfer, a)
        } else {
            PillButton(
                text = when (plan?.status) {
                    DataStatus.NOT_INSTALLED -> "Baixar"
                    DataStatus.UPDATE -> "Atualizar"
                    DataStatus.UP_TO_DATE -> "Em dia"
                    null -> "—"
                },
                enabled = plan != null && plan.status != DataStatus.UP_TO_DATE && s.transfer == null,
                primary = true,
            ) { a.downloadData() }
        }
        Txt(
            "Use o wi-fi do carro ou o ponto de acesso do celular. Se a conexão cair, o download continua de onde parou. Dados do OpenStreetMap (© colaboradores).",
            14, color = p.text3, maxLines = 4,
        )
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
private fun PillButton(text: String, enabled: Boolean, primary: Boolean, onClick: () -> Unit) {
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
const val DATA_KEY = "dados"
