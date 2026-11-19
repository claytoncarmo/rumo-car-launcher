package app.rumo.webradio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.rumo.shared.Alerts
import app.rumo.shared.WebRadioProtocol as P

/**
 * Player do rádio online, comandado pelo Rumo ([P]). Fica em primeiro plano com uma notificação simples enquanto toca,
 * para o som continuar com o Rumo fora da tela, e se encerra quando para sem ninguém ligado nele.
 *
 * Foco de áudio pelo player: quando o Android Auto (ou o rádio) pega o som, ele pausa. Se o firmware encerrar este app
 * na troca de fonte, o Rumo só vê a ligação cair (é para isso que o player mora aqui, e não no Rumo).
 */
class WebRadioService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var player: ExoPlayer
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<IBinder>()
    private var foreground = false
    private var bound = false
    // Os mesmos sons do Rumo (android/shared): com foco curto, a música do player só abaixa enquanto tocam
    // As linhas do registro (foco, falhas de áudio…) vão para o Rumo, que as grava no alertas.log
    private val alerts by lazy { Alerts(this) { line -> sendLog(line) } }

    /** Só aceita quem tem a mesma assinatura deste app (o Rumo). */
    private fun trusted(): Boolean =
        packageManager.checkSignatures(Binder.getCallingUid(), Process.myUid()) == PackageManager.SIGNATURE_MATCH

    private val control = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in P.TX_LISTEN..P.TX_ALARM) return super.onTransact(code, data, reply, flags)
            if (!trusted()) throw SecurityException("só o Rumo comanda o rádio online")
            data.enforceInterface(P.DESCRIPTOR)
            when (code) {
                P.TX_LISTEN -> data.readStrongBinder()?.let { b -> main.post { listen(b) } }
                P.TX_PLAY -> {
                    val id = data.readString().orEmpty()
                    val url = data.readString().orEmpty()
                    val name = data.readString().orEmpty()
                    val hls = data.readInt() == 1
                    main.post { play(id, url, name, hls) }
                }
                P.TX_STOP -> main.post { stopPlayback() }
                P.TX_CHIME -> alerts.chime()
                P.TX_ALARM -> data.readFloat().let { k -> alerts.alarm(k.takeUnless { it.isNaN() }) }
            }
            return true
        }
    }

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
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                updateForeground()
                publish()
            }
        })
    }

    override fun onBind(intent: Intent): IBinder {
        bound = true
        return control
    }

    override fun onUnbind(intent: Intent): Boolean {
        bound = false
        listeners.clear()
        alerts.stop()
        if (!playing()) stopSelf()
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        alerts.stop()
        player.release()
        super.onDestroy()
    }

    private fun listen(b: IBinder) {
        if (listeners.none { it == b }) listeners += b
        publish()
    }

    private fun play(id: String, url: String, name: String, hls: Boolean) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val item = MediaItem.Builder()
            .setMediaId(id)
            .setUri(Uri.parse(url))
            .apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            // O nome da estação vai como artista: o título fica para o "tocando agora" que o stream manda (ICY)
            .setMediaMetadata(MediaMetadata.Builder().setArtist(name).build())
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    private fun stopPlayback() {
        player.stop()
        player.clearMediaItems()
    }

    private fun playing() = player.playWhenReady && player.mediaItemCount > 0

    private fun status(): String = when {
        player.mediaItemCount == 0 -> P.STATUS_OFF
        player.playerError != null -> P.STATUS_ERROR
        // Pausado: o Rumo parou ou outro app (Android Auto) tomou o som
        !player.playWhenReady -> P.STATUS_OFF
        // Pronto: tocando (ou calado um instante por outro som curto, como a voz do navegador)
        player.playbackState == Player.STATE_READY -> P.STATUS_LIVE
        player.playbackState == Player.STATE_ENDED -> P.STATUS_ERROR
        player.playbackState == Player.STATE_IDLE -> P.STATUS_OFF
        else -> P.STATUS_CONNECTING
    }

    private fun sendLog(line: String) {
        for (b in listeners) {
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(P.EVENTS_DESCRIPTOR)
                data.writeString(line)
                b.transact(P.TX_LOG, data, null, IBinder.FLAG_ONEWAY)
            } catch (e: Exception) {
            } finally {
                data.recycle()
            }
        }
    }

    /** Avisa os ouvintes (o Rumo); quem morreu sai da lista. */
    private fun publish() {
        val id = player.currentMediaItem?.mediaId.orEmpty()
        val status = status()
        val title = player.mediaMetadata.title?.toString()?.trim().orEmpty()
        val dead = ArrayList<IBinder>()
        for (b in listeners) {
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(P.EVENTS_DESCRIPTOR)
                data.writeString(id)
                data.writeString(status)
                data.writeString(title)
                b.transact(P.TX_EVENT, data, null, IBinder.FLAG_ONEWAY)
            } catch (e: Exception) {
                dead += b
            } finally {
                data.recycle()
            }
        }
        listeners.removeAll(dead)
    }

    /** Tocando: primeiro plano com a notificação; parado: sai do primeiro plano (e se encerra sem ninguém ligado). */
    private fun updateForeground() {
        if (playing()) {
            val n = notification(player.mediaMetadata.artist?.toString() ?: "Rádio online", player.mediaMetadata.title?.toString())
            if (!foreground) {
                // "Iniciado" para continuar depois que o Rumo soltar a ligação
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
            @Suppress("DEPRECATION")
            stopForeground(true)
            foreground = false
            if (!bound) stopSelf()
        }
    }

    private fun notification(station: String, title: String?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Rádio online", NotificationManager.IMPORTANCE_LOW))
        // Tocar na notificação abre o Rumo
        val open = packageManager.getLaunchIntentForPackage(RUMO)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(station)
            .setContentText(title?.takeIf { it.isNotBlank() } ?: "Rádio online")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private companion object {
        const val RUMO = "app.rumo"
        const val CONNECT_MS = 10_000
        const val READ_MS = 15_000
        const val CHANNEL = "web_radio"
        const val NOTIFICATION_ID = 7
    }
}
