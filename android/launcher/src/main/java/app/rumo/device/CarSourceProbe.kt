package app.rumo.device

import android.content.Context
import dalvik.system.PathClassLoader
import java.lang.reflect.Modifier

/**
 * Teste do farol, parte do fabricante (HCN): o sistema tem um pacote próprio, android.carsource, com um serviço da
 * MCU (McuService, com algo chamado BACKLIGHT). Aqui o Rumo abre essas classes por reflexão e lista constantes e
 * métodos, procura serviços do sistema ligados ao carro e lê as propriedades de luz uma a uma.
 */
object CarSourceProbe {

    private val SERVICE_HINT = Regex("(?i)(car|mcu|hcn|source|light|ill|can|radio|fm)")
    // A lista completa vai para o arquivo do relatório, não para a tela
    private const val MAX_METHODS = 300

    /** Constantes e métodos de cada classe achada. Chamar fora da thread principal. */
    fun describe(ctx: Context, classes: Map<String, String>): List<String> {
        val out = mutableListOf<String>()
        val loaders = HashMap<String, ClassLoader>()
        for ((name, apk) in classes.entries.take(40)) {
            val (cls, from) = try {
                Class.forName(name, false, ctx.classLoader) to "sistema"
            } catch (e: Throwable) {
                try {
                    if (apk.isEmpty()) throw e
                    val loader = loaders.getOrPut(apk) { PathClassLoader(apk, ctx.classLoader.parent) }
                    Class.forName(name, false, loader) to apk.substringAfterLast('/')
                } catch (e2: Throwable) {
                    out += "$name: não carregou (${e2.javaClass.simpleName})"
                    continue
                }
            }
            out += "■ $name ($from)"
            for (f in cls.declaredFields) {
                if (!Modifier.isStatic(f.modifiers)) continue
                val value = try {
                    f.isAccessible = true
                    f.get(null)?.toString() ?: "null"
                } catch (e: Throwable) {
                    "?"
                }
                out += "   ${f.name} = $value"
            }
            for (m in cls.declaredMethods.take(MAX_METHODS)) {
                val params = m.parameterTypes.joinToString { it.simpleName }
                out += "   ${m.name}($params): ${m.returnType.simpleName}"
            }
        }
        return out
    }

    /** Serviços do sistema (ServiceManager) com nome de carro, MCU, luz… */
    fun services(): List<String> = try {
        val sm = Class.forName("android.os.ServiceManager")
        @Suppress("UNCHECKED_CAST")
        val all = sm.getMethod("listServices").invoke(null) as Array<String>
        all.filter { SERVICE_HINT.containsMatchIn(it) }.sorted()
    } catch (e: Throwable) {
        listOf("lista de serviços indisponível (${e.javaClass.simpleName})")
    }

    private val NOT_CAR = Regex("^(autofill|media|wifi)")

    /**
     * Para cada serviço do carro (carstatus, car_settings, sourceinfo, mcu_service…): o nome da interface que ele
     * informa e, quando a classe existe no sistema, as constantes e os métodos dela e do Stub (números de transação).
     */
    fun serviceInterfaces(ctx: Context, services: List<String>): List<String> {
        val out = mutableListOf<String>()
        val classes = LinkedHashMap<String, String>()
        for (name in services.filterNot { NOT_CAR.containsMatchIn(it) }) {
            val descriptor = try {
                val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                    .invoke(null, name) as android.os.IBinder?
                binder?.interfaceDescriptor
            } catch (e: Throwable) {
                "erro: ${e.javaClass.simpleName}"
            }
            out += "$name → ${descriptor ?: "(sem interface)"}"
            if (descriptor != null && '.' in descriptor && !descriptor.startsWith("erro")) {
                classes[descriptor] = ""
                classes["$descriptor\$Stub"] = ""
            }
        }
        return out + describe(ctx, classes)
    }

    /** Valor de cada propriedade, pedida uma a uma (algumas não aparecem no getprop completo). */
    fun props(names: List<String>): Map<String, String> = names.associateWith { name ->
        try {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", name))
            p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }.ifEmpty { "(vazio)" }
        } catch (e: Exception) {
            "(erro)"
        }
    }
}
