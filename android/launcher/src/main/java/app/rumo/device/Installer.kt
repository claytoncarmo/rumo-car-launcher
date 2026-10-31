package app.rumo.device

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

enum class InstallResult {
    /** O instalador do Android abriu: falta o usuário confirmar. */
    OPENED,

    /** O Rumo ainda não pode instalar apps: abriu a tela para liberar "instalar apps desconhecidos". */
    NEEDS_PERMISSION,

    /** O aparelho não tem o instalador (ou a tela de permissão). */
    UNAVAILABLE,
}

/** Instala um APK já baixado pelo instalador do Android (sempre com a confirmação do usuário). */
object Installer {
    /** Pasta do APK baixado: em `cache/atualizacoes`, a mesma do `res/xml/file_paths.xml`. */
    fun folder(context: Context): File = File(context.cacheDir, "atualizacoes").also { it.mkdirs() }

    fun install(context: Context, apk: File): InstallResult {
        if (!context.packageManager.canRequestPackageInstalls()) {
            return try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                InstallResult.NEEDS_PERMISSION
            } catch (e: ActivityNotFoundException) {
                InstallResult.UNAVAILABLE
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.arquivos", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            InstallResult.OPENED
        } catch (e: ActivityNotFoundException) {
            InstallResult.UNAVAILABLE
        }
    }
}
