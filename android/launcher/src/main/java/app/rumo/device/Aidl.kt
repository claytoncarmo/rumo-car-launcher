package app.rumo.device

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Conversa com os serviços do fabricante (sourceinfo, carstatus, mcu_service) em binder puro, decodificando
 * argumentos e respostas pelas próprias interfaces AIDL que existem no sistema (lidas por reflexão): o número de
 * cada chamada vem das constantes TRANSACTION_* do Stub e os tipos vêm da assinatura do método.
 */
object Aidl {

    fun service(name: String): IBinder? = try {
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, name) as IBinder?
    } catch (e: Throwable) {
        null
    }

    /**
     * Classe pelo nome: do sistema ou, com [loader], de outro APK (ex.: a interface do app de rádio, que só existe
     * dentro dele).
     */
    fun cls(name: String, loader: ClassLoader? = null): Class<*> =
        Class.forName(name, false, loader ?: Aidl::class.java.classLoader)

    /** Método da interface [iface] que corresponde à transação [code], pelo TRANSACTION_nome do Stub. */
    fun methodFor(iface: String, code: Int, loader: ClassLoader? = null): Method? = try {
        val stub = cls("$iface\$Stub", loader)
        val name = stub.declaredFields.firstOrNull { f ->
            f.name.startsWith("TRANSACTION_") && Modifier.isStatic(f.modifiers) &&
                f.let { it.isAccessible = true; it.getInt(null) } == code
        }?.name?.removePrefix("TRANSACTION_")
        name?.let { n -> cls(iface, loader).methods.firstOrNull { it.name == n } }
    } catch (e: Throwable) {
        null
    }

    fun code(iface: String, method: String, loader: ClassLoader? = null): Int? = try {
        cls("$iface\$Stub", loader).getDeclaredField("TRANSACTION_$method").let { it.isAccessible = true; it.getInt(null) }
    } catch (e: Throwable) {
        null
    }

    /** Chama um método sem argumentos e devolve a resposta decodificada (ou o erro, como texto). */
    fun call(binder: IBinder, iface: String, method: String, loader: ClassLoader? = null): Any? {
        val code = code(iface, method, loader) ?: return "sem TRANSACTION_$method"
        val returnType = try {
            cls(iface, loader).methods.first { it.name == method }.returnType
        } catch (e: Throwable) {
            return "sem o método $method"
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(iface)
            binder.transact(code, data, reply, 0)
            reply.readException()
            if (returnType == Void.TYPE) null else read(reply, returnType, loader)
        } catch (e: Throwable) {
            "erro: ${e.javaClass.simpleName} ${e.message ?: ""}"
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Chama um método que recebe só um binder (ex.: registrar ou remover um ouvinte). */
    fun callWithBinder(binder: IBinder, iface: String, method: String, arg: IBinder, loader: ClassLoader? = null): String {
        val code = code(iface, method, loader) ?: return "sem TRANSACTION_$method"
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(iface)
            data.writeStrongBinder(arg)
            binder.transact(code, data, reply, 0)
            reply.readException()
            "$method ok"
        } catch (e: Throwable) {
            "$method recusado (${e.javaClass.simpleName} ${e.message ?: ""})"
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Lê um valor do tipo [type] como o AIDL escreve. */
    @Suppress("DEPRECATION") // readArrayList(ClassLoader): o aparelho é Android 9
    fun read(p: Parcel, type: Class<*>, loader: ClassLoader? = null): Any? = when (type) {
        Int::class.javaPrimitiveType, Int::class.javaObjectType -> p.readInt()
        Long::class.javaPrimitiveType -> p.readLong()
        Boolean::class.javaPrimitiveType -> p.readInt() != 0
        Float::class.javaPrimitiveType -> p.readFloat()
        Double::class.javaPrimitiveType -> p.readDouble()
        Byte::class.javaPrimitiveType -> p.readByte()
        String::class.java, CharSequence::class.java -> p.readString()
        ByteArray::class.java -> p.createByteArray()
        IntArray::class.java -> p.createIntArray()
        Array<String>::class.java -> p.createStringArray()
        IBinder::class.java -> p.readStrongBinder()
        List::class.java, java.util.ArrayList::class.java -> p.readArrayList(loader ?: Aidl::class.java.classLoader)
        else -> when {
            Parcelable::class.java.isAssignableFrom(type) -> {
                // AIDL: 1 = há objeto, 0 = null; depois o próprio objeto pelo CREATOR
                if (p.readInt() == 0) {
                    null
                } else {
                    val creator = type.getField("CREATOR").get(null) as Parcelable.Creator<*>
                    creator.createFromParcel(p)
                }
            }
            IBinder::class.java.isAssignableFrom(type) || type.isInterface -> p.readStrongBinder()
            else -> "(tipo ${type.simpleName} não lido)"
        }
    }

    /** Texto de um valor: objetos viram Classe{campo=valor, …} com os campos declarados (sem os estáticos). */
    fun dump(v: Any?, depth: Int = 0): String = when (v) {
        null -> "null"
        is String -> "\"$v\""
        is Number, is Boolean, is Char -> v.toString()
        is ByteArray -> v.joinToString(" ", "[", "]") { "%02X".format(it) }
        is IntArray -> v.joinToString(prefix = "[", postfix = "]")
        is Array<*> -> v.joinToString(prefix = "[", postfix = "]") { dump(it, depth + 1) }
        is Collection<*> -> v.joinToString(prefix = "[", postfix = "]") { dump(it, depth + 1) }
        is IBinder -> "binder"
        else -> if (depth > 2) {
            v.toString()
        } else {
            val fields = generateSequence<Class<*>>(v.javaClass) { it.superclass }
                .takeWhile { it != Any::class.java }
                .flatMap { it.declaredFields.asSequence() }
                .filter { !Modifier.isStatic(it.modifiers) }
                .joinToString(", ") { f ->
                    val value = try {
                        f.isAccessible = true
                        dump(f.get(v), depth + 1)
                    } catch (e: Throwable) {
                        "?"
                    }
                    "${f.name}=$value"
                }
            "${v.javaClass.simpleName}{$fields}"
        }
    }
}

/**
 * Ouvinte genérico para um serviço que avisa por callback (ex.: ISourceCallback do sourceinfo): recebe as
 * transações, acha o método pelo número e decodifica os argumentos pela assinatura. [onEvent] recebe o nome do
 * método e os argumentos (numa thread do binder).
 */
class AidlTap(
    private val iface: String,
    private val loader: ClassLoader? = null,
    private val onEvent: (String, List<Any?>) -> Unit,
) : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(iface)
            return true
        }
        val method = Aidl.methodFor(iface, code, loader)
        try {
            data.enforceInterface(iface)
            if (method == null) {
                onEvent("transação $code", listOf("${data.dataAvail()} bytes"))
            } else {
                onEvent(method.name, method.parameterTypes.map { Aidl.read(data, it, loader) })
            }
        } catch (e: Throwable) {
            onEvent(method?.name ?: "transação $code", listOf("erro ao ler (${e.javaClass.simpleName})"))
        }
        reply?.writeNoException()
        return true
    }

    companion object {
        fun describe(method: String, args: List<Any?>) = "$method(${args.joinToString { Aidl.dump(it) }})"
    }
}

