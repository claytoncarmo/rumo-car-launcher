package app.rumo.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import androidx.core.content.pm.PackageInfoCompat
import app.rumo.core.WebStation
import app.rumo.core.WebStatus
import app.rumo.shared.WebRadioProtocol as P

/** O que o player do rádio online está fazendo: estação (id do catálogo), estado e o "tocando agora" do stream. */
data class WebPlayback(val stationId: String?, val status: WebStatus, val title: String?)

/**
 * Controle do player do rádio online, que mora no app à parte `app.rumo.webradio` ([P]): quando outra fonte pega o
 * som, o firmware encerra aquele app e não o Rumo. Thread principal. Liga no serviço na primeira vez que precisa;
 * comandos pedidos antes de ligar esperam a ligação. [onChange] recebe cada mudança.
 */
class WebRadioPlayer(context: Context, private val onChange: (WebPlayback) -> Unit) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var remote: IBinder? = null
    private var connection: ServiceConnection? = null
    /** A ligação atual cria o serviço ([connect] com create); a sem create só espera um serviço que já rode. */
    private var creates = false
    private val pending = ArrayList<(IBinder) -> Unit>()
    private var last = WebPlayback(null, WebStatus.OFF, null)

    /** Recebe os avisos do player; só aceita o app do rádio online (mesma assinatura do Rumo). */
    private val events = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != P.TX_EVENT && code != P.TX_LOG) return super.onTransact(code, data, reply, flags)
            if (ctx.packageManager.checkSignatures(Binder.getCallingUid(), Process.myUid()) != PackageManager.SIGNATURE_MATCH) {
                throw SecurityException("aviso de um app desconhecido")
            }
            data.enforceInterface(P.EVENTS_DESCRIPTOR)
            if (code == P.TX_LOG) {
                val line = data.readString().orEmpty()
                main.post { onLog(line) }
                return true
            }
            val id = data.readString().orEmpty().ifEmpty { null }
            val status = when (data.readString()) {
                P.STATUS_LIVE -> WebStatus.LIVE
                P.STATUS_CONNECTING -> WebStatus.CONNECTING
                P.STATUS_ERROR -> WebStatus.ERROR
                else -> WebStatus.OFF
            }
            val title = data.readString().orEmpty().ifEmpty { null }
            main.post { emit(WebPlayback(id, status, title)) }
            return true
        }
    }

    /** Tocando ou tentando tocar. */
    val active: Boolean
        get() = last.status == WebStatus.CONNECTING || last.status == WebStatus.LIVE

    /** Ligado no serviço agora (os avisos de radar podem sair por ele). */
    val connected: Boolean get() = remote != null

    /** Com o Rumo na tela: fica ligado no app do online e liga de novo se o firmware o encerrar. */
    private var keep = false

    /** Linha do registro dos avisos de radar tocados pelo app do online. */
    var onLog: (String) -> Unit = {}

    /** Chamado a cada vez que a ligação abre (o Rumo toca o aviso que esperava por ela). */
    var onConnected: () -> Unit = {}

    /** O app do rádio online está instalado (então todo som do Rumo deve sair por ele). */
    val installed: Boolean get() = installedVersion(ctx) != null

    /** Liga de novo já, se a ligação caiu e o Rumo está na tela (um aviso espera por ela). */
    fun reconnectNow() {
        if (keep && remote == null) {
            if (connection != null && !creates) unbind()
            if (connection == null && installed) connect()
        }
    }

    /**
     * Com o Rumo na tela, fica ligado no app do online (se instalado), tocando ou não: todo som do Rumo sai por ele
     * ([chime], [alarm]). Um som tocado pelo Rumo o faz virar a fonte, e o firmware o encerra na troca seguinte.
     */
    fun attach() {
        keep = true
        if (installedVersion(ctx) != null) connect()
    }

    /** Fora da tela: solta a ligação (se estiver tocando, o serviço continua sozinho, em primeiro plano). */
    fun detach() {
        keep = false
        release()
    }

    private fun reconnectLater() {
        main.postDelayed({ if (keep && connection == null && installedVersion(ctx) != null) connect() }, RECONNECT_MS)
    }

    /** Liga no serviço; [create] falso só liga se ele já estiver rodando (tela recriada com o rádio tocando). */
    fun connect(create: Boolean = true) {
        // Ligação só "se já estiver rodando" e sem resposta: para tocar, refaz criando o serviço
        if (connection != null && create && !creates && remote == null) unbind()
        if (connection != null) return
        if (create && installedVersion(ctx) == null) {
            pending.clear()
            emit(WebPlayback(null, WebStatus.ERROR, null))
            return
        }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                remote = service
                send(service, P.TX_LISTEN) { writeStrongBinder(events) }
                pending.forEach { it(service) }
                pending.clear()
                onConnected()
            }

            // O app do rádio online foi encerrado (troca de fonte, por exemplo): parado
            override fun onServiceDisconnected(name: ComponentName) {
                remote = null
                emit(WebPlayback(last.stationId, WebStatus.OFF, null))
            }

            // Encerrado à força (o firmware, na troca de fonte): a ligação não volta sozinha
            override fun onBindingDied(name: ComponentName) {
                remote = null
                unbind()
                emit(WebPlayback(last.stationId, WebStatus.OFF, null))
                reconnectLater()
            }
        }
        val intent = Intent().setComponent(ComponentName(P.PACKAGE, P.SERVICE))
        val ok = try {
            ctx.bindService(intent, conn, if (create) Context.BIND_AUTO_CREATE else 0)
        } catch (e: Exception) {
            false
        }
        if (ok) {
            connection = conn
            creates = create
        } else {
            try {
                ctx.unbindService(conn)
            } catch (e: Exception) {
            }
            pending.clear()
            if (create) emit(WebPlayback(null, WebStatus.ERROR, null))
        }
    }

    fun release() {
        unbind()
        pending.clear()
    }

    fun play(s: WebStation) = withRemote { b ->
        send(b, P.TX_PLAY) {
            writeString(s.id)
            writeString(s.url)
            writeString(s.name)
            writeInt(if (s.hls) 1 else 0)
        }
    }

    /** Aviso de radar pelo app do online (falso se não estiver ligado: o Rumo toca ele mesmo). */
    fun chime(): Boolean = remote?.let { send(it, P.TX_CHIME) {}; true } ?: false

    /** Bipes pelo app do online: proximidade 0 a 1, null para parar. */
    fun alarm(k: Float?): Boolean = remote?.let { send(it, P.TX_ALARM) { writeFloat(k ?: Float.NaN) }; true } ?: false

    fun stop() {
        remote?.let { send(it, P.TX_STOP) {} }
        pending.clear()
    }

    /** Para e solta a ligação: parado e sem ninguém ligado, o serviço se encerra e libera o player e o foco de áudio. */
    fun shutdown() {
        stop()
        release()
    }

    private fun unbind() {
        connection?.let {
            try {
                ctx.unbindService(it)
            } catch (e: Exception) {
            }
        }
        connection = null
        remote = null
    }

    private fun withRemote(block: (IBinder) -> Unit) {
        val b = remote
        if (b != null) block(b) else {
            pending += block
            connect()
        }
    }

    private fun send(b: IBinder, code: Int, write: Parcel.() -> Unit) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(P.DESCRIPTOR)
            data.write()
            b.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: Exception) {
            // Morreu no meio: o onServiceDisconnected avisa
        } finally {
            data.recycle()
        }
    }

    private fun emit(p: WebPlayback) {
        last = p
        onChange(p)
    }

    companion object {
        private const val RECONNECT_MS = 300L

        /** versionCode do app do rádio online instalado, ou null se não estiver. */
        fun installedVersion(context: Context): Long? = try {
            PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(P.PACKAGE, 0))
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
