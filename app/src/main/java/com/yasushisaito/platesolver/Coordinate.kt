package com.yasushisaito.platesolver

import java.io.Serializable
import java.lang.Math
import kotlin.math.*


// Represents a coordinate in an image file.
// x is in range [0, image width).
// y is in range [0, image height).
// (0,0) is at the upper left corner of the image.
data class PixelCoordinate(val x: Double, val y: Double) : Serializable

// The astronomical coordinate.
data class CelestialCoordinate(
    // Right ascension, in range [0, 360).
    val ra: Double,
    // Declination, in range [-90, 90].
    val dec: Double) : Serializable {

    fun toDisplayString(): String {
        return "ra: %s\ndec: %s".format(
            rightAscensionToString(ra),
            declinationToString(dec))
    }
}

// Size of an image
data class ImageDimension(val width: Int, val height: Int)

// Size of a view
data class CanvasDimension(val width: Int, val height: Int)

// Convert an RA value in range [0,360) to an "XhYmZs" string.
fun rightAscensionToString(ra: Double): String {
    val hour = (ra / 15.0).toInt()
    val remainder = ra  - hour * 15
    val min = (remainder * 4).toInt()
    val second = (remainder - min / 4) * 60
    return "%02dh%02dm%.2f".format(hour, min, second)
}

fun declinationToString(dec: Double): String {
    return "%.3f".format(dec)
}

private fun radianToDeg(rad: Double): Double {
    return rad * 180 / Math.PI
}

private fun degToRadian(deg: Double): Double {
    return deg * Math.PI / 180.0
}

private fun sinDeg(deg: Double): Double {
    return sin(degToRadian(deg))
}

private fun cosDeg(deg: Double): Double {
    return cos(degToRadian(deg))
}

//private val PIXEL_BIN_FACTOR = 57.5
private const val PIXEL_BIN_FACTOR = 180 / Math.PI // ??

// Convert pixel coordinate to WCS.
//
// https://sourceforge.net/p/astap-program/discussion/general/thread/6eed679fcc/
fun convertPixelToCelestial(
    p: PixelCoordinate,
    imageDimension: ImageDimension,
    refPixel: PixelCoordinate,
    refCel: CelestialCoordinate,
    matrix: Matrix22): CelestialCoordinate {
    val d = matrix.multiply(Vector2(
        (p.x - refPixel.x) / PIXEL_BIN_FACTOR,
        (imageDimension.height - p.y - refPixel.y) / PIXEL_BIN_FACTOR))
    val dRa = d.x
    val dDec =  d.y
    val delta = cosDeg(refCel.dec) - dDec * sinDeg(refCel.dec)
    val gamma = sqrt(dRa * dRa + delta * delta)
    val ra = refCel.ra + radianToDeg(atan2(dRa, delta))
    val dec = radianToDeg(atan((sinDeg(refCel.dec) + dDec * cosDeg(refCel.dec)) / gamma))
    return CelestialCoordinate(ra=ra, dec=dec)
}

// Convert WCS to the pixel coordinate. Inverse of pixelToWcs.
// Arg "matrix" should be the inverse of that passed to convertPixelToWcs.
//
// https://sourceforge.net/p/astap-program/discussion/general/thread/6eed679fcc/
fun convertCelestialToPixel(wcs: CelestialCoordinate,
                            imageDimension: ImageDimension,
                            refPixel: PixelCoordinate,
                            refCel: CelestialCoordinate,
                            matrix: Matrix22): PixelCoordinate {
    val h = sinDeg(wcs.dec)*sinDeg(refCel.dec) + cosDeg(wcs.dec)*cosDeg(refCel.dec)*cosDeg(wcs.ra-refCel.ra)
    val dRa = (cosDeg(wcs.dec)*sinDeg(wcs.ra-refCel.ra)) / h
    val dDec = (sinDeg(wcs.dec)*cosDeg(refCel.dec)-cosDeg(wcs.dec)*sinDeg(refCel.dec)*cosDeg(wcs.ra-refCel.ra)) / h
    val d = matrix.multiply(Vector2(dRa, dDec))
    return PixelCoordinate(x=refPixel.x + d.x * PIXEL_BIN_FACTOR, y=imageDimension.height - (d.y * PIXEL_BIN_FACTOR + refPixel.y))
}
