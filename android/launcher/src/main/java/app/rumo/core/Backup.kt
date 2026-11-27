package app.rumo.core

/**
 * Backup das configurações do dono num arquivo de texto (`rumo-backup.txt`), para guardar no pendrive e voltar depois de
 * reinstalar o app ou trocar de aparelho. Entra só o que o dono configurou ([PREF_KEYS]) e os arquivos dele ([FILES]):
 * nada de mapas, nada de estado do aparelho (posição, fonte de áudio, verificação de versão, contador do carro).
 *
 * Formato, uma linha por item, campos separados por tabulação (tab, quebra de linha, barra e o separador de listas vão
 * com barra invertida):
 * ```
 * rumo-backup <TAB> 1 <TAB> versão do Rumo <TAB> data e hora
 * p <TAB> tipo <TAB> chave <TAB> valor        (tipo: s texto, i int, l long, f float, b boolean, t lista de textos)
 * f <TAB> arquivo <TAB> conteúdo
 * ```
 * Ao ler, chaves e arquivos fora das listas são ignorados: um arquivo adulterado não escreve onde não deve.
 */
object Backup {
    const val FILE = "rumo-backup.txt"
    const val FILE_PREVIOUS = "rumo-backup-previous.txt"
    private const val MAGIC = "rumo-backup"
    private const val FORMAT = 1
    private const val LIST_SEPARATOR = '\u001f'

    /**
     * Preferências que entram no backup. Configuração nova do dono: acrescentar aqui, senão ela não volta numa restauração.
     */
    val PREF_KEYS: Set<String> = setOf(
        "radar_zone_m", "radar_margin_kmh",
        "theme", "card_page",
        "favorites", "radio_names", "radio_muted", "radio_goto_unit", "radio_band", "web_favorites", "web_last",
        "obd_address", "obd_name", "obd_fuel_factor",
        "oil_type", "oil_interval_km", "oil_interval_months", "oil_last_date", "oil_last_km", "oil_viscosity", "odometer_km",
        "tank_liters", "tank_raw_full",
    )

    /** Arquivos do dono que entram (pontos marcados e diário de viagens). */
    val FILES: Set<String> = setOf(UserPoints.FILE, "trips.csv")

    /** `rumo-backup.txt`, `rumo-backup-previous.txt`, o antigo `rumo-backup-anterior.txt` ou uma cópia renomeada (`rumo-backup (1).txt`). */
    fun isBackupName(name: String): Boolean = name.startsWith("rumo-backup") && name.endsWith(".txt")

    /** O conteúdo lido de um backup. */
    class Content(val appVersion: String, val at: String, val prefs: Map<String, Any>, val files: Map<String, String>)

    fun encode(appVersion: String, at: String, prefs: Map<String, *>, files: Map<String, String>): String = buildString {
        append(MAGIC).append('\t').append(FORMAT).append('\t').append(esc(appVersion)).append('\t').append(esc(at)).append('\n')
        for (entry in prefs.entries.sortedBy { it.key }) {
            if (entry.key !in PREF_KEYS) continue
            val (type, text) = typed(entry.value) ?: continue
            append("p\t").append(type).append('\t').append(entry.key).append('\t').append(text).append('\n')
        }
        for (entry in files.entries.sortedBy { it.key }) {
            if (entry.key !in FILES) continue
            append("f\t").append(entry.key).append('\t').append(esc(entry.value)).append('\n')
        }
    }

    /** Null se o texto não é um backup deste formato (ou é de um formato mais novo que o app conhece). */
    fun decode(text: String): Content? {
        val lines = text.split('\n').map { it.trimEnd('\r') }
        val head = lines.firstOrNull()?.split('\t') ?: return null
        if (head.size < 4 || head[0] != MAGIC) return null
        val format = head[1].toIntOrNull() ?: return null
        if (format > FORMAT) return null
        val prefs = LinkedHashMap<String, Any>()
        val files = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val f = line.split('\t', limit = 4)
            when (f[0]) {
                "p" -> {
                    if (f.size < 4 || f[2] !in PREF_KEYS) continue
                    val value = parse(f[1], f[3]) ?: continue
                    prefs[f[2]] = value
                }
                "f" -> {
                    if (f.size < 3 || f[1] !in FILES) continue
                    files[f[1]] = unesc(f[2])
                }
            }
        }
        return Content(unesc(head[2]), unesc(head[3]), prefs, files)
    }

    private fun typed(value: Any?): Pair<String, String>? = when (value) {
        is String -> "s" to esc(value)
        is Int -> "i" to value.toString()
        is Long -> "l" to value.toString()
        is Float -> "f" to value.toString()
        is Boolean -> "b" to value.toString()
        is Set<*> -> "t" to value.filterIsInstance<String>().sorted().joinToString(LIST_SEPARATOR.toString()) { esc(it) }
        else -> null
    }

    private fun parse(type: String, raw: String): Any? = when (type) {
        "s" -> unesc(raw)
        "i" -> raw.toIntOrNull()
        "l" -> raw.toLongOrNull()
        "f" -> raw.toFloatOrNull()
        "b" -> when (raw) {
            "true" -> true
            "false" -> false
            else -> null
        }
        "t" -> if (raw.isEmpty()) emptySet<String>() else raw.split(LIST_SEPARATOR).map { unesc(it) }.toSet()
        else -> null
    }

    private fun esc(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                LIST_SEPARATOR -> append("\\u")
                else -> append(c)
            }
        }
    }

    private fun unesc(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                i++
                out.append(
                    when (s[i]) {
                        't' -> '\t'
                        'n' -> '\n'
                        'r' -> '\r'
                        'u' -> LIST_SEPARATOR
                        else -> s[i]
                    },
                )
            } else {
                out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
