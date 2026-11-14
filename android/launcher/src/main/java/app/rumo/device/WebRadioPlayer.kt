package app.rumo.device

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.rumo.core.WebStation
import app.rumo.core.WebStatus
import com.google.common.util.concurrent.ListenableFuture

/** O que o player do rádio online está fazendo: estação (id do catálogo), estado e o "tocando agora" do stream. */
data class WebPlayback(val stationId: String?, val status: WebStatus, val title: String?)

/**
 * Controle do [WebRadioService] pela tela do Rumo (thread principal). Liga no serviço na primeira vez que precisa;
 * comandos pedidos antes de ligar esperam a ligação. [onChange] recebe cada mudança do player.
 */
class WebRadioPlayer(context: Context, private val onChange: (WebPlayback) -> Unit) {
    private val ctx = context.applicationContext
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    /** Tocando ou tentando tocar (o motorista pediu e nada o parou). */
    val active: Boolean
        get() = controller?.let { it.playWhenReady && it.mediaItemCount > 0 && it.playerError == null } == true

    fun connect() {
        if (future != null) return
        val f = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, WebRadioService::class.java))).buildAsync()
        future = f
        f.addListener({
            val c = try {
                f.get()
            } catch (e: Exception) {
                null
            }
            if (c == null) {
                future = null
                pending.clear()
                onChange(WebPlayback(null, WebStatus.ERROR, null))
                return@addListener
            }
            controller = c
            c.addListener(listener)
            pending.forEach { it(c) }
            pending.clear()
            publish()
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun release() {
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        pending.clear()
    }

    fun play(s: WebStation) = withController { c ->
        val item = MediaItem.Builder()
            .setMediaId(s.id)
            .setUri(Uri.parse(s.url))
            .apply { if (s.hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            // O nome da estação vai como artista: o título fica para o "tocando agora" que o stream manda (ICY)
            .setMediaMetadata(MediaMetadata.Builder().setArtist(s.name).build())
            .build()
        c.setMediaItem(item)
        c.prepare()
        c.play()
    }

    fun stop() {
        controller?.run {
            stop()
            clearMediaItems()
        }
        pending.clear()
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else {
            pending += block
            connect()
        }
    }

    private fun publish() {
        val c = controller ?: return
        val status = when {
            c.mediaItemCount == 0 -> WebStatus.OFF
            c.playerError != null -> WebStatus.ERROR
            // Pausado: o motorista parou ou outro app (Android Auto) tomou o som
            !c.playWhenReady -> WebStatus.OFF
            // Pronto: tocando (ou calado um instante por outro som curto, como a voz do navegador)
            c.playbackState == Player.STATE_READY -> WebStatus.LIVE
            c.playbackState == Player.STATE_ENDED -> WebStatus.ERROR
            c.playbackState == Player.STATE_IDLE -> WebStatus.OFF
            else -> WebStatus.CONNECTING
        }
        val title = c.mediaMetadata.title?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        onChange(WebPlayback(c.currentMediaItem?.mediaId, status, title))
    }
}
