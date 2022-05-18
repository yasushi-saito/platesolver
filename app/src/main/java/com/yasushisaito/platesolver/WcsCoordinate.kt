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

