package app.rumo.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.rumo.core.TripMeter
import kotlin.math.roundToInt

enum class GpsStatus { NO_PERMISSION, OFF, SEARCHING, OK }

data class GpsInfo(
    val status: GpsStatus = GpsStatus.SEARCHING,
    val lat: Double? = null,
    val lon: Double? = null,
    val speedKmh: Int? = null,
    val bearing: Float? = null,
    val accuracyM: Int? = null,
    val tripMeters: Double = 0.0,
    val tripMovingMs: Long = 0,
    val tripAverageKmh: Int? = null,
)

/** Satélites usados na posição e vistos: à parte da posição, porque mudam várias vezes por segundo. */
data class Satellites(val used: Int = 0, val seen: Int = 0)

/**
 * GPS do aparelho, ligado só enquanto o Rumo está na tela (start/stop no onStart/onStop da tela inicial).
 * [onChange] recebe o estado e se é uma posição nova (fix) ou só uma mudança de estado; [onSatellites], a contagem
 * de satélites, quando muda.
 */
class Gps(
    context: Context,
    private val onChange: (GpsInfo, Boolean) -> Unit,
    private val onSatellites: (Satellites) -> Unit = {},
) {
    private val ctx = context.applicationContext
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val trip = TripMeter()
    private var info = GpsInfo()
    private var satellites = Satellites()
    private var lastFixAt = 0L
    private var running = false

    // No Android 9 a interface não tem implementação padrão destes métodos: sem eles o app quebra quando o sistema os chama
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        override fun onProviderEnabled(provider: String) = publish(info.copy(status = GpsStatus.SEARCHING))
        override fun onProviderDisabled(provider: String) = publish(info.copy(status = GpsStatus.OFF, speedKmh = null))
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            val now = Satellites(used, status.satelliteCount)
            if (now != satellites) {
                satellites = now
                onSatellites(now)
            }
            // Sem posição há um tempo: volta a "procurando" (não é uma posição nova)
            val stale = SystemClock.elapsedRealtime() - lastFixAt > STALE_MS
            if (stale && (info.status == GpsStatus.OK || info.speedKmh != null)) {
                publish(
                    info.copy(
                        status = if (info.status == GpsStatus.OK) GpsStatus.SEARCHING else info.status,
                        speedKmh = null,
                    )
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            publish(info.copy(status = GpsStatus.NO_PERMISSION))
            return
        }
        if (!lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            publish(info.copy(status = GpsStatus.OFF))
            return
        }
        running = true
        val enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        publish(info.copy(status = if (enabled) GpsStatus.SEARCHING else GpsStatus.OFF, speedKmh = null))
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        @Suppress("DEPRECATION")
        lm.registerGnssStatusCallback(gnss, Handler(Looper.getMainLooper()))
    }

    fun stop() {
        if (!running) return
        running = false
        lm.removeUpdates(listener)
        lm.unregisterGnssStatusCallback(gnss)
    }

    private fun onFix(l: Location) {
        lastFixAt = SystemClock.elapsedRealtime()
        fix = true
        trip.add(l.latitude, l.longitude, l.time, l.speed, if (l.hasAccuracy()) l.accuracy else Float.MAX_VALUE)
        publish(
            info.copy(
                status = GpsStatus.OK,
                lat = l.latitude,
                lon = l.longitude,
                speedKmh = if (l.hasSpeed()) (l.speed * 3.6f).roundToInt() else 0,
                // Parado, o rumo do GPS gira à toa: mantém o último
                bearing = if (l.hasBearing() && l.speed >= MIN_BEARING_SPEED_MS) l.bearing else info.bearing,
                accuracyM = if (l.hasAccuracy()) l.accuracy.roundToInt() else null,
                tripMeters = trip.meters,
                tripMovingMs = trip.movingMs,
                tripAverageKmh = trip.averageKmh,
            )
        )
    }

    private var fix = false

    private fun publish(i: GpsInfo) {
        info = i
        val newFix = fix
        fix = false
        onChange(i, newFix)
    }

    private companion object {
        const val STALE_MS = 5_000L
        const val MIN_BEARING_SPEED_MS = 1.4f
    }
}
