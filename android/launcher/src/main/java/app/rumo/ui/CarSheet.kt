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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.sp
import app.rumo.core.Maintenance
import app.rumo.core.OilType
import kotlinx.coroutines.delay
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
    var editingOil by remember { mutableStateOf(false) }
    // Andou com o diálogo aberto: fecha (o teclado não fica na tela com o carro andando)
    LaunchedEffect(parked) { if (!parked) editingOil = false }
    Box(Modifier.fillMaxSize()) {
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
                OilSection(s, a, parked) { editingOil = true }
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
    if (editingOil) OilDialog(s, a) { editingOil = false }
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

/**
 * Troca de óleo: o intervalo vem do tipo do óleo (o dono ajusta) e conta o que vier primeiro, km ou tempo. Os km andam
 * pelo contador do carro (PID 31) a partir do km do painel informado pelo dono. Na primeira vez, pede a última troca.
 */
@Composable
private fun OilSection(s: HomeState, a: HomeActions, parked: Boolean, onEdit: () -> Unit) {
    val p = LocalPalette.current
    val plan = s.oilPlan
    SectionCard("Troca de óleo") {
        if (plan == null) {
            Txt("Avisa quando a troca do óleo do motor estiver perto, pelos km ou pelo tempo. Informe o óleo e a última troca.", 17, color = p.text2, maxLines = 4)
            Row { PillButton("Configurar", enabled = parked, primary = true, onClick = onEdit) }
            if (!parked) Txt("Só com o carro parado.", 15, color = p.text3)
        } else {
            OilStatusBody(s, a, plan, parked, onEdit)
        }
    }
}

@Composable
private fun OilStatusBody(s: HomeState, a: HomeActions, plan: app.rumo.core.OilPlan, parked: Boolean, onEdit: () -> Unit) {
    val p = LocalPalette.current
    var confirmForget by remember { mutableStateOf(false) }
    LaunchedEffect(confirmForget) {
        if (confirmForget) {
            delay(5_000)
            confirmForget = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val st = Maintenance.status(plan, s.odometerKm, java.time.LocalDate.now())
        Txt(
            Maintenance.text(st), 24, weight = FontWeight.SemiBold, maxLines = 2,
            color = when {
                st.due -> p.danger
                st.soon -> p.accentInk
                else -> p.text
            },
        )
        Txt(
            "Próxima: ${kmText(plan.nextKm)} ou ${Maintenance.formatDate(plan.nextDate)}",
            16, color = p.text2, maxLines = 2,
        )
        Txt(
            "Última: ${Maintenance.formatDate(plan.lastDate)} com ${kmText(plan.lastKm)} · ${plan.type.label}, " +
                "a cada ${kmText(plan.intervalKm)} ou ${monthsText(plan.intervalMonths)}",
            15, color = p.text3, maxLines = 3,
        )
        Txt(
            s.odometerKm?.let { "Painel agora: cerca de ${kmText(Math.round(it).toInt())}" } ?: "Km do painel não informado: conta só pelo tempo",
            15, color = p.text3, maxLines = 2,
        )
        if (s.obd.supported.isNotEmpty() && 0x31 !in s.obd.supported) {
            Txt("Este carro não informa a distância rodada: atualize o km do painel em Editar de vez em quando.", 14, color = p.text3, maxLines = 3)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Troquei hoje", enabled = parked && s.odometerKm != null, primary = st.due || st.soon) { a.maintDone() }
            PillButton("Editar", enabled = parked, primary = false, onClick = onEdit)
            PillButton(if (confirmForget) "Apagar?" else "Apagar", enabled = parked, primary = confirmForget) {
                if (confirmForget) {
                    confirmForget = false
                    a.maintForget()
                } else {
                    confirmForget = true
                }
            }
        }
        if (!parked) Txt("Os botões funcionam só com o carro parado.", 15, color = p.text3)
    }
}

private fun kmText(km: Int): String = String.format(PT_BR, "%,d km", km)

private fun monthsText(m: Int): String = if (m == 1) "1 mês" else "$m meses"

/** Configurar a troca de óleo: tipo (dá o intervalo padrão), intervalo, última troca e o km do painel hoje. */
@Composable
private fun OilDialog(s: HomeState, a: HomeActions, onClose: () -> Unit) {
    val p = LocalPalette.current
    val plan = s.oilPlan
    var type by remember { mutableStateOf(plan?.type ?: OilType.SEMI_SYNTHETIC) }
    var intervalKm by remember { mutableStateOf(plan?.intervalKm ?: type.intervalKm) }
    var intervalMonths by remember { mutableStateOf(plan?.intervalMonths ?: type.intervalMonths) }
    var date by remember { mutableStateOf(dateField(plan?.let { Maintenance.formatDate(it.lastDate).filter(Char::isDigit) } ?: "")) }
    var lastKm by remember { mutableStateOf(plan?.lastKm?.toString() ?: "") }
    var nowKm by remember { mutableStateOf(s.odometerKm?.let { Math.round(it).toString() } ?: "") }

    val parsedDate = Maintenance.parseDate(date.text)
    val parsedLast = Maintenance.parseKm(lastKm)
    val parsedNow = if (nowKm.isBlank()) null else Maintenance.parseKm(nowKm)
    val today = java.time.LocalDate.now()
    val problem = when {
        parsedDate == null -> "Informe a data da última troca (dia/mês/ano)."
        parsedDate.isAfter(today) -> "A data da última troca está no futuro."
        parsedLast == null -> "Informe o km do painel na última troca."
        nowKm.isNotBlank() && parsedNow == null -> "O km do painel hoje não é um número."
        parsedNow != null && parsedNow < parsedLast -> "O km de hoje é menor que o da última troca."
        else -> null
    }

    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onClose), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .padding(top = 16.dp, bottom = 16.dp)
                .width(820.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(p.surface)
                .clickable(enabled = false) {}
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Txt("Troca de óleo", 26, weight = FontWeight.SemiBold)
            Txt("Óleo usado (o manual do carro manda; o intervalo abaixo é o mais comum para cada tipo)", 16, color = p.text2, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OilType.entries.forEach { t ->
                    Chip(t.label, on = t == type, enabled = true, modifier = Modifier.weight(1f)) {
                        type = t
                        intervalKm = t.intervalKm
                        intervalMonths = t.intervalMonths
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallStepper("A cada", kmText(intervalKm), Modifier.weight(1f), intervalKm > 1_000, intervalKm < 30_000,
                    { intervalKm -= 500 }, { intervalKm += 500 })
                SmallStepper("ou", monthsText(intervalMonths), Modifier.weight(1f), intervalMonths > 1, intervalMonths < 24,
                    { intervalMonths -= 1 }, { intervalMonths += 1 })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                NumberField("Data da última troca", date, "dd/mm/aaaa", Modifier.weight(1f)) { date = it }
                NumberField("Km na última troca", lastKm, "ex.: 40000", Modifier.weight(1f)) { lastKm = it.filter(Char::isDigit).take(7) }
                NumberField("Km do painel hoje", nowKm, "opcional", Modifier.weight(1f)) { nowKm = it.filter(Char::isDigit).take(7) }
            }
            Txt(
                problem ?: "Com o scanner ligado, o Rumo anda os km sozinho a partir do km do painel de hoje.",
                15, color = if (problem != null) p.danger else p.text3, maxLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.weight(1f))
                PillButton("Cancelar", enabled = true, primary = false, onClick = onClose)
                PillButton("Salvar", enabled = problem == null, primary = true) {
                    a.maintSave(type, intervalKm, intervalMonths, parsedDate!!, parsedLast!!, parsedNow)
                    onClose()
                }
            }
        }
    }
}

@Composable
private fun SmallStepper(label: String, value: String, modifier: Modifier, canDec: Boolean, canInc: Boolean, onDec: () -> Unit, onInc: () -> Unit) {
    val p = LocalPalette.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt(label, 18, color = p.text2)
        PillButton("−", enabled = canDec, primary = false, onClick = onDec)
        Txt(value, 22, weight = FontWeight.SemiBold, numeric = true, modifier = Modifier.weight(1f), align = androidx.compose.ui.text.style.TextAlign.Center)
        PillButton("+", enabled = canInc, primary = false, onClick = onInc)
    }
}

