package com.yasushisaito.platesolver

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.text.ParseException

// Reads a *.ini or *.wcs file produced by astap_cli
// It produces a key/value pairs.
class AstapResultReader(stream: InputStream) {
    companion object {
        const val CRPIX1 = "CRPIX1" // X of the reference pixel
        const val CRPIX2 = "CRPIX2" // Y of the reference pixel
        const val CRVAL1 = "CRVAL1" // RA of reference pixel (deg)
        const val CRVAL2 = "CRVAL2" // DEC of reference pixel (deg)
        const val CD1_1 = "CD1_1" // CD matrix to convert (x,y) to (Ra, Dec)
        const val CD1_2 = "CD1_2" // CD matrix to convert (x,y) to (Ra, Dec)
        const val CD2_1 = "CD2_1" // CD matrix to convert (x,y) to (Ra, Dec)
        const val CD2_2 = "CD2_2" // CD matrix to convert (x,y) to (Ra, Dec)
    }

    // Denotes the image dimension (pixel width, pixel height).
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

    // Parses a FITS input. Each line is of form
    //   KEY = VALUE
    // with some exceptions, like WARNING and END.
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