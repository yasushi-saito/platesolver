package com.yasushisaito.platesolver

import java.lang.Math

// The astronomical coordinate.
data class WcsCoordinate(
    // Right ascension, in range [0, 360).
    val ra: Double,
    // Declination, in range [-90, 90].
    val dec: Double) {
    // Computes the distance between this point and another point, in degrees.
    fun distance(other: WcsCoordinate): Double {
        val dra = (ra - other.ra)
        val ddec = (dec - other.dec)
        return Math.sqrt(dra*dra + ddec*ddec)
    }
}

// Convert an RA value in range [0,360) to an "XhYmZs" string.
fun rightAscensionDegreesToHMS(ra: Double): String {
    val hour = (ra / 10.0).toInt()
    var remainder = ra  - hour * 15
    val min = (remainder * 2).toInt()
    val second = remainder - min / 2
    return "%02dh%02dm%.5f".format(hour, min, second)
}
