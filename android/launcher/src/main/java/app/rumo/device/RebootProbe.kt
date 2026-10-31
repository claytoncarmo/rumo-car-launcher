package app.rumo.device

import android.Manifest
import android.content.Context
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.os.Build
import app.rumo.core.looksLikeRebootHook
import java.io.File

/**
 * Teste do reinício: o Rumo não tem a permissão REBOOT nem root, então procura no aparelho um gancho do fabricante
 * (HCN, ZLINK, configurações de fábrica) que reinicie: componentes, ações, métodos dos serviços da MCU e serviços do
 * sistema com nome de reiniciar/desligar. Só lê; não aciona nada.
 */
object RebootProbe {
    class Report(val summary: List<String>, val full: List<String>)

    private val MAKER = Regex("(?i)(hcn|zjinnova|zlink|factory|mcu|engineer|setting|carsource)")
    private val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/vendor/bin/su", "/system/sbin/su")
    private val PROPS = listOf(
        "ro.secure", "ro.debuggable", "ro.adb.secure", "service.adb.tcp.port", "persist.adb.tcp.port",
        "sys.powerctl", "persist.sys.usb.config", "ro.build.tags",
    )
    private val CLASSES = listOf(
        "android.carsource.McuConstant", "android.carsource.IMcuManager", "android.carsource.IMcuManager\$Stub",
        "android.carstatus.ICarStatus", "android.carstatus.ICarStatus\$Stub",
        "com.android.car.settings.ICarSettings", "com.android.car.settings.ICarSettings\$Stub",
        "android.sourceservice.ISourceService", "android.sourceservice.ISourceService\$Stub",
        "android.os.IPowerManager", "android.os.PowerManager",
    )
    private const val MAX_FULL = 400
    private const val MAX_SUMMARY = 12

    /** Lê APKs e o sistema: chamar fora da thread principal. */
    fun run(ctx: Context): Report {
        val system = system(ctx)
        val hooks = components(ctx)
        val strings = BroadcastHunt.grep(ctx, { MAKER.containsMatchIn(it) }, ::looksLikeRebootHook, MAX_FULL)
        val members = members()
        val services = services()
        val maker = makerActivities(ctx)

        val summary = buildList {
            addAll(system.take(4))
            add("Componentes com nome de reiniciar/desligar (${hooks.size}):")
            addAll(hooks.take(MAX_SUMMARY).ifEmpty { listOf("nenhum") })
            add("Textos nos apps do fabricante (${strings.size}):")
            addAll(strings.take(MAX_SUMMARY).ifEmpty { listOf("nenhum") })
            add("Métodos e constantes dos serviços da MCU (${members.size}):")
            addAll(members.take(MAX_SUMMARY).ifEmpty { listOf("nenhum") })
            add("Serviços do sistema com esse nome (${services.hinted.size}):")
            addAll(services.hinted.ifEmpty { listOf("nenhum") })
        }
        val full = buildList {
            add("Aparelho:")
            addAll(system)
            add("Componentes com nome de reiniciar/desligar (${hooks.size}):")
            addAll(hooks.ifEmpty { listOf("nenhum") })
            add("Textos nos apps do fabricante (${strings.size}):")
            addAll(strings.ifEmpty { listOf("nenhum") })
            add("Métodos e constantes dos serviços da MCU (${members.size}):")
            addAll(members.ifEmpty { listOf("nenhum") })
            add("Todos os serviços do sistema (${services.all.size}):")
            addAll(services.all)
            add("Telas e componentes expostos dos apps do fabricante (${maker.size}):")
            addAll(maker.ifEmpty { listOf("nenhum") })
        }
        return Report(summary, full)
    }

    private fun system(ctx: Context): List<String> = buildList {
        add("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.TYPE} · ${Build.TAGS}")
        val reboot = ctx.checkSelfPermission(Manifest.permission.REBOOT) == PackageManager.PERMISSION_GRANTED
        val su = SU_PATHS.filter { File(it).exists() }
        add("Permissão REBOOT: ${if (reboot) "concedida" else "negada"} · su: ${su.joinToString().ifEmpty { "nenhum" }}")
        add("Digital do sistema: ${Build.FINGERPRINT}")
        CarSourceProbe.props(PROPS).forEach { (k, v) -> add("$k = $v") }
    }

    /** Atividades, receptores, serviços e provedores com nome que parece de reiniciar/desligar, em qualquer app. */
    private fun components(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS
        val out = ArrayList<String>()
        for (pkg in packages(ctx)) {
            val info = try {
                pm.getPackageInfo(pkg, flags)
            } catch (e: Exception) {
                continue
            }
            fun add(kind: String, list: Array<out ComponentInfo>?, permission: (ComponentInfo) -> String?) {
                for (c in list.orEmpty()) {
                    if (out.size < MAX_FULL && looksLikeRebootHook(c.name)) out += describe(pkg, kind, c, permission(c))
                }
            }
            add("tela", info.activities) { (it as? android.content.pm.ActivityInfo)?.permission }
            add("receptor", info.receivers) { (it as? android.content.pm.ActivityInfo)?.permission }
            add("serviço", info.services) { (it as? android.content.pm.ServiceInfo)?.permission }
            add("provedor", info.providers) { (it as? android.content.pm.ProviderInfo)?.readPermission }
        }
        return out
    }

    /** Todas as telas e componentes de apps do fabricante que outros apps podem abrir (onde costuma ficar o menu de fábrica). */
    private fun makerActivities(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES
        val out = ArrayList<String>()
        for (pkg in packages(ctx).filter { MAKER.containsMatchIn(it) }) {
            val info = try {
                pm.getPackageInfo(pkg, flags)
            } catch (e: Exception) {
                continue
            }
            for ((kind, list) in listOf("tela" to info.activities, "receptor" to info.receivers, "serviço" to info.services)) {
                for (c in list.orEmpty()) if (out.size < MAX_FULL && c.exported) out += describe(pkg, kind, c, null)
            }
        }
        return out
    }

    private fun packages(ctx: Context): List<String> {
        @Suppress("DEPRECATION")
        return ctx.packageManager.getInstalledApplications(0).map { it.packageName }.sorted()
    }

    private fun describe(pkg: String, kind: String, c: ComponentInfo, permission: String?) =
        "$pkg/${c.name.removePrefix(pkg)} ($kind, ${if (c.exported) "exportado" else "interno"}${permission?.let { ", exige $it" }.orEmpty()})"

    /** Constantes e métodos com nome de reiniciar/desligar nas classes do framework da MCU, do carro e da energia. */
    private fun members(): List<String> {
        val out = ArrayList<String>()
        for (name in CLASSES) {
            val cls = try {
                Class.forName(name)
            } catch (e: Throwable) {
                continue
            }
            try {
                for (f in cls.declaredFields) if (looksLikeRebootHook(f.name)) out += "$name.${f.name} (campo)"
                for (m in cls.declaredMethods) if (looksLikeRebootHook(m.name)) {
                    out += "$name.${m.name}(${m.parameterTypes.joinToString { it.simpleName }}) (método)"
                }
            } catch (e: Throwable) {
            }
        }
        return out
    }

    private class Services(val all: List<String>, val hinted: List<String>)

    private fun services(): Services = try {
        val sm = Class.forName("android.os.ServiceManager")
        @Suppress("UNCHECKED_CAST")
        val all = (sm.getMethod("listServices").invoke(null) as Array<String>).sorted()
        Services(all, all.filter(::looksLikeRebootHook))
    } catch (e: Throwable) {
        val why = listOf("lista de serviços indisponível (${e.javaClass.simpleName})")
        Services(why, emptyList())
    }
}
