package app.rumo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.rumo.core.ObdPids
import app.rumo.core.PT_BR
import app.rumo.device.ObdScan
import app.rumo.device.ObdStatus

/** "12,4 km/L", "1,5 L/h" ou "--". */
internal fun fuelText(value: Double?, unit: String): String = value?.let { String.format(PT_BR, "%.1f %s", it, unit) } ?: "--"

/**
 * Página "Carro": tudo o que o scanner OBD2 (Bluetooth) consegue ler do carro, ao vivo, mais a conexão com o scanner e a
 * conta do consumo. Na esquerda, o scanner e o consumo; na direita, os dados que este carro informa, por grupo.
 */
@Composable
fun CarSheet(s: HomeState, a: HomeActions) {
    val p = LocalPalette.current
    val o = s.obd
    val parked = (s.gps.speedKmh ?: 0) < 5
    Column(Modifier.fillMaxSize().card(p).swallowTouches()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Txt("Carro", 30, weight = FontWeight.SemiBold)
                Txt(statusLine(s), 16, color = p.text2)
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
            // Esquerda: scanner e consumo
            Column(Modifier.weight(0.9f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ScannerSection(s, a, parked)
                FuelSection(s, a)
            }
            // Direita: os dados do carro
            Box(Modifier.weight(1.3f).fillMaxHeight()) {
                if (o.supported.isEmpty()) {
                    Txt(
                        when {
                            s.obdScanner == null -> "Escolha o scanner OBD2 ao lado para ver os dados do carro."
                            o.status == ObdStatus.LIVE -> "Lendo o que o carro informa…"
                            o.status == ObdStatus.CAR_OFF -> "Ligue a ignição para ler o carro."
                            else -> "Sem leitura ainda."
                        },
                        18, color = p.text2, maxLines = 3,
                    )
                } else {
                    Readings(s)
                }
            }
        }
    }
}

private fun statusLine(s: HomeState): String {
    val o = s.obd
    val name = s.obdScanner?.let { it.name ?: "scanner" }
    return when {
        s.obdScanner == null -> "Nenhum scanner OBD2 escolhido"
        o.status == ObdStatus.LIVE -> "Lendo o carro pelo $name" + (o.voltage?.let { String.format(PT_BR, " · bateria %.1f V", it) } ?: "")
        o.status == ObdStatus.CAR_OFF -> "$name conectado · ignição desligada"
        o.status == ObdStatus.CONNECTING -> o.message ?: "Conectando no $name…"
        o.status == ObdStatus.ERROR -> o.message ?: "Sem conexão com o $name"
        else -> "$name · parado (Rumo fora da tela)"
    }
}

@Composable
private fun ScannerSection(s: HomeState, a: HomeActions, parked: Boolean) {
    val p = LocalPalette.current
    SectionCard("Scanner OBD2 (Bluetooth)") {
        Txt(
            s.obdScanner?.let { "Escolhido: ${it.name ?: "sem nome"}" } ?: "Ligue o scanner na tomada do carro (com a ignição ligada) e toque em Procurar.",
            17, color = p.text2, maxLines = 3,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(if (s.obdSearching) "Procurando…" else "Procurar", enabled = parked && !s.obdSearching, primary = s.obdScanner == null) { a.carSearch() }
            if (s.obdScanner != null) {
                PillButton("Reconectar", enabled = true, primary = false) { a.carReconnect() }
                PillButton("Esquecer", enabled = true, primary = false) { a.carForget() }
            }
        }
        if (!parked) Txt("A procura só funciona com o carro parado.", 15, color = p.text3)
        if (s.obdFound.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val list = s.obdFound.sortedByDescending { ObdScan.looksLikeScanner(it.name) }
                list.forEach { d ->
                    val likely = ObdScan.looksLikeScanner(d.name)
                    Row(
                        Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (likely) p.accentSoft else p.surface3).clickable { a.carChoose(d) }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Txt(d.name ?: "(sem nome)", 18, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (likely) Txt("scanner", 14, color = p.accentInk)
                    }
                }
            }
        }
    }
}

