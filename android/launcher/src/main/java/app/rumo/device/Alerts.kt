package app.rumo.device

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Sons dos radares, sintetizados (os mesmos do protótipo HTML): aviso curto ao entrar na zona e,
 * acima do limite, bipes que aceleram e sobem de tom conforme o radar se aproxima. Só tocam com o Rumo na tela:
 * a tela inicial chama [stop] no onStop. No aparelho, qualquer som silencia a música enquanto toca.
 */
class Alerts(context: Context) {
    private val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { }
        .build()
    private val chimePcm by lazy { render(listOf(Seg(0.0, 988.0, 0.32, false, 0.5), Seg(0.2, 1319.0, 0.5, false, 0.5))) }

    // Bipes prontos, um por degrau de proximidade (21 degraus: a diferença de tom e ritmo entre eles não se ouve), e
    // um silêncio só, do tamanho do maior intervalo: até 7 bipes por segundo sem montar e jogar fora buffers a cada um
    private val beeps = arrayOfNulls<ShortArray>(STEPS + 1)
    private val silence by lazy { ShortArray((0.95 * RATE).toInt()) }

    private fun beep(step: Int): ShortArray = beeps[step] ?: run {
        val k = step.toDouble() / STEPS
        render(listOf(Seg(0.0, 1300.0 + 700 * k, min(0.08, gapOf(k) * 0.45), true, 0.28)), tail = 0.0)
    }.also { beeps[step] = it }

    private fun gapOf(k: Double) = (950 - 820 * k) / 1000.0

    /** Proximidade do radar (0 na borda da zona, 1 em cima dele) enquanto acima do limite; null = sem bipes. */
    @Volatile private var closeness: Float? = null
    private var alarmThread: Thread? = null
    @Volatile private var chimeUntil = 0L

    /** Aviso de entrada na zona do radar: dois tons. */
    fun chime() {
        chimeUntil = System.currentTimeMillis() + 800
        Thread({ playOnce(chimePcm) }, "rumo-aviso").start()
    }

    /** Liga, ajusta ou desliga ([k] null) os bipes de "acima do limite do radar". */
    fun alarm(k: Float?) {
        closeness = k
        if (k != null && alarmThread?.isAlive != true) {
            alarmThread = Thread(::alarmLoop, "rumo-bipes").also { it.start() }
        }
    }

    fun stop() = alarm(null)

    private fun alarmLoop() {
        val track = streamTrack()
        am.requestAudioFocus(focus)
        try {
            track.play()
            // Espera o aviso de entrada terminar antes do primeiro bipe
            while (System.currentTimeMillis() < chimeUntil && closeness != null) Thread.sleep(50)
            while (true) {
                val k = closeness ?: break
                val step = (k.coerceIn(0f, 1f) * STEPS).roundToInt()
                val beep = beep(step)
                val rest = ((gapOf(step.toDouble() / STEPS) * RATE).toInt() - beep.size).coerceIn(0, silence.size)
                track.write(beep, 0, beep.size)
                track.write(silence, 0, rest)
            }
        } catch (e: Exception) {
            // som não é essencial: sem bipe, o aviso visual continua
        } finally {
            try {
                track.stop()
            } catch (e: Exception) {
            }
            track.release()
            am.abandonAudioFocusRequest(focus)
        }
    }

    private fun playOnce(pcm: ShortArray) {
        am.requestAudioFocus(focus)
        val track = AudioTrack(attrs, format(), pcm.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(pcm.size * 1000L / RATE + 100)
            track.stop()
        } catch (e: Exception) {
        } finally {
            track.release()
            if (closeness == null) am.abandonAudioFocusRequest(focus)
        }
    }

    private fun streamTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack(attrs, format(), maxOf(min, RATE / 10 * 2), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
    }

    private fun format() = AudioFormat.Builder()
        .setSampleRate(RATE)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build()

    private class Seg(val start: Double, val freq: Double, val dur: Double, val square: Boolean, val amp: Double)

    private fun render(segs: List<Seg>, tail: Double = 0.05): ShortArray {
        val total = segs.maxOf { it.start + it.dur } + tail
        val buf = DoubleArray((total * RATE).toInt())
        val att = (0.005 * RATE).toInt()
        for (s in segs) {
            val n0 = (s.start * RATE).toInt()
            val n = (s.dur * RATE).toInt()
            for (i in 0 until n) {
                val idx = n0 + i
                if (idx >= buf.size) break
                val ph = 2 * PI * s.freq * i / RATE
                val wave = if (s.square) (if (sin(ph) >= 0) 1.0 else -1.0) else sin(ph)
                val edge = when {
                    i < att -> i.toDouble() / att
                    i > n - att -> (n - i).toDouble() / att
                    else -> 1.0
                }
                val decay = if (s.square) 1.0 else exp(-3.0 * i / n)
                buf[idx] += wave * edge * decay * s.amp
            }
        }
        return ShortArray(buf.size) { (buf[it].coerceIn(-1.0, 1.0) * 32767 * 0.9).toInt().toShort() }
    }

    private companion object {
        const val RATE = 44_100
        const val STEPS = 20
    }
}
