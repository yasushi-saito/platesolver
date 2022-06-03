package com.yasushisaito.platesolver

import android.content.res.AssetManager
import android.util.Log
import java.io.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// Log tag.
private const val TAG = "WellKnownDso"

// Max number of DSOs to return in findInRange.
private const val MAX_HITS = 100

// Represents a well known deep sky object.
data class WellKnownDso(
    // Gxy, OC, etc.
    // See page 19 of https://ngcicproject.observers.org/public_HCNGC/The_HCNGC_intro.pdf
    val typ: String,
    val cel: CelestialCoordinate,
    // Visible magnitude
    val mag: Double,
    // List of names. An DSO may have multiple names, e.g., "M42", "NGC1976", "Orion nebula".
    // The order of names in the list is unspecified.
    val names: List<String>
) : Serializable {
    override fun toString(): String {
        val namesString = names.joinToString("/")
        return "DeepSkyEntry(wcs=$cel names=$namesString)"
    }
}


// Parser for deep_sky.csv file. The format is described in the first two lines
// the file
data class WellKnownDsoSet(val entries: ArrayList<WellKnownDso>) : Serializable {
    companion object {
        private val singletonMu = ReentrantLock()
        private var singleton: WellKnownDsoSet? = null
        private val callbacks = ArrayList<() -> Unit>()

        // Registers a function to be called when the singleton
        // WellKnownDsoReader object is loaded. If the reader is already
        // loaded at the point of the call, the  function will be called
        // immediately in a background thread.
        fun registerOnSingletonLoaded(cb: () -> Unit) {
            singletonMu.withLock {
                if (singleton != null) {
                    Thread { cb() }.start()
                    return@withLock
                }
                callbacks.add(cb)
            }
        }

        // Gets the singleton reader object. It returns non-null only after
        // startLoadSingleton call.
        fun getSingleton(): WellKnownDsoSet? {
            singletonMu.withLock {
                return singleton
            }
        }

        // Starts loading the singleton reader object. Idempotent.
        // This function does not block.
        fun startLoadSingleton(assets: AssetManager, cacheDir: File) {
            val findWellKnownDsoFilename = fun(): String {
                val filenames = assets.list("")
                Log.d(TAG, "assets: $filenames")
                for (f in filenames!!) {
                    if (f.startsWith("wellknowndso_")) {
                        return f
                    }
                }
                throw Exception("wellknowndso*.csv not found in assets")
            }
            Thread {
                singletonMu.withLock {
                    val filename = findWellKnownDsoFilename()
                    val cachePath = File(cacheDir, "${filename}.data")
                    singleton = readCache(cachePath)
                    if (singleton == null) {
                        Log.d(TAG, "reading asset $filename")
                        assets.open(filename).use { inputStream ->
                            singleton = parseCsv(inputStream)
                        }
                        writeCache(singleton!!, cachePath)
                        for (cb in callbacks) {
                            cb()
                        }
                        callbacks.clear()
                    }
                }
            }.start()
        }

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

    fun findInRange(
        minRa: Double,
        minDec: Double,
        maxRa: Double,
        maxDec: Double,
    ): ArrayList<WellKnownDso> {
        val hits = ArrayList<WellKnownDso>()
        var n = 0
        // Visit entries in magnitude order (brightest first).
        for (ent in entries) {
            if (ent.cel.ra in minRa..maxRa &&
                ent.cel.dec in minDec..maxDec
            ) {
                hits.add(ent)
                n++
                if (n > MAX_HITS) break
            }
        }
        return hits
    }
}

// Serializes DSOs in a file in a fast binary format.
private fun writeCache(dso: WellKnownDsoSet, cachePath: File) {
    Log.d(TAG, "writeCache: dumping dso objects in $cachePath")
    val bos = ByteArrayOutputStream()
    val out = ObjectOutputStream(bos)
    out.writeObject(dso)
    out.flush()
    writeFileAtomic(cachePath, bos.toByteArray())
    Log.d(TAG, "writeCache: dumped dso objects in $cachePath")
}

// Reads the file created by writeCache. Returns null on any error.
// Never raises exception.
private fun readCache(cachePath: File): WellKnownDsoSet? {
    try {
        FileInputStream(cachePath).use { fd ->
            val stream = ObjectInputStream(BufferedInputStream(fd))
            val dso = stream.readObject() as WellKnownDsoSet
            Log.d(
                TAG,
                "readCache: successfully read dso objects with ${dso.entries.size} entries from $cachePath"
            )
            return dso
        }
    } catch (ex: Exception) {
        Log.d(TAG, "readCache: exception $ex")
        return null
    }
}

// Parses wellknowndso*.csv.
private fun parseCsv(stream: InputStream): WellKnownDsoSet {
    val entries = ArrayList<WellKnownDso>()
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
        entries.add(
            WellKnownDso(
                typ = typ,
                cel = CelestialCoordinate(ra, dec),
                mag = mag,
                names = names
            )
        )
    }
    // Sort entries by magnitude first (brighter first), then
    // star types (non-star objects, such as nebulae are sorted before stars).
    entries.sortWith { a, b ->
        if (a.mag < b.mag) {
            -1
        } else if (a.mag > b.mag) {
            1
        } else if (a.typ != b.typ) {
            if (a.typ == "Star") 1
            else if (b.typ == "Star") -1
            else 0
        } else {
            0
        }
    }
    return WellKnownDsoSet(entries)
}
