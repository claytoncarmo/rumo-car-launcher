package app.rumo.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import dalvik.system.PathClassLoader
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Teste do rádio, segunda parte: o app de rádio do aparelho (com.hcn.autoradio) tem um serviço para outros apps
 * (FMPlugService, ação com.hcn.radio.FM_PLUG_SERVICE) com a interface com.hcn.autoradio.IRadioServiceAPI e um
 * ouvinte IRadioCallBack. As classes só existem dentro do APK do rádio: são carregadas dele para decodificar.
 * Só chama funções de leitura (get…/is…/has…): nada que troque estação ou grave algo.
 */
class RadioProbe(private val ctx: Context) {

    private val loader: ClassLoader? = try {
        val apk = ctx.packageManager.getApplicationInfo(PKG, 0).sourceDir
        PathClassLoader(apk, ctx.classLoader.parent)
    } catch (e: Exception) {
        null
    }
    private var connection: ServiceConnection? = null
    private var binder: IBinder? = null
    private var iface: String? = null
    private var tap: AidlTap? = null
    private var unregister: String? = null

    /** Classes do rádio: as do APK e as do sistema (android.radio.*), com constantes e métodos. */
    fun describe(): List<String> {
        val names = LinkedHashMap<String, String>()
        val apk = try {
            ctx.packageManager.getApplicationInfo(PKG, 0).sourceDir
        } catch (e: Exception) {
            ""
        }
        for (c in listOf(API, "$API\$Stub", CALLBACK, "$CALLBACK\$Stub")) names[c] = apk
        for (c in listOf("android.radio.IRadioPlayer", "android.radio.IRadioPlayer\$Stub", "android.radio.IRadioCallback")) names[c] = ""
        // Tipos usados nos métodos e avisos (ex.: um objeto com a estação)
        for (c in listOf(API, CALLBACK)) {
            try {
                Aidl.cls(c, loader).methods.flatMap { it.parameterTypes.toList() + it.returnType }
                    .filter { !it.isPrimitive && it.name.startsWith("com.hcn") }
                    .forEach { names[it.name] = apk }
            } catch (e: Throwable) {
            }
        }
        return CarSourceProbe.describe(ctx, names)
    }

    fun permissions(): String = listOf("hcn.permission.FM_DATA_SERVICE", "hcn.permission.FM_PLUG_SERVICE").joinToString { p ->
        val ok = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        "$p: ${if (ok) "concedida" else "negada"}"
    }

