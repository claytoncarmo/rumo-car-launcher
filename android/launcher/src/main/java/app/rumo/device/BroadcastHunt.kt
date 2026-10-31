package app.rumo.device

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Procura, no código e no manifesto do ZLINK e dos apps do fabricante, nomes de aviso (broadcast) que pareçam do
 * farol ("ILL", "night", "light", "lamp"…). O sinal do farol chega da MCU por algum aviso desses: o ZLINK o recebe
 * e põe o Android Auto no modo noturno. O teste do farol escuta todos os candidatos e mostra qual chegou.
 */
object BroadcastHunt {

    /** Nomes vistos em outros multimídias chineses; entram no teste mesmo sem aparecer nos apps. */
    val KNOWN = listOf(
        "android.intent.action.ILL", "android.intent.action.ILL_ON", "android.intent.action.ILL_OFF",
        "com.android.intent.action.ILL", "com.android.intent.action.ILL_ON", "com.android.intent.action.ILL_OFF",
        "android.intent.action.MCU_ILL", "com.microntek.light", "com.syu.ill", "com.fyt.ill", "com.ts.ill",
        "com.car.ill", "com.mcu.ill", "com.wits.ill", "android.car.action.ILLUMINATION", "com.android.car.ILL",
        "android.intent.action.HEADLIGHT", "android.intent.action.NIGHT_MODE", "com.android.action.NIGHT_MODE",
        "android.app.action.ENTER_CAR_MODE", "android.app.action.EXIT_CAR_MODE",
    )

    class Candidate(val pkg: String, val action: String)

    private val KEYWORDS = Regex("(?i)(ill|night|light|lamp|dark|daymode|day_?night|dimm)")
    // Ao menos três partes separadas por ponto, sem espaços: com.empresa.ACAO_ILL
    private val DOTTED = Regex("^[A-Za-z][\\w]*(\\.[\\w]+){2,}$")
    private val SKIP_PREFIX = listOf("java.", "javax.", "kotlin.", "androidx.", "android.support.", "com.google.", "dalvik.", "org.")
    private val DEX_NAME = Regex("classes\\d*\\.dex")
    private const val MAX_DEX = 32L * 1024 * 1024

    /**
     * O que a busca achou: [actions] candidatos a aviso; [props] propriedades do sistema que parecem de luz
     * (persist.vendor.cem.light0…); [classes] classes do android.carsource (serviço da MCU do fabricante HCN) e o
     * APK em que apareceram.
     */
    class Result(val actions: List<Candidate>, val props: List<String>, val classes: Map<String, String>)

    private val PROP = Regex("^(persist|vendor|sys|ro|debug)\\.[\\w.]+$")

    /** Lê os APKs: demora alguns segundos. Chamar fora da thread principal. */
    fun hunt(ctx: Context, zlink: String): Result {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(0).filter { a ->
            a.packageName == zlink || (a.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                !a.packageName.startsWith("com.android.") && !a.packageName.startsWith("com.google.") &&
                a.packageName != "android")
        }
        val actions = LinkedHashMap<String, Candidate>()
        val props = LinkedHashSet<String>()
        val classes = LinkedHashMap<String, String>()
        for (a in apps) {
            val apk = a.sourceDir?.let(::File) ?: continue
            if (!apk.canRead()) continue
            val visit = { s: String ->
                when {
                    "carsource" in s -> {
                        carsourceClass(s)?.let { classes.putIfAbsent(it, apk.path) }
                        // McuService.BACKLIGHT também pode ser nome de aviso: escuta junto
                        if (looksLikeAction(s)) actions.putIfAbsent(s, Candidate(a.packageName, s))
                    }
                    PROP.matches(s) -> if (KEYWORDS.containsMatchIn(s) || ".cem." in s) props += s
                    looksLikeAction(s) -> actions.putIfAbsent(s, Candidate(a.packageName, s))
                }
            }
            try {
                ZipFile(apk).use { zip ->
                    for (e in zip.entries()) {
                        val name = e.name
                        val isDex = DEX_NAME.matches(name)
                        if (!isDex && name != "AndroidManifest.xml") continue
                        if (e.size > MAX_DEX) continue
                        val bytes = zip.getInputStream(e).use { it.readBytes() }
                        if (isDex) dexStrings(bytes, visit) else axmlStrings(bytes, visit)
                    }
                }
            } catch (e: Exception) {
                // APK ilegível ou formato inesperado: segue para o próximo
            }
        }
        return Result(actions.values.toList(), props.toList(), classes)
    }

    /**
     * Teste do rádio: todos os nomes com cara de aviso (com.empresa.ACAO…) do app de rádio do aparelho, e os textos
     * do manifesto dele (onde ficam receptores e provedores de dados).
     */
    class RadioHunt(val pkgs: List<String>, val actions: List<String>, val manifest: List<String>)

