package com.yasushisaito.platesolver

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

class Fits(stream: InputStream) {
    val CRPIX1 = "CRPIX1" // X of the reference pixel
    val CRPIX2 = "CRPIX2" // Y of the reference pixel
    val CRVAL1 = "CRVAL1" // RA of reference pixel (deg)
    val CRVAL2 = "CRVAL2" // DEC of reference pixel (deg)
    val CDELT1 = "CDELT1" // X pixel size (deg)
    val CDELT2 = "CDELT2" // Y pixel size (deg)
    val CD1_1 = "CD1_1" // CD matrix to convert (x,y) to (Ra, Dec)
    val CD1_2 = "CD1_2" // CD matrix to convert (x,y) to (Ra, Dec)
    val CD2_1 = "CD2_1" // CD matrix to convert (x,y) to (Ra, Dec)
    val CD2_2 = "CD2_2" // CD matrix to convert (x,y) to (Ra, Dec)

    data class BoolKey(val key: String, val value: Boolean) {}
    data class FloatKey(val key: String, val value: Double) {}
    data class StringKey(val key: String, val value: String) {}

    private val bools = ArrayList<BoolKey>()
    private val floats = ArrayList<FloatKey>()
    private val strings = ArrayList<StringKey>()
    private val warnings = ArrayList<String>()
    private val comments = ArrayList<String>()

    init {
        parse(stream)
    }

    fun getBool(key: String, defaultValue = false): Boolean {
        for (f in bools) {
            if (f.key == key) return f.value
        }
        return defaultValue
    }

    val INVALID_DOUBLE = -999.0
    fun getDouble(key: String, defaultValue: Double=INVALID_DOUBLE): Double {
        for (f in floats) {
            println("GETFLOAT: ${f.key} $key")
            if (f.key == key) return f.value
        }
        return defaultValue
    }

    val INVALID_STRING = ""
    fun getString(key: String, defaultValue = INVALID_STRING): String {
        for (s in strings) {
            if (s.key == key) return s.value
        }
        return defaultValue
    }

    private fun parse(stream: InputStream) {
        val numOrBoolRe = Regex("^([A-Z0-9_]+)\\s*=\\s*([0-9.+-eETF]+)")
        val strRe = Regex("^([A-Z0-9_]+)\\s*=\\s*'([^']+)'")
        val commentRe = Regex("^COMMENT\\s+(.*)")
        val reader = BufferedReader(InputStreamReader(stream))
        while (true) {
            val line = reader.readLine();
            if (line == null) break
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
            result = commentRe.find(line)
            if (result != null) {
                val (value) = result.destructured
                println("FITS: comment $value")
                comments.add(value)
            }
            if (line == "END") {
                continue
            }
            println("INVALID LINE: $line")
        }
    }
}