package ai.carbuddy.core.geo

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Solar position from the NOAA / Meeus approximations (±0.5°, enough for "is the sun up" and
 * "is it ahead of us"). Offline, no API. Azimuth: degrees clockwise from north.
 */
object Sun {
    data class Position(val elevationDeg: Double, val azimuthDeg: Double)

    fun position(epochMs: Long, lat: Double, lng: Double): Position {
        val jd = epochMs / 86_400_000.0 + 2440587.5
        val t = (jd - 2451545.0) / 36525.0                                  // Julian centuries since J2000
        val l0 = norm360(280.46646 + t * (36000.76983 + 0.0003032 * t))     // mean longitude
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)               // mean anomaly
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sin(rad(m)) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(rad(2 * m)) * (0.019993 - 0.000101 * t) + sin(rad(3 * m)) * 0.000289
        val trueLong = l0 + c
        val omega = 125.04 - 1934.136 * t
        val lambda = trueLong - 0.00569 - 0.00478 * sin(rad(omega))
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = eps0 + 0.00256 * cos(rad(omega))
        val decl = deg(asin(sin(rad(eps)) * sin(rad(lambda))))
        val y = tan(rad(eps / 2)).let { it * it }
        val eqTime = 4 * deg(
            y * sin(2 * rad(l0)) - 2 * e * sin(rad(m)) + 4 * e * y * sin(rad(m)) * cos(2 * rad(l0)) -
                0.5 * y * y * sin(4 * rad(l0)) - 1.25 * e * e * sin(2 * rad(m)),
        )                                                                  // minutes
        val utcMin = ((epochMs % 86_400_000L) + 86_400_000L) % 86_400_000L / 60_000.0
        val trueSolarMin = ((utcMin + eqTime + 4 * lng) % 1440 + 1440) % 1440
        val hourAngle = trueSolarMin / 4 - 180
        val zenith = deg(acos((sin(rad(lat)) * sin(rad(decl)) + cos(rad(lat)) * cos(rad(decl)) * cos(rad(hourAngle))).coerceIn(-1.0, 1.0)))
        val az = norm360(deg(atan2(sin(rad(hourAngle)), cos(rad(hourAngle)) * sin(rad(lat)) - tan(rad(decl)) * cos(rad(lat)))) + 180)
        return Position(90 - zenith, az)
    }

    /** Upper limb above the horizon, refraction included (−0.833°). */
    fun isDay(epochMs: Long, lat: Double, lng: Double) = position(epochMs, lat, lng).elevationDeg > -0.833

    /** Smallest angle between two bearings, 0..180. */
    fun angleBetween(a: Double, b: Double): Double = kotlin.math.abs(((a - b) % 360 + 540) % 360 - 180)

    private fun rad(d: Double) = d * PI / 180
    private fun deg(r: Double) = r * 180 / PI
    private fun norm360(d: Double) = ((d % 360) + 360) % 360
}
