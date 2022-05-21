package com.yasushisaito.platesolver

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader


// Parser for deep_sky.csv file. The format is described in the first two lines
// the file
class DeepSkyCsv(stream: InputStream) {
    // names are all in lowercase.
    data class Entry(val wcs: WcsCoordinate, val names: List<String>) {
        override fun toString(): String {
            val namesString = names.joinToString("/")
            return "DeepSkyEntry(wcs=$wcs names=$namesString)"
        }
    }
    private val entries = ArrayList<Entry>()

    init {
        parse(stream)
    }

    private fun parse(stream: InputStream) {
        val reader = BufferedReader(InputStreamReader(stream))
        // The first two lines are comments
        reader.readLine()
        reader.readLine()

        while (true) {
            val line = reader.readLine()?.trimIndent() ?: break
            if (line.isEmpty()) continue
            val cols = line.split(",")
            if (cols.size < 3) continue
            // right ascension in range [0,86400]
            // Convert it to range [0, 360]
            val ra = cols[0].toDouble() * 360 / 864000.0
            // Declination in range [-32400, +32400]
            // Convert it to range [-90, 90]
            val dec = cols[1].toDouble() * 90 / 324000.0
            val names = cols[2].split("/").map { it.lowercase() }
            entries.add(Entry(wcs=WcsCoordinate(ra, dec), names=names))
            if (names.contains("m42")) {
                println("DEEPSKY: add ${entries.last()}")
            }
        }
    }

    fun findByName(wantName: String): Entry? {
        val wantNameLower = wantName.lowercase()
        for (ent in entries) {
            for (name in ent.names) {
                if (name == wantNameLower) return ent
            }
        }
        return null
    }

    fun findInRange(minRa: Double, minDec: Double, maxRa: Double, maxDec: Double): ArrayList<Entry> {
        val hits = ArrayList<Entry>()
        var n = 0
        for (ent in entries) {
            if (ent.wcs.ra in minRa..maxRa &&
                ent.wcs.dec in minDec..maxDec) {
                hits.add(ent)
                n++
                if (n > 100) break
            }
        }
        return hits
    }
}