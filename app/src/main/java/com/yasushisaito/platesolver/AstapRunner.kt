package com.yasushisaito.platesolver

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


// Remove the extension from the given path.
// If the path's filename is missing '.',
// it returns the path itself.
//
// Example:
//   removeExt(File(x, "bar.jpg") -> File(x, "bar")
private fun removeExt(path: File): File {
    val name = path.name
    val i = name.lastIndexOf('.')
    if (i < 0) return path
    val basename = name.substring(0, i)
    return File(path.parent, basename)
}

private fun replaceExt(path: File, ext: String): File {
    val basename = removeExt(path)
    return File(basename.parentFile, basename.name + ext)
}

// Helper for running astap in a separate process.
// Thread safe.
class AstapRunner(
    val context: Context,
    val imageName: String,
    val solverParams: SolverParameters,
    // The destination path of the final .json file
    val solutionJsonPath: File,
    // onMessage will update the dialog status line.
    val onMessage: (message: String) -> Unit,
    // onMessage will update the dialog status line with an error color.
    val onError: (message: String) -> Unit,
) {
    companion object {
        const val TAG = "AstapRunner"
    }

    // Starts the astap process. It blocks until the
    // process finishes. If astap finishes successfully,
    // it will create a file at solutionJsonPath.
    fun run() {
        val imagePath = File(solverParams.imagePath)
        val wcsPath = replaceExt(imagePath, ".wcs")
        wcsPath.delete() // delete an old result file if any
        onMessage("Running astap...")
        runAstap(solverParams)
        if (!wcsPath.exists()) {
            onError("runastap: file $wcsPath does not exist")
            return
        }
        FileInputStream(wcsPath).use { wcsStream ->
            val wcs = Wcs(wcsStream)

            // Reference pixel coordinate.
            // Typically the middle of the image
            val refPixel =
                PixelCoordinate(wcs.getDouble(Wcs.CRPIX1), wcs.getDouble(Wcs.CRPIX2))
            // The corresponding astrometric coordinate
            val refWcs =
                WcsCoordinate(wcs.getDouble(Wcs.CRVAL1), wcs.getDouble(Wcs.CRVAL2))
            val dim =
                Wcs.ImageDimension((refPixel.x * 2).toInt(), (refPixel.y * 2).toInt())
            val pixelToWcsMatrix = Matrix22(
                wcs.getDouble(Wcs.CD1_1), wcs.getDouble(Wcs.CD1_2),
                wcs.getDouble(Wcs.CD2_1), wcs.getDouble(Wcs.CD2_2)
            )
            val wcsToPixelMatrix = pixelToWcsMatrix.invert()

            var minRa = Double.MAX_VALUE
            var maxRa = -Double.MAX_VALUE
            var minDec = Double.MAX_VALUE
            var maxDec = -Double.MAX_VALUE

            val updateRange = fun(px: PixelCoordinate) {
                val wcs = convertPixelToWcs(px, dim, refPixel, refWcs, pixelToWcsMatrix)
                if (minRa > wcs.ra) minRa = wcs.ra
                if (maxRa < wcs.ra) maxRa = wcs.ra
                if (minDec > wcs.dec) minDec = wcs.dec
                if (maxDec < wcs.dec) maxDec = wcs.dec
            }
            updateRange(PixelCoordinate(0.0, 0.0))
            updateRange(PixelCoordinate(0.0, dim.height.toDouble()))
            updateRange(PixelCoordinate(dim.width.toDouble(), 0.0))
            updateRange(PixelCoordinate(dim.width.toDouble(), dim.height.toDouble()))

            val allMatchedStars =
                DeepSkyCsv.getSingleton()!!.findInRange(minRa, minDec, maxRa, maxDec)
            val validMatchedStars = ArrayList<DeepSkyEntry>()
            // Since the image rectangle may not be aligned with the wcs coordinate system,
            // findInRange will report stars outside the image rectangle. Remove them.
            for (m in allMatchedStars) {
                val px =
                    convertWcsToPixel(m.wcs, dim, refPixel, refWcs, wcsToPixelMatrix)
                if (px.x < 0.0 || px.x >= dim.width || px.y < 0.0 || px.y >= dim.height) continue
                validMatchedStars.add(m)
            }

            val solution = Solution(
                params = solverParams,
                refPixel = refPixel,
                refWcs = refWcs,
                imageDimension = dim,
                imageName = imageName,
                pixelToWcsMatrix = pixelToWcsMatrix,
                matchedStars = validMatchedStars
            )
            val js = Gson().toJson(solution)
            Log.d(TAG, "runastap: write result to $solutionJsonPath")
            writeFileAtomic(solutionJsonPath, js)
        }
    }

    private var procMu = ReentrantLock()
    private var proc: Process? = null
    private var aborted = false

    // Aborts the astap process. If the process hasn't started yet,
    // it tells run() not to start the process.
    // If the process has terminated already, this function is a noop.
    fun abort() {
        procMu.withLock {
            aborted = true
            proc?.let { it.destroy() }
        }
    }

    private fun runAstap(solverParams: SolverParameters) {
        val imagePath = File(solverParams.imagePath)
        val cmdline = arrayOf(
            getAstapCliPath(context).path,
            "-f", solverParams.imagePath,
            "-d", getStarDbDir(context).path,
            "-fov", solverParams.fovDeg.toString(),
        )
        Log.d(TAG, "runastap: cmdline=${cmdline.contentToString()}")

        procMu.withLock {
            if (aborted) return@runAstap
            proc = Runtime.getRuntime().exec(cmdline, null, imagePath.parentFile)
        }

        val readOutputs = fun(stream: InputStream) {
            Thread {
                val buf = ByteArray(8192)
                try {
                    while (true) {
                        val len = stream.read(buf)
                        if (len < 0) break
                        System.out.write(buf, 0, len)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "output reader: $e (ignored)")
                }
            }.start()
        }
        readOutputs(proc!!.inputStream)
        readOutputs(proc!!.errorStream)
        val exitCode = proc!!.waitFor()
        if (exitCode != 0) {
            Log.e(TAG, "runastap: exitcode=$exitCode")
        }
        procMu.withLock {
            proc = null
        }
    }
}