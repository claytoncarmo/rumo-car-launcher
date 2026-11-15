package app.rumo.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Rádio online: o player fica num serviço comum (no processo do Rumo) para o som continuar quando o motorista abre
 * outro app, como o FM. Enquanto toca, fica em primeiro plano com uma notificação simples.
 *
 * **Sem sessão de mídia do Android (MediaSession), de propósito:** com ela o firmware passa a ver o Rumo como app de
 * mídia e, a cada troca de fonte (o rádio ou o Android Auto pegando o som), encerra o pacote inteiro, levando a tela
 * inicial junto (ver docs/device-integration.md).
 *
 * Sem foco de áudio (ver onCreate): quando o Android Auto pega o som, quem para o rádio online é o Rumo, pelo sourceinfo.
 * O serviço se encerra quando ninguém está ligado nele e o player está parado.
 */
class WebRadioService : Service() {
    inner class Local : Binder() {
        val service: WebRadioService get() = this@WebRadioService
    }

    private val binder = Local()
    lateinit var player: ExoPlayer
        private set
    private var foreground = false
    private var bound = false

    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Rumo")
            // Muitas rádios mandam de https para http (ou o contrário) no meio do caminho
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(CONNECT_MS)
            .setReadTimeoutMs(READ_MS)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            // O firmware marca como fonte (e depois encerra) o app que toca som do tipo mídia, com ou sem foco de áudio;
            // os alertas de radar, do tipo navegação, nunca foram marcados. Tipo "jogo": mesmo volume e mesma saída da
            // mídia, sem ser mídia. Sem pedir o foco de áudio: quem para o online quando o ZLINK pega o som é o Rumo
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_GAME).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ false,
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = updateForeground()
        })
    }

    override fun onBind(intent: Intent): IBinder {
        bound = true
        return binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        bound = false
        if (!playing()) stopSelf()
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    private fun playing() = player.playWhenReady && player.mediaItemCount > 0

    /** Tocando: primeiro plano com a notificação; parado: sai do primeiro plano (e se encerra sem a tela ligada). */
    private fun updateForeground() {
        if (playing()) {
            val n = notification(player.mediaMetadata.artist?.toString() ?: "Rádio online", player.mediaMetadata.title?.toString())
            if (!foreground) {
                // O serviço precisa estar "iniciado" para continuar sem a tela ligada nele (o Android recusa isso com o
                // Rumo em segundo plano; aí fica só em primeiro plano enquanto a tela estiver ligada)
                try {
                    startService(Intent(this, WebRadioService::class.java))
                } catch (e: IllegalStateException) {
                }
                startForeground(NOTIFICATION_ID, n)
                foreground = true
            } else {
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n)
            }
        } else if (foreground) {
            stopForeground(true)
            foreground = false
            if (!bound) stopSelf()
        }
    }

    private fun notification(station: String, title: String?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Rádio online", NotificationManager.IMPORTANCE_LOW))
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(station)
            .setContentText(title ?: "Rádio online")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private companion object {
        const val CONNECT_MS = 10_000
        const val READ_MS = 15_000
        const val CHANNEL = "web_radio"
        const val NOTIFICATION_ID = 7
    }
}
