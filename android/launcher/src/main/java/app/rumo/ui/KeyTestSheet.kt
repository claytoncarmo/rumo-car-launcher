package app.rumo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Teste dos botões do aparelho (volume, início, power…): mostra cada tecla que o Android entrega ao Rumo, com o
 * dispositivo que a mandou, e os avisos de tela acesa/apagada. Só lê; não toca na MCU. Início e Power o Android não
 * entrega aos apps: Início aparece como a tela inicial pedida e Power como a tela apagando.
 */
@Composable
fun KeyTestSheet(s: HomeState, a: HomeActions) {
    val p = LocalPalette.current
    DisposableEffect(Unit) {
        a.keyTest(true)
        onDispose { a.keyTest(false) }
    }
    Column(Modifier.fillMaxSize().card(p).swallowTouches()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Txt("Teste dos botões", 30, weight = FontWeight.SemiBold)
                Txt("Aperte os botões do aparelho: volume, início, power…", 16, color = p.text2)
            }
            Box(
                Modifier.size(76.dp, 64.dp).clip(RoundedCornerShape(20.dp)).background(p.surface2).clickable { s.sheet = Sheet.NONE },
                contentAlignment = Alignment.Center,
            ) {
                Ico(Icons.x, 30.dp, p.text)
            }
        }
        Column(
            Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Txt(
                "Cada tecla que o Android entregar ao Rumo aparece abaixo, com o nome do dispositivo que a mandou. " +
                    "Início e Power não chegam aos apps: se o Início trouxe você até aqui, ele funciona; o Power aparece como a tela apagando.",
                15, color = p.text3, maxLines = 4,
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surface2).padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (s.keyLog.isEmpty()) Txt("Nenhuma tecla ainda.", 18, color = p.text2)
                s.keyLog.forEach { Txt(it, 17, maxLines = 2) }
            }
        }
    }
}
