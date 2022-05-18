package com.yasushisaito.platesolver

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.text.ParseException

class Wcs(stream: InputStream) {
    companion object {
        private const val CRPIX1 = "CRPIX1" // X of the reference pixel
        private const val CRPIX2 = "CRPIX2" // Y of the reference pixel
        private const val CRVAL1 = "CRVAL1" // RA of reference pixel (deg)
        private const val CRVAL2 = "CRVAL2" // DEC of reference pixel (deg)
        private const val CDELT1 = "CDELT1" // X pixel size (deg)
        private const val CDELT2 = "CDELT2" // Y pixel size (deg)
        private const val CD1_1 = "CD1_1" // CD matrix to convert (x,y) to (Ra, Dec)
        private const val CD1_2 = "CD1_2" // CD matrix to convert (x,y) to (Ra, Dec)
        private const val CD2_1 = "CD2_1" // CD matrix to convert (x,y) to (Ra, Dec)
        private const val CD2_2 = "CD2_2" // CD matrix to convert (x,y) to (Ra, Dec)
    }
    // Denotes the image dimension (pixel width, pixel height).
    data class ImageDimension(val width: Int, val height: Int)
    data class BoolKey(val key: String, val value: Boolean)
    data class FloatKey(val key: String, val value: Double)
    data class StringKey(val key: String, val value: String)

    private val bools = ArrayList<BoolKey>()
    private val floats = ArrayList<FloatKey>()
    private val strings = ArrayList<StringKey>()
    private val warnings = ArrayList<String>()

    init {
        parse(stream)
    }

    private val refPixel = PixelCoordinate(getDouble(CRPIX1), getDouble(CRPIX2))
    private val refWcs = WcsCoordinate(getDouble(CRVAL1), getDouble(CRVAL2))
    private val pixelToWcs = Matrix22(
        getDouble(CD1_1), getDouble(CD1_2),
        getDouble(CD2_1), getDouble(CD2_2)
    )
    private val wcsToPixel = pixelToWcs.invert()
    // refPixel is the middle point of the image, so double them to get the image size
    private val imageDimension = ImageDimension((refPixel.x*2).toInt(), (refPixel.y*2).toInt())

    fun getBool(key: String): Boolean {
        for (f in bools) {
            if (f.key == key) return f.value
        }
        throw ParseException("Bool key $key not found", 0)
    }

    fun getDouble(key: String): Double {
        for (f in floats) {
            if (f.key == key) return f.value
        }
        throw ParseException("Double key $key not found", 0)
    }

    fun getString(key: String): String {
        for (s in strings) {
            if (s.key == key) return s.value
        }
        throw ParseException("String key $key not found", 0)
    }

    fun getWarnings(): ArrayList<String> {
        return warnings
    }

    // Convert pixel coordinate to WCS.
    fun pixelToWcs(p: PixelCoordinate): WcsCoordinate {
        val v = pixelToWcs.mult(Vector2(
            p.x - refPixel.x,
            imageDimension.height - p.y - refPixel.y))
        return WcsCoordinate(ra = v.x + refWcs.ra, dec = v.y + refWcs.dec)
    }

    fun wcsToPixel(wcs: WcsCoordinate): PixelCoordinate {
        val pv = wcsToPixel.mult(Vector2(wcs.ra - refWcs.ra, wcs.dec - refWcs.dec))
        // wcsToPixel's Y coordinate moves up, but PixelCoordinate moves down.
        return PixelCoordinate(
            pv.x + refPixel.x,
            (imageDimension.height - (pv.y + refPixel.y)))
    }

    // Get the size of the user-provided image file (jpg, png, etc).
    fun getImageDimension(): ImageDimension { return imageDimension }

    private fun parse(stream: InputStream) {
        val numOrBoolRe = Regex("^([A-Z0-9-_]+)\\s*=\\s*([0-9.+-eETF]+)")
        val strRe = Regex("^([A-Z0-9-_]+)\\s*=\\s*'([^']+)'")
        val reader = BufferedReader(InputStreamReader(stream))
        while (true) {
            val line = reader.readLine()?.trim() ?: break
            if (line.startsWith("COMMENT ")) {
                continue
            }
            var result = numOrBoolRe.find(line)
            if (result != null) {
                val (key, value) = result.destructured
                when (value) {
                    "T" -> bools.add(BoolKey(key, true))
                    "F" -> bools.add(BoolKey(key, false))
                    else -> floats.add(FloatKey(key, value.toDouble()))
                }
                continue
            }
            result = strRe.find(line)
            if (result != null) {
                val (key, value) = result.destructured
                if (key == "WARNING") {
                    warnings.add(value)
                    println("FITS: warning $value")
                } else {
                    strings.add(StringKey(key, value))
                    println("FITS: add2 $key $value")
                }
                continue
            }
            if (line == "END") {
                continue
            }
            println("INVALID LINE: '[[$line]]'")
        }
    }
}