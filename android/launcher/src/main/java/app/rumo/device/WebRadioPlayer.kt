package app.rumo.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.rumo.core.WebStation
import app.rumo.core.WebStatus

/** O que o player do rádio online está fazendo: estação (id do catálogo), estado e o "tocando agora" do stream. */
data class WebPlayback(val stationId: String?, val status: WebStatus, val title: String?)

/**
 * Controle do [WebRadioService] pela tela do Rumo (thread principal; o player também vive nela). Liga no serviço na
 * primeira vez que precisa; comandos pedidos antes de ligar esperam a ligação. [onChange] recebe cada mudança.
 */
class WebRadioPlayer(context: Context, private val onChange: (WebPlayback) -> Unit) {
    private val ctx = context.applicationContext
    private var player: ExoPlayer? = null
    private var connection: ServiceConnection? = null
    /** A ligação atual cria o serviço ([connect] com create); a sem create só espera um serviço que já rode. */
    private var creates = false
    private val pending = ArrayList<(ExoPlayer) -> Unit>()

    private val listener = object : Player.Listener {
        override fun onEvents(p: Player, events: Player.Events) = publish()
    }

    /** Tocando ou tentando tocar (o motorista pediu e nada o parou). */
    val active: Boolean
        get() = player?.let { it.playWhenReady && it.mediaItemCount > 0 && it.playerError == null } == true

    /** Liga no serviço; [create] falso só liga se ele já estiver rodando (tela recriada com o rádio tocando). */
    fun connect(create: Boolean = true) {
        // Ligação só "se já estiver rodando" e sem player: para tocar, refaz criando o serviço (senão o pedido esperava
        // um serviço que ninguém iniciava, e a primeira estação não tocava)
        if (connection != null && create && !creates && player == null) {
            connection?.let {
                try {
                    ctx.unbindService(it)
                } catch (e: Exception) {
                }
            }
            connection = null
        }
        if (connection != null) return
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val p = (service as WebRadioService.Local).service.player
                player = p
                p.addListener(listener)
                pending.forEach { it(p) }
                pending.clear()
                publish()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                player = null
                onChange(WebPlayback(null, WebStatus.OFF, null))
            }
        }
        val ok = try {
            ctx.bindService(Intent(ctx, WebRadioService::class.java), conn, if (create) Context.BIND_AUTO_CREATE else 0)
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
        }
    }

    fun release() {
        player?.removeListener(listener)
        player = null
        connection?.let {
            try {
                ctx.unbindService(it)
            } catch (e: Exception) {
            }
        }
        connection = null
        pending.clear()
    }

    fun play(s: WebStation) = withPlayer { p ->
        val item = MediaItem.Builder()
            .setMediaId(s.id)
            .setUri(Uri.parse(s.url))
            .apply { if (s.hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            // O nome da estação vai como artista: o título fica para o "tocando agora" que o stream manda (ICY)
            .setMediaMetadata(MediaMetadata.Builder().setArtist(s.name).build())
            .build()
        p.setMediaItem(item)
        p.prepare()
        p.play()
    }

    fun stop() {
        player?.run {
            stop()
            clearMediaItems()
        }
        pending.clear()
    }

    /** Para e solta a ligação: parado e sem ninguém ligado, o serviço se encerra e libera o player e o foco de áudio. */
    fun shutdown() {
        stop()
        release()
    }

    private fun withPlayer(block: (ExoPlayer) -> Unit) {
        val p = player
        if (p != null) block(p) else {
            pending += block
            connect()
        }
    }

    private fun publish() {
        val p = player ?: return
        val status = when {
            p.mediaItemCount == 0 -> WebStatus.OFF
            p.playerError != null -> WebStatus.ERROR
            // Pausado: o motorista parou ou outro app (Android Auto) tomou o som
            !p.playWhenReady -> WebStatus.OFF
            // Pronto: tocando (ou calado um instante por outro som curto, como a voz do navegador)
            p.playbackState == Player.STATE_READY -> WebStatus.LIVE
            p.playbackState == Player.STATE_ENDED -> WebStatus.ERROR
            p.playbackState == Player.STATE_IDLE -> WebStatus.OFF
            else -> WebStatus.CONNECTING
        }
        val title = p.mediaMetadata.title?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        onChange(WebPlayback(p.currentMediaItem?.mediaId, status, title))
    }
}
