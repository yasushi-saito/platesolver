package com.yasushisaito.platesolver

import android.content.res.AssetManager
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// Represents a well known deep sky object.
data class WellKnownDso(
    // Gxy, OC, etc.
    // See page 19 of https://ngcicproject.observers.org/public_HCNGC/The_HCNGC_intro.pdf
    val typ: String,
    val wcs: WcsCoordinate,
    // Visible magnitude
    val mag: Double,
    // List of names. An DSO may have multiple names, e.g., "M42", "NGC1976", "Orion nebula".
    // The order of names in the list is unspecified.
    val names: List<String>
) {
    override fun toString(): String {
        val namesString = names.joinToString("/")
        return "DeepSkyEntry(wcs=$wcs names=$namesString)"
    }
}


// Parser for deep_sky.csv file. The format is described in the first two lines
// the file
class WellKnownDsoReader(stream: InputStream) {
    companion object {
        private val singletonMu = ReentrantLock()
        private var singleton: WellKnownDsoReader? = null
        private val callbacks = ArrayList<()->Unit>()

        fun registerOnSingletonLoaded(cb: ()-> Unit) {
            singletonMu.withLock {
                if (singleton != null) {
                    Thread({cb()}).start()
                    return@withLock
                }
                callbacks.add(cb)
            }
        }
        fun getSingleton(): WellKnownDsoReader? {
            singletonMu.withLock {
                return singleton
            }
        }

        fun startLoadSingleton(assets: AssetManager) {
            Thread({
                singletonMu.withLock {
                    if (singleton == null) {
                        assets.open("wellknowndso.csv").use { inputStream ->
                            singleton = WellKnownDsoReader(inputStream)
                        }
                        for (cb in callbacks) {
                            cb()
                        }
                        callbacks.clear()
                    }
                }
            }).start()
        }
    }
    private val entries = ArrayList<WellKnownDso>()

    init {
        parse(stream)
    }

    private fun parse(stream: InputStream) {
        val reader = BufferedReader(InputStreamReader(stream))
        // The first line is the header
        reader.readLine()

        while (true) {
            val line = reader.readLine()?.trimIndent() ?: break
            if (line.isEmpty()) continue
            val cols = line.split(",")
            if (cols.size != 5) {
                throw Exception("Invalid line: $line")
            }
            val typ = cols[0]
            // right ascension in range [0,360]
            val ra = cols[1].toDouble()
            // Convert it to range [-90, 90]
            val dec = cols[2].toDouble()
            // Visible magnitude
            val mag = cols[3].toDouble()
            val names = cols[4].split("/").map { it.lowercase() }
            entries.add(WellKnownDso(typ=typ, wcs=WcsCoordinate(ra, dec), mag=mag, names=names))
            if (names.contains("m42")) {
                println("DEEPSKY: add ${entries.last()}")
            }
        }
        // Sort entries by magnitude first (brighter first), then
        // star types (non-star objects, such as nebulae are sorted before stars).
        entries.sortWith(Comparator<WellKnownDso> { a, b ->
            if (a.mag < b.mag) {
                -1
            } else if (a.mag > b.mag) {
                1
            } else if (a.typ != b.typ) {
                if (a.typ == "Star") 1
                else -1
            } else {
                0
            }
        })
    }

    fun findByName(wantName: String): WellKnownDso? {
        val wantNameLower = wantName.lowercase()
        for (ent in entries) {
            for (name in ent.names) {
                if (name == wantNameLower) return ent
            }
        }
        return null
    }

    fun findInRange(minRa: Double, minDec: Double, maxRa: Double, maxDec: Double): ArrayList<WellKnownDso> {
        val hits = ArrayList<WellKnownDso>()
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