@Composable
private fun NumberField(label: String, value: String, hint: String, modifier: Modifier, onChange: (String) -> Unit) {
    NumberFieldBox(label, hint, value.isEmpty(), modifier) { style ->
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true, textStyle = style,
            cursorBrush = SolidColor(LocalPalette.current.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Data digitada só com números: as barras entram sozinhas. */
@Composable
private fun NumberField(label: String, value: TextFieldValue, hint: String, modifier: Modifier, onChange: (TextFieldValue) -> Unit) {
    NumberFieldBox(label, hint, value.text.isEmpty(), modifier) { style ->
        BasicTextField(
            value = value,
            onValueChange = { v ->
                var digits = v.text.filter(Char::isDigit).take(8)
                // Apagou só a barra: apaga o número antes dela
                if (v.text.length < value.text.length && digits == value.text.filter(Char::isDigit)) digits = digits.dropLast(1)
                onChange(dateField(digits))
            },
            singleLine = true, textStyle = style,
            cursorBrush = SolidColor(LocalPalette.current.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NumberFieldBox(label: String, hint: String, empty: Boolean, modifier: Modifier, field: @Composable (TextStyle) -> Unit) {
    val p = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(label, 15, color = p.text2, maxLines = 1)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface2).padding(14.dp)) {
            if (empty) Txt(hint, 22, color = p.text3)
            field(TextStyle(color = p.text, fontSize = 22.sp))
        }
    }
}

/** "01062026" → "01/06/2026" (com o cursor no fim). */
private fun dateField(digits: String): TextFieldValue {
    val t = buildString {
        append(digits.take(2))
        if (digits.length > 2) append('/').append(digits.substring(2, minOf(4, digits.length)))
        if (digits.length > 4) append('/').append(digits.substring(4))
    }
    return TextFieldValue(t, TextRange(t.length))
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