    /** Liga ao serviço do rádio; devolve um resumo. Chamar na thread principal. */
    suspend fun bind(): String {
        val intents = listOf(
            Intent("com.hcn.radio.FM_PLUG_SERVICE").setPackage(PKG),
            Intent("com.hcn.autoradio.FM_PLUG_SERVICE").setPackage(PKG),
            Intent().setComponent(ComponentName(PKG, "$PKG.service.FMPlugService")),
        )
        val tried = mutableListOf<String>()
        for (intent in intents) {
            val label = intent.action ?: intent.component?.shortClassName ?: "?"
            val b = withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine<IBinder?> { cont ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, service: IBinder) {
                            if (cont.isActive) cont.resume(service)
                        }

                        override fun onServiceDisconnected(name: ComponentName) {}
                    }
                    val ok = try {
                        ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE)
                    } catch (e: SecurityException) {
                        tried += "$label: sem permissão (${e.message})"
                        false
                    }
                    if (ok) {
                        connection = conn
                    } else {
                        tried += "$label: não ligou"
                        cont.resume(null)
                    }
                }
            }
            if (b != null) {
                binder = b
                iface = try {
                    b.interfaceDescriptor
                } catch (e: Exception) {
                    null
                }
                return "ligado por $label · interface ${iface ?: "?"}"
            }
            connection?.let { ctx.unbindService(it) }
            connection = null
            if (tried.none { it.startsWith(label) }) tried += "$label: sem resposta em 5 s"
        }
        return "não ligou: ${tried.joinToString(" · ")}"
    }

    /** Resultado de todas as funções de leitura sem argumentos. Chamada ao app de rádio: fora da thread principal. */
    fun read(): List<String> {
        val b = binder ?: return emptyList()
        val name = iface ?: return emptyList()
        val methods = try {
            Aidl.cls(name, loader).methods
        } catch (e: Throwable) {
            return listOf("interface $name não carregou: ${e.javaClass.simpleName}")
        }
        return methods
            .filter { it.parameterTypes.isEmpty() && it.returnType != Void.TYPE && READ.containsMatchIn(it.name) }
            .sortedBy { it.name }
            .map { m -> "${m.name}() = ${Aidl.dump(Aidl.call(b, name, m.name, loader))}" }
    }

    /** Inscreve um ouvinte em todo método que recebe só o IRadioCallBack (register…/setCallback…). */
    fun listen(onEvent: (String) -> Unit): String {
        val b = binder ?: return "sem serviço"
        val name = iface ?: return "sem interface"
        val methods = try {
            Aidl.cls(name, loader).methods
        } catch (e: Throwable) {
            return "interface não carregou"
        }
        val cbClass = try {
            Aidl.cls(CALLBACK, loader)
        } catch (e: Throwable) {
            null
        }
        val reg = methods.firstOrNull { m ->
            m.parameterTypes.size == 1 && m.parameterTypes[0] == cbClass && !m.name.lowercase().startsWith("un") &&
                !m.name.lowercase().contains("remove")
        } ?: return "nenhum método para registrar o ouvinte"
        unregister = methods.firstOrNull { m ->
            m.parameterTypes.size == 1 && m.parameterTypes[0] == cbClass && m !== reg
        }?.name
        val t = AidlTap(CALLBACK, loader) { method, args -> onEvent(AidlTap.describe(method, args)) }
        tap = t
        return Aidl.callWithBinder(b, name, reg.name, t, loader)
    }

    private var fm: IBinder? = null
    private var fmTap: AidlTap? = null

    /**
     * Serviço de rádio do sistema ("fmradio", android.radio.IRadioPlayer): getRadioInfo() pode trazer o nome da
     * estação (RDS). Só leituras.
     */
    fun systemRead(): List<String> {
        val b = fm ?: Aidl.service(FM_SERVICE)?.also { fm = it } ?: return listOf("$FM_SERVICE indisponível")
        return listOf("getFreq", "getUiband", "getRegion", "getRadioInfo").map { m ->
            "$FM_SERVICE.$m() = ${Aidl.dump(Aidl.call(b, FM_IFACE, m))}"
        }
    }

    /** Ouvinte do fmradio (onEvent(int, RadioInfo), onStringEvent(int, String): talvez o texto do RDS). */
    fun systemListen(onEvent: (String) -> Unit): String {
        val b = fm ?: Aidl.service(FM_SERVICE)?.also { fm = it } ?: return "$FM_SERVICE indisponível"
        val t = AidlTap(FM_CALLBACK) { method, args -> onEvent("$FM_SERVICE · ${AidlTap.describe(method, args)}") }
        fmTap = t
        return Aidl.callWithBinder(b, FM_IFACE, "setcallback", t)
    }

    fun stop() {
        val b = binder
        val t = tap
        val name = iface
        if (b != null && t != null && name != null) unregister?.let { Aidl.callWithBinder(b, name, it, t, loader) }
        val f = fm
        val ft = fmTap
        if (f != null && ft != null) Aidl.callWithBinder(f, FM_IFACE, "removecallback", ft)
        fmTap = null
        connection?.let {
            try {
                ctx.unbindService(it)
            } catch (e: Exception) {
            }
        }
        connection = null
        binder = null
        tap = null
    }

    companion object {
        const val PKG = "com.hcn.autoradio"
        const val API = "com.hcn.autoradio.IRadioServiceAPI"
        const val CALLBACK = "com.hcn.autoradio.IRadioCallBack"
        const val FM_SERVICE = "fmradio"
        const val FM_IFACE = "android.radio.IRadioPlayer"
        const val FM_CALLBACK = "android.radio.IRadioCallback"
        private val READ = Regex("^(get|is|has|query|read|current)", RegexOption.IGNORE_CASE)
    }
}
