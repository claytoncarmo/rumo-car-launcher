package app.rumo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.rumo.core.Channel
import app.rumo.core.formatBuiltAt
import app.rumo.core.formatSize
import app.rumo.core.noticeChannel

/**
 * Popup da versão nova (ícone ao lado do tema): as novidades em poucas linhas, lidas rápido, e o botão de baixar.
 * Tocar fora fecha, menos durante o download (que se cancela pelo botão).
 */
@Composable
fun UpdatePopup(s: HomeState, a: HomeActions) {
    val p = LocalPalette.current
    val release = s.update ?: return
    val channel = noticeChannel(s.appVersion)
    val transfer = s.transfer?.takeIf { channel != null && it.key == channel.name }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (transfer == null) s.updateOpen = false
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(660.dp).card(p).swallowTouches().padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(if (channel == Channel.NIGHTLY) "Nova nightly disponível" else "Nova versão disponível", 30, weight = FontWeight.SemiBold)
                Txt(
                    listOf(release.versionName, formatBuiltAt(release.builtAt), formatSize(release.size))
                        .filter { it.isNotEmpty() }.joinToString(" · "),
                    18, color = p.text2,
                )
                Txt("Instalada: ${s.appVersion}", 16, color = p.text3)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt("Novidades", 15, color = p.text2, weight = FontWeight.SemiBold, caps = true)
                if (release.notes.isEmpty()) {
                    Txt("Esta versão não traz lista de novidades.", 20, color = p.text2)
                } else {
                    release.notes.forEach { note ->
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.padding(top = 11.dp).size(8.dp).clip(CircleShape).background(p.accent))
                            Txt(note, 22, maxLines = 2, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            if (transfer != null) {
                TransferBar(transfer, a)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Baixar e instalar", enabled = true, primary = true) { a.installUpdate() }
                    PillButton("Agora não", enabled = true, primary = false) { s.updateOpen = false }
                }
            }
        }
    }
}
