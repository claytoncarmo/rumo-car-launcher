package app.rumo.device

import android.app.UiModeManager
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Teste do farol: o multimídia escurece a tela e o Android Auto com o farol aceso, mas não liga o modo noturno
 * do Android. Esta foto dos ajustes do sistema (System, Global, Secure), das propriedades (getprop), do brilho da
 * tela e do modo de interface, tirada antes e depois de acender o farol, mostra por onde o aparelho passa o sinal.
 */
object NightSignal {

    /** Lê tudo que um app comum consegue ler. Demora um pouco (getprop): chamar fora da thread principal. */
    fun snapshot(ctx: Context): Map<String, String> {
        val out = HashMap<String, String>()
        for (table in listOf("system", "global", "secure")) readSettings(ctx, table, out)
        readProps(out)
        readSys(out)
        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        out["ui:nightMode"] = ui.nightMode.toString()
        out["ui:uiMode"] = ctx.resources.configuration.uiMode.toString()
        return out
    }

    /** Linhas "chave: antes → depois", só do que mudou. */
    fun diff(before: Map<String, String>, after: Map<String, String>): List<String> =
        (before.keys + after.keys).sorted().mapNotNull { k ->
            val a = before[k]
            val b = after[k]
            if (a == b) null else "$k: ${a ?: "(nada)"} → ${b ?: "(nada)"}"
        }

    private fun readSettings(ctx: Context, table: String, out: MutableMap<String, String>) {
        try {
            ctx.contentResolver.query(Uri.parse("content://settings/$table"), arrayOf("name", "value"), null, null, null)?.use { c ->
                while (c.moveToNext()) out["$table:${c.getString(0)}"] = c.getString(1) ?: ""
            }
        } catch (e: Exception) {
            // tabela protegida neste aparelho
        }
    }

    private val PROP = Regex("""^\[(.+?)]: \[(.*)]$""")

    private fun readProps(out: MutableMap<String, String>) {
        try {
            val p = Runtime.getRuntime().exec("getprop")
            p.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> PROP.find(line)?.let { out["prop:${it.groupValues[1]}"] = it.groupValues[2] } }
            }
            p.waitFor()
        } catch (e: Exception) {
        }
    }

    private fun readSys(out: MutableMap<String, String>) {
        val files = mutableListOf(File("/sys/class/leds/lcd-backlight/brightness"))
        File("/sys/class/backlight").listFiles()?.forEach { files += File(it, "brightness") }
        for (f in files) {
            try {
                out["sys:${f.path}"] = f.readText().trim()
            } catch (e: Exception) {
            }
        }
    }
}
