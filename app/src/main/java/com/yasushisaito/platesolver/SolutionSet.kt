package com.yasushisaito.platesolver

import android.util.Log
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SolutionSet(private val solutionDir: File) {
    companion object {
        private val singletonMu = ReentrantLock()
        private var singleton: SolutionSet? = null
        private var singletonSolutionDir: File? = null
        fun getSingleton(solutionDir: File): SolutionSet {
            singletonMu.withLock {
                assert(singletonSolutionDir == null ||
                        singletonSolutionDir!!.absolutePath == solutionDir.absolutePath)
                if (singleton == null) {
                    singletonSolutionDir = solutionDir
                    singleton = SolutionSet(solutionDir)
                }
            }
            return singleton!!
        }
    }
    data class Entry(
        val fileName: String,
        // negative cache entry. If invalid, all other fields are invalid
        val invalid: Boolean,
        // The time the solution was created, as millisec from Unix epoch
        val modTime: Long,
        // Set iff. !invalid
        val solution: Solution?
    )

    // keys are filenames under solutionDir
    private val mu = ReentrantLock()
    private val entriesMap = HashMap<String, Entry>()

    init {
        Log.d(TAG, "REFRESHING")
        refresh()
    }

    // Lists the valid entries in entriesMap. Sorted by modtime (newest first), then filename.
    fun refresh() : ArrayList<Entry> {
        return mu.withLock {
            val fsEntriesMap = HashSet<String>()
            for (fileName in solutionDir.list() ?: arrayOf()) {
                fsEntriesMap.add(fileName)
                if (fileName !in entriesMap) {
                    try {
                        val path = File(solutionDir, fileName)
                        val solution = readSolution(path)
                        entriesMap[fileName] = Entry(
                            fileName = fileName,
                            invalid = false,
                            modTime = path.lastModified(),
                            solution = solution
                        )
                        Log.d(TAG, "successfully added $fileName")
                    } catch (ex: Exception) {
                        entriesMap[fileName] = Entry(
                            fileName = fileName,
                            invalid = true,
                            modTime = 0,
                            solution = null
                        )
                        Log.d(TAG, "could not parse $fileName")
                    }
                }
            }
            entriesMap.keys.removeIf { it !in fsEntriesMap }
            // Sync entriesMap and entriesList
            val entriesList = ArrayList<Entry>()
            for (e in entriesMap.entries.iterator()) {
                if (!e.value.invalid) entriesList.add(e.value)
            }
            // Sort newest first.
            entriesList.sortWith { a, b: Entry -> Int
                if (a.modTime > b.modTime) -1
                else if (a.modTime < b.modTime) 1
                else if (a.fileName < b.fileName) -1
                else if (a.fileName > b.fileName) 1
                else 0
            }
            return entriesList
        }
    }
}