package app.rumo.device

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Reinicia o Android. Um app comum não pode (a permissão REBOOT é só de apps do sistema), então tenta, em ordem:
 * `PowerManager.reboot` (vale se o Rumo estiver instalado como app privilegiado) e `su` (aparelho com root).
 */
object Reboot {
    private val SU = listOf(
        arrayOf("su", "-c", "reboot"),
        arrayOf("su", "-c", "svc power reboot"),
    )

    /** Null se o reinício foi aceito (o aparelho cai em instantes); senão, por que cada tentativa falhou. Lê o disco e espera processos: fora da thread principal. */
    @SuppressLint("MissingPermission")
    fun request(ctx: Context): String? {
        val failures = mutableListOf<String>()
        try {
            ctx.getSystemService(PowerManager::class.java).reboot(null)
            return null
        } catch (e: SecurityException) {
            failures += "sem a permissão REBOOT"
        } catch (e: Exception) {
            failures += e.javaClass.simpleName
        }
        for (cmd in SU) {
            val problem = run(cmd) ?: return null
            failures += "${cmd.drop(1).joinToString(" ")}: $problem"
        }
        return failures.joinToString(" · ")
    }

    private fun run(cmd: Array<String>): String? = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (!p.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
            p.destroy()
            "sem resposta"
        } else if (p.exitValue() == 0) {
            null
        } else {
            p.inputStream.bufferedReader().readText().trim().take(60).ifEmpty { "código ${p.exitValue()}" }
        }
    } catch (e: IOException) {
        "sem root"
    }

    private const val TIMEOUT_S = 5L
}