/**
 * Fonte de áudio pelo serviço "sourceinfo" do multimídia (HCN): avisa onSourceChanged(pacote) e onMusicEvent(MusicInfo)
 * quando a fonte muda, e getMusicInfo() diz quem toca agora (MusicInfo.className: "com.zjinnova.zlink" no Android
 * Auto, ".RadioMain" no rádio). Não informa a estação nem a música.
 */
class SourceInfo(private val onOwner: (String) -> Unit) {
    private var binder: IBinder? = null
    private val tap = AidlTap(CALLBACK) { method, args ->
        when (method) {
            "onSourceChanged" -> (args.firstOrNull() as? String)?.let(onOwner)
            "onMusicEvent" -> args.firstOrNull()?.let(::classNameOf)?.let(onOwner)
            else -> {}
        }
    }

    /** Registra o aviso e devolve quem toca agora; null sem o serviço. Chamada ao sistema: fora da thread principal. */
    fun start(): String? {
        val b = Aidl.service(SERVICE) ?: return null
        binder = b
        transactCallback(b, "setcallback")
        return classNameOf(Aidl.call(b, IFACE, "getMusicInfo")) ?: ""
    }

    fun stop() {
        binder?.let { transactCallback(it, "removecallback") }
        binder = null
    }

    private fun transactCallback(b: IBinder, method: String): Boolean {
        val code = Aidl.code(IFACE, method) ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE)
            data.writeStrongBinder(tap)
            b.transact(code, data, reply, 0)
            reply.readException()
            true
        } catch (e: Throwable) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    companion object {
        const val SERVICE = "sourceinfo"
        const val IFACE = "android.sourceservice.ISourceService"
        const val CALLBACK = "android.sourceservice.ISourceCallback"

        private fun classNameOf(info: Any?): String? = try {
            info?.javaClass?.getDeclaredField("className")?.let { it.isAccessible = true; it.get(info) as? String }
        } catch (e: Throwable) {
            null
        }
    }
}
