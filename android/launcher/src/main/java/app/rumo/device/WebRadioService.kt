package app.rumo.device

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Rádio online: o player fica num serviço de mídia para o som continuar quando o motorista abre outro app (como o FM,
 * que toca fora do Rumo). Enquanto toca, o Android mostra a notificação de mídia (exigência do serviço em primeiro
 * plano). O Rumo fala com ele por um MediaController ([WebRadioPlayer]).
 *
 * Foco de áudio pelo player: quando o Android Auto (ou outro app) pede o som, o rádio online pausa e não volta sozinho.
 */
class WebRadioService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Rumo")
            // Muitas rádios mandam de https para http (ou o contrário) no meio do caminho
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(CONNECT_MS)
            .setReadTimeoutMs(READ_MS)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        // Parado e sem estação: o serviço se encerra assim que a tela soltar a ligação (libera o player, o foco de
        // áudio e a sessão de mídia antes de o FM voltar)
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (p.mediaItemCount == 0 && !p.playWhenReady) stopSelf()
            }
        })
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val builder = MediaSession.Builder(this, player)
        open?.let { builder.setSessionActivity(it) }
        session = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Parado, não há por que o serviço continuar existindo. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private companion object {
        const val CONNECT_MS = 10_000
        const val READ_MS = 15_000
    }
}
