package com.meshx.app.mesh

import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The MeshX link score. One number from 0 to 100 that says how good a radio link is.
 *
 *   score = 0.35 * signal strength + 0.25 * signal-to-noise + 0.25 * delivery + 0.15 * battery
 *
 * Each part is first scaled to 0..1. The adaptive routing mode uses this score.
 */
object LinkScore {
    const val W_RSSI = 0.35f
    const val W_SNR = 0.25f
    const val W_DELIVERY = 0.25f
    const val W_BATTERY = 0.15f

    const val RSSI_MIN = -120f
    const val RSSI_MAX = -50f
    const val SNR_MIN = -10f
    const val SNR_MAX = 10f

    /** Used when a link has no radio measurement, for example a phone-to-phone link. */
    const val UNKNOWN_SIGNAL = 0.75f

    /**
     * @param rssi dBm, 0 means "not measured"
     * @param snr dB
     * @param loss share of packets lost, 0..1
     * @param battery percent of the node on the other end, negative means unknown
     */
    fun score(rssi: Float, snr: Float, loss: Float, battery: Int): Int {
        val rssiPart = if (rssi == 0f) UNKNOWN_SIGNAL else unit((rssi - RSSI_MIN) / (RSSI_MAX - RSSI_MIN))
        val snrPart = if (rssi == 0f) UNKNOWN_SIGNAL else unit((snr - SNR_MIN) / (SNR_MAX - SNR_MIN))
        val deliveryPart = 1f - unit(loss)
        val batteryPart = if (battery < 0) 0.5f else unit(battery / 100f)
        val score = W_RSSI * rssiPart + W_SNR * snrPart + W_DELIVERY * deliveryPart + W_BATTERY * batteryPart
        return (score * 100f).roundToInt().coerceIn(1, 100)
    }

    /** Cost of one hop for the adaptive mode: a perfect link costs 1, a weak link costs much more. */
    fun cost(score: Int): Float = 100f / score.coerceIn(1, 100)

    /** 0..4 bars for the user interface. */
    fun bars(score: Int): Int = when {
        score >= 75 -> 4
        score >= 55 -> 3
        score >= 35 -> 2
        score > 0 -> 1
        else -> 0
    }

    fun word(score: Int): String = when {
        score >= 75 -> "Strong"
        score >= 55 -> "Good"
        score >= 35 -> "Fair"
        score > 0 -> "Weak"
        else -> "No link"
    }

    private fun unit(value: Float) = value.coerceIn(0f, 1f)
}

/** Distance and direction between two positions. */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Compass bearing from point 1 to point 2 in degrees, 0 = north, 90 = east. */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun formatDistance(meters: Double): String = when {
        meters < 1000 -> "${meters.roundToInt()} m"
        meters < 10_000 -> String.format(Locale.US, "%.1f km", meters / 1000)
        else -> "${(meters / 1000).roundToInt()} km"
    }

    fun compass(bearing: Double): String {
        val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[((bearing + 22.5) / 45.0).toInt() % 8]
    }
}
