package app.rumo.device

import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/**
 * Ouvinte do serviço da MCU do fabricante (HCN, "mcu_service", interface android.carsource.IMcuManager). O próprio
 * sistema avisa os apps por ele: setcallback(ouvinte, grupo) registra; a MCU chama onEvent(int, int) e
 * onByteEvent(int, byte[]). Feito em binder puro (sem as classes escondidas do fabricante), com os números de
 * transação lidos no aparelho: setcallback = 1, removecallback = 2; no ouvinte, onEvent = 1, onByteEvent = 2.
 */
class McuTap(private val onEvent: (String) -> Unit) : Binder() {

    private var service: IBinder? = null
    private val registered = mutableListOf<Int>()

    /** Registra o ouvinte em cada grupo; devolve um resumo (quantos aceitaram, erros). */
    fun attach(groups: List<Int>): String {
        val s = try {
            Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, SERVICE) as IBinder?
        } catch (e: Throwable) {
            null
        } ?: return "serviço $SERVICE não encontrado"
        service = s
        var errors = 0
        var lastError = ""
        for (g in groups) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(MANAGER)
                data.writeStrongBinder(this)
                data.writeInt(g)
                s.transact(TX_SETCALLBACK, data, reply, 0)
                reply.readException()
                registered += g
            } catch (e: Throwable) {
                errors++
                lastError = "${e.javaClass.simpleName} ${e.message ?: ""}"
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
        return "ouvindo ${registered.size} de ${groups.size} grupos" + if (errors > 0) " · $errors recusados ($lastError)" else ""
    }

    fun detach() {
        val s = service ?: return
        for (g in registered) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(MANAGER)
                data.writeStrongBinder(this)
                data.writeInt(g)
                s.transact(TX_REMOVECALLBACK, data, reply, 0)
            } catch (e: Throwable) {
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
        registered.clear()
        service = null
    }

    // Chega numa thread do binder
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(LISTENER)
            return true
        }
        try {
            data.enforceInterface(LISTENER)
            when (code) {
                TX_ON_EVENT -> {
                    val a = data.readInt()
                    val b = data.readInt()
                    onEvent("onEvent($a, $b)")
                }
                TX_ON_BYTE_EVENT -> {
                    val a = data.readInt()
                    val bytes = data.createByteArray()
                    onEvent("onByteEvent($a, ${bytes?.joinToString(" ") { "%02X".format(it) } ?: "null"})")
                }
                else -> onEvent("transação $code desconhecida")
            }
        } catch (e: Throwable) {
            onEvent("erro ao ler evento $code: ${e.javaClass.simpleName}")
        }
        reply?.writeNoException()
        return true
    }

    companion object {
        const val SERVICE = "mcu_service"
        const val MANAGER = "android.carsource.IMcuManager"
        const val LISTENER = "android.carsource.IMcuListener"
        const val TX_SETCALLBACK = 1
        const val TX_REMOVECALLBACK = 2
        const val TX_ON_EVENT = 1
        const val TX_ON_BYTE_EVENT = 2

        /**
         * Grupos GID_* de android.carsource.McuConstant (são byte: GID_SYS_STATUS = -112 é 0x90); sem a classe,
         * os números vistos no aparelho.
         */
        fun groups(): List<Int> {
            val found = try {
                Class.forName("android.carsource.McuConstant").declaredFields
                    .filter { it.name.startsWith("GID_") }
                    .mapNotNull { f ->
                        f.isAccessible = true
                        (f.get(null) as? Number)?.toInt()
                    }
                    .distinct()
            } catch (e: Throwable) {
                emptyList()
            }
            return found.ifEmpty { (1..60).toList() + listOf(96, -112) }
        }
    }
}
