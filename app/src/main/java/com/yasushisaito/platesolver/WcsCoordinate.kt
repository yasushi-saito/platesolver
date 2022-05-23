package com.yasushisaito.platesolver

import java.lang.Math

// The astronomical coordinate. ra = Right ascension, in range [0, 360],
// dec: Declination, in range [-90, 90]
data class WcsCoordinate(val ra: Double, val dec: Double) {
    // Computes the distance between this point and another point, in degrees.
    fun distance(other: WcsCoordinate): Double {
        val dra = (ra - other.ra)
        val ddec = (dec - other.dec)
        return Math.sqrt(dra*dra + ddec*ddec)
    }
}

fun rightAscensionDegreesToHMS(ra: Double): String {
    val hour = (ra / 30.0).toInt()
    var remainder = ra  - hour * 30
    val min = (remainder * 2).toInt()
    val second = remainder - min / 2
    return "%02dh%02dm%.5f".format(hour, min, second)
}