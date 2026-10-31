package app.rumo.device

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import app.rumo.core.PT_BR
import java.text.Collator

/** [system]: veio com o aparelho (não dá para desinstalar, só tirar atualizações). */
data class AppEntry(
    val label: String,
    val pkg: String,
    val activity: String,
    val icon: ImageBitmap,
    val system: Boolean = false,
)

object Apps {
    /** App que faz o Android Auto neste aparelho. */
    const val ZLINK = "com.zjinnova.zlink"

    private const val ICON_PX = 80

    // O rádio, o Bluetooth e os players do aparelho são apps do fabricante: só dá para reconhecê-los pelo nome
    private val AUDIO_HINTS = listOf(
        "radio", "rádio", "fmradio", "tuner", "am/fm", "music", "música", "musica", "player",
        "usb", "bt music", "btmusic", "media", "mídia",
    )
    private val RADIO_HINTS = listOf("radio", "rádio", "fmradio", "tuner", "am/fm")
    private val PHONE_HINTS = listOf(
        "phone", "telefone", "dialer", "discador", "bluetooth", "btphone", "bt phone", "handsfree", "contatos", "contacts",
    )

    /** Apps com ícone na gaveta, em ordem alfabética. Carrega os ícones: chamar fora da thread principal. */
    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val collator = Collator.getInstance(PT_BR)
        return pm.queryIntentActivities(main, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { ri ->
                AppEntry(
                    label = ri.loadLabel(pm).toString(),
                    pkg = ri.activityInfo.packageName,
                    activity = ri.activityInfo.name,
                    icon = ri.loadIcon(pm).toBitmap(ICON_PX, ICON_PX).asImageBitmap(),
                    system = ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    fun isAudio(app: AppEntry): Boolean = app.pkg == ZLINK || matches(app, AUDIO_HINTS)

    fun isRadio(app: AppEntry): Boolean = matches(app, RADIO_HINTS)

    fun isPhone(app: AppEntry): Boolean = matches(app, PHONE_HINTS)

    /** O rádio do aparelho: de preferência o app com "rádio" no nome. */
    fun findRadio(apps: List<AppEntry>): AppEntry? = apps.filter(::isRadio)
        .minByOrNull { if (it.label.lowercase(PT_BR).let { l -> "rádio" in l || "radio" in l }) 0 else 1 }

    fun launchIntent(app: AppEntry): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName(app.pkg, app.activity)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    /** Assistente de voz: comando de voz ou, sem ele, o assistente do sistema; null se não houver nenhum. */
    fun voiceIntent(ctx: Context): Intent? =
        listOf(Intent(Intent.ACTION_VOICE_COMMAND), Intent(Intent.ACTION_ASSIST))
            .firstOrNull { ctx.packageManager.resolveActivity(it, 0) != null }

    fun detailsIntent(app: AppEntry): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null))

    fun uninstallIntent(app: AppEntry): Intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.pkg, null))

    private fun matches(app: AppEntry, hints: List<String>): Boolean {
        val text = (app.label + " " + app.pkg).lowercase(PT_BR)
        return hints.any { it in text }
    }
}
