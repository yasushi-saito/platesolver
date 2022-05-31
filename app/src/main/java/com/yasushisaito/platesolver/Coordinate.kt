package com.yasushisaito.platesolver

import java.io.Serializable
import java.lang.Math


// pixel image (x, y) to astronomertrical coordinate.
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
    // Computes the distance between this point and another point, in degrees.
    fun distance(other: CelestialCoordinate): Double {
        val dra = (ra - other.ra)
        val ddec = (dec - other.dec)
        return Math.sqrt(dra*dra + ddec*ddec)
    }

    fun toDisplayString(): String {
        return "ra: %s\ndec: %s".format(
            rightAscensionToString(ra),
            declinationToString(dec))
    }
}

// Convert an RA value in range [0,360) to an "XhYmZs" string.
fun rightAscensionToString(ra: Double): String {
    val hour = (ra / 15.0).toInt()
    var remainder = ra  - hour * 15
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
    return Math.sin(degToRadian(deg))
}

private fun cosDeg(deg: Double): Double {
    return Math.cos(degToRadian(deg))
}

//private val PIXEL_BIN_FACTOR = 57.5
private val PIXEL_BIN_FACTOR = 180 / Math.PI // ??

// Convert pixel coordinate to WCS.
//
// https://sourceforge.net/p/astap-program/discussion/general/thread/6eed679fcc/
//
// dRA = CD001001 * (x-CRPIX1) + CD001002 * (y-CRPIX2)
// dDEC = CD002001 * (x-CRPIX1) + CD002002 * (y-CRPIX2)
// delta= cos(CRVAL2) - dDEC*sin(CRVAL2)
// gamma= sqrt( dRA*dRA + delta*delta )
// RA = CRVAL1 + atan (dRA/delta)
// DEC = atan ( [sin(CRVAL2)+dDEC*cos(CRVAL2)] / gamma )
//
// dRA = CD1_1 * (x - refPixel.x) + CD1_2 * (y - refPixel.y)
// dDEC = CD2_1 * (x - refPixel.x) + CD2_2 * (y - refPixel.y)
// delta = cos(refCel.dec) - dDEC*sin(refCel.dec)
// gamma = sqrt(dRA * dRA + delta * delta)
// ra = refCel.ra + atan(dRA / delta)
// dec = atan( (sin(refCel.dec) + dDEC*cos(refCel.dec)) / gamma)
fun convertPixelToCelestial(
    p: PixelCoordinate,
    imageDimension: AstapResultReader.ImageDimension,
    refPixel: PixelCoordinate,
    refCel: CelestialCoordinate,
    matrix: Matrix22): CelestialCoordinate {
    val d = matrix.mult(Vector2(
        (p.x - refPixel.x) / PIXEL_BIN_FACTOR,
        (imageDimension.height - p.y - refPixel.y) / PIXEL_BIN_FACTOR))
    val dRa = d.x
    val dDec =  d.y
    val delta = cosDeg(refCel.dec) - dDec * sinDeg(refCel.dec)
    val gamma = Math.sqrt(dRa * dRa + delta * delta)
    val ra = refCel.ra + radianToDeg(Math.atan2(dRa, delta))
    val dec = radianToDeg(Math.atan((sinDeg(refCel.dec) + dDec * cosDeg(refCel.dec)) / gamma))
    // println("ConvertPixelToWcs: p=$p refCel=$refCel dra=$dRa ddec=$dDec delta=$delta gamma=$gamma ra=$ra dec=$dec")
    return CelestialCoordinate(ra=ra, dec=dec)
    /*
    val v = matrix.mult(Vector2(
        p.x - refPixel.x,
        imageDimension.height - p.y - refPixel.y))
    return WcsCoordinate(ra = v.x + refCel.ra, dec = v.y + refCel.dec)*/
}

// Convert WCS to the pixel coordinate. Inverse of pixelToWcs.
// Arg "matrix" should be the inverse of that passed to convertPixelToWcs.
//
// https://sourceforge.net/p/astap-program/discussion/general/thread/6eed679fcc/
//
// H = sin(DEC)*sin(CRVAL2) + cos(DEC)*cos(CRVAL2)*cos(RA-CRVAL1)
// dRA = cos(DEC)*sin(RA-CRVAL1) / H
// dDEC = [ sin(DEC)*cos(CRVAL2) - cos(DEC)*sin(CRVAL2)*cos(RA-CRVAL1) ] / H
// det=CD002002*CD001001-CD001002*CD002001
// x = CRPIX1 - (CD001002*dDEC - CD002002*dRA) / det
// y = CRPIX2 + (CD001001*dDEC - CD002001*dRA) / det
//
// H = sin(DEC)*sin(refCel.dec) + cos(DEC)*cos(refCel.dec)*cos(RA-refCel.ra)
// dRA = cos(DEC)*sin(RA-refCel.ra) / H
// dDEC = [ sin(DEC)*cos(refCel.dec) - cos(DEC)*sin(refCel.dec)*cos(RA-refCel.ra) ] / H
// det=CD2_2*CD1_1-CD1_2*CD2_1
// x = refPixel.x - (CD1_2*dDEC - CD2_2*dRA) / det
// y = refPixel.y + (CD1_1*dDEC - CD2_1*dRA) / det
fun convertCelestialToPixel(wcs: CelestialCoordinate,
                            imageDimension: AstapResultReader.ImageDimension,
                            refPixel: PixelCoordinate,
                            refCel: CelestialCoordinate,
                            matrix: Matrix22): PixelCoordinate {
    val h = sinDeg(wcs.dec)*sinDeg(refCel.dec) + cosDeg(wcs.dec)*cosDeg(refCel.dec)*cosDeg(wcs.ra-refCel.ra)
    val dRa = (cosDeg(wcs.dec)*sinDeg(wcs.ra-refCel.ra)) / h
    val dDec = (sinDeg(wcs.dec)*cosDeg(refCel.dec)-cosDeg(wcs.dec)*sinDeg(refCel.dec)*cosDeg(wcs.ra-refCel.ra)) / h
    val d = matrix.mult(Vector2(dRa, dDec))
    // Log.d("ConvertWcsToPixel", "wcs=$wcs refCel=$refCel h=$h dra=$dRa ddec=$dDec d=$d")
    return PixelCoordinate(x=refPixel.x + d.x * PIXEL_BIN_FACTOR, y=imageDimension.height - (d.y * PIXEL_BIN_FACTOR + refPixel.y))
/*    val pv = matrix.mult(Vector2(wcs.ra - refCel.ra, wcs.dec - refCel.dec))
    // wcsToPixel's Y coordinate moves up, but PixelCoordinate moves down.
    return PixelCoordinate(
        pv.x + refPixel.x,
        (imageDimension.height - (pv.y + refPixel.y)))*/
}