    fun radio(ctx: Context): RadioHunt {
        @Suppress("DEPRECATION")
        val apps = ctx.packageManager.getInstalledApplications(0).filter { "radio" in it.packageName.lowercase() }
        val actions = LinkedHashSet<String>()
        val manifest = LinkedHashSet<String>()
        for (a in apps) {
            val apk = a.sourceDir?.let(::File)?.takeIf { it.canRead() } ?: continue
            try {
                ZipFile(apk).use { zip ->
                    for (e in zip.entries()) {
                        val isDex = DEX_NAME.matches(e.name)
                        if (!isDex && e.name != "AndroidManifest.xml" || e.size > MAX_DEX) continue
                        val bytes = zip.getInputStream(e).use { it.readBytes() }
                        if (isDex) {
                            dexStrings(bytes) { s -> if (looksLikeName(s)) actions += s }
                        } else {
                            axmlStrings(bytes) { s -> if (DOTTED.matches(s)) manifest += s }
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }
        return RadioHunt(apps.map { it.packageName }, (actions + manifest.filter(::looksLikeName)).distinct(), manifest.toList())
    }

    /**
     * Textos (do código e do manifesto) dos apps escolhidos por [pkg] que combinam com [hint], como "pacote: texto".
     * Para o teste do reinício. Lê os APKs: chamar fora da thread principal.
     */
    fun grep(ctx: Context, pkg: (String) -> Boolean, hint: (String) -> Boolean, max: Int): List<String> {
        @Suppress("DEPRECATION")
        val apps = ctx.packageManager.getInstalledApplications(0).filter { pkg(it.packageName) }
        val found = LinkedHashSet<String>()
        for (a in apps) {
            val apk = a.sourceDir?.let(::File)?.takeIf { it.canRead() } ?: continue
            try {
                ZipFile(apk).use { zip ->
                    for (e in zip.entries()) {
                        val isDex = DEX_NAME.matches(e.name)
                        if (!isDex && e.name != "AndroidManifest.xml" || e.size > MAX_DEX) continue
                        val bytes = zip.getInputStream(e).use { it.readBytes() }
                        val add = { s: String -> if (found.size < max && hint(s)) found += "${a.packageName}: $s" }
                        if (isDex) dexStrings(bytes, add) else axmlStrings(bytes, add)
                    }
                }
            } catch (e: Exception) {
            }
        }
        return found.toList()
    }

    /** Nome com pontos que não é classe nem pacote do Java/Android/Google (candidato a aviso ou chave). */
    private fun looksLikeName(s: String): Boolean {
        if (s.length !in 8..120 || !DOTTED.matches(s) || SKIP_PREFIX.any { s.startsWith(it) }) return false
        val last = s.substringAfterLast('.')
        return !(last.length > 1 && last[0].isUpperCase() && last[1].isLowerCase())
    }

    /** "Landroid/carsource/McuService;" ou "android.carsource.McuService.BACKLIGHT" → "android.carsource.McuService". */
    fun carsourceClass(s: String): String? {
        val dotted = if (s.startsWith("L") && s.endsWith(";")) s.substring(1, s.length - 1).replace('/', '.') else s
        if (!dotted.startsWith("android.carsource.") || !DOTTED.matches(dotted.replace('$', '_'))) return null
        // Corta o nome de constante ou campo no fim (MAIÚSCULAS ou minúsculas depois da classe)
        val parts = dotted.split('.')
        val cls = parts.indexOfLast { it.isNotEmpty() && it[0].isUpperCase() && it.any(Char::isLowerCase) }
        return if (cls < 0) null else parts.subList(0, cls + 1).joinToString(".")
    }

    fun looksLikeAction(s: String): Boolean {
        if (s.length !in 8..120 || !DOTTED.matches(s) || !KEYWORDS.containsMatchIn(s)) return false
        if (SKIP_PREFIX.any { s.startsWith(it) }) return false
        // Nome de classe (último pedaço "Classe") não é aviso
        val last = s.substringAfterLast('.')
        return !(last.length > 1 && last[0].isUpperCase() && last[1].isLowerCase())
    }

    /** Tabela de textos de um classes.dex. */
    private fun dexStrings(b: ByteArray, out: (String) -> Unit) {
        if (b.size < 0x70 || b[0] != 'd'.code.toByte() || b[1] != 'e'.code.toByte()) return
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val count = bb.getInt(0x38)
        val idsOff = bb.getInt(0x3C)
        for (i in 0 until count) {
            var p = bb.getInt(idsOff + i * 4)
            // tamanho em uleb128, depois MUTF-8 terminado em zero
            while (b[p].toInt() and 0x80 != 0) p++
            p++
            var end = p
            while (end < b.size && b[end] != 0.toByte() && end - p <= 160) end++
            if (end - p in 8..160) out(String(b, p, end - p, Charsets.UTF_8))
        }
    }

    /** Tabela de textos do AndroidManifest.xml binário (onde ficam as ações dos receptores fixos). */
    private fun axmlStrings(b: ByteArray, out: (String) -> Unit) {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val p = 8
        if (b.size < p + 28 || bb.getShort(p).toInt() != 0x0001) return
        val header = bb.getShort(p + 2).toInt()
        val count = bb.getInt(p + 8)
        val utf8 = bb.getInt(p + 16) and 0x100 != 0
        val start = p + bb.getInt(p + 20)
        for (i in 0 until count) {
            var q = start + bb.getInt(p + header + i * 4)
            if (utf8) {
                q += if (b[q].toInt() and 0x80 != 0) 2 else 1
                var n = b[q].toInt() and 0xFF
                if (n and 0x80 != 0) {
                    n = ((n and 0x7F) shl 8) or (b[q + 1].toInt() and 0xFF)
                    q += 2
                } else {
                    q += 1
                }
                out(String(b, q, n, Charsets.UTF_8))
            } else {
                var n = bb.getShort(q).toInt() and 0xFFFF
                q += 2
                if (n and 0x8000 != 0) {
                    n = ((n and 0x7FFF) shl 16) or (bb.getShort(q).toInt() and 0xFFFF)
                    q += 2
                }
                out(String(b, q, n * 2, Charsets.UTF_16LE))
            }
        }
    }
}