@Composable
private fun FuelSection(s: HomeState, a: HomeActions) {
    val p = LocalPalette.current
    val o = s.obd
    SectionCard("Consumo") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FuelStat(fuelText(o.kmPerLiter, "km/L"), "agora", Modifier.weight(1f))
            FuelStat(fuelText(o.litersPerHour, "L/h"), "taxa", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FuelStat(fuelText(o.tripKmPerLiter, "km/L"), "média da viagem", Modifier.weight(1f))
            FuelStat(String.format(PT_BR, "%.1f L · %.0f km", o.tripLiters, o.tripKm), "gasto e distância", Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Zerar a viagem", enabled = true, primary = false) { a.carResetTrip() }
        }
        Txt("Ajuste do consumo", 16, color = p.text2, weight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("−", enabled = s.obdFuelFactor > 0.51, primary = false) { a.carFuelFactor(-2) }
            Txt("${Math.round(s.obdFuelFactor * 100)} %", 24, weight = FontWeight.SemiBold, numeric = true, modifier = Modifier.width(96.dp))
            PillButton("+", enabled = s.obdFuelFactor < 1.99, primary = false) { a.carFuelFactor(2) }
        }
        Txt(
            "O carro não informa o consumo: é estimado pelo ar que entra no motor e erra uns 10 %. Abasteça o tanque, rode, " +
                "e se o gasto de verdade for maior que o estimado, suba o ajuste (e o contrário).",
            14, color = p.text3, maxLines = 5,
        )
    }
}

@Composable
private fun FuelStat(value: String, label: String, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(p.surface3).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Txt(value, 22, weight = FontWeight.SemiBold, numeric = true)
        Txt(label, 14, color = p.text2)
    }
}

/** Os dados que o carro informa, em grupos (motor, combustível…), mais a luz de injeção e os códigos de falha. */
@Composable
private fun Readings(s: HomeState) {
    val p = LocalPalette.current
    val o = s.obd
    LazyVerticalGrid(
        columns = GridCells.Adaptive(190.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (group in ObdPids.Group.entries) {
            val defs = ObdPids.DEFS.filter { it.group == group && it.pid in o.supported }
            if (defs.isEmpty() && group != ObdPids.Group.SERVICE) continue
            item(span = { GridItemSpan(maxLineSpan) }) {
                Txt(group.label, 15, color = p.text2, weight = FontWeight.SemiBold, caps = true, modifier = Modifier.padding(top = 6.dp))
            }
            // Tensão da bateria (lida pelo scanner) no grupo elétrico
            if (group == ObdPids.Group.SENSORS) {
                item { Reading("Bateria (pelo scanner)", o.voltage?.let { String.format(PT_BR, "%.1f V", it) } ?: "--") }
            }
            items(defs.size) { i ->
                val d = defs[i]
                Reading(d.label, o.values[d.pid]?.let { ObdPids.display(d.pid, it) } ?: "--")
            }
            if (group == ObdPids.Group.SERVICE) {
                item { Reading("Luz de injeção", when (o.milOn) { true -> "acesa"; false -> "apagada"; null -> "--" }) }
                item { Reading("Falhas guardadas", o.dtcCount?.toString() ?: "--") }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface2).padding(14.dp)) {
                        Txt("Códigos de falha", 14, color = p.text2)
                        val codes = o.dtcs
                        Txt(
                            when {
                                codes == null -> "Lendo…"
                                codes.isEmpty() -> "Nenhum código guardado"
                                else -> codes.joinToString("  ·  ")
                            },
                            20, weight = FontWeight.SemiBold, maxLines = 3,
                        )
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun Reading(label: String, value: String) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface2).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Txt(value, 24, weight = FontWeight.SemiBold, numeric = true)
        Txt(label, 14, color = p.text2, maxLines = 2)
    }
}
