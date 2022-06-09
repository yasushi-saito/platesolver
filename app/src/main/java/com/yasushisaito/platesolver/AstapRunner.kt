package com.yasushisaito.platesolver

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import java.io.*
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.ArrayList
import kotlin.concurrent.withLock


// Checks if this machine is likely an Android emulator.
// https://stackoverflow.com/questions/2799097/how-can-i-detect-when-an-android-application-is-running-in-the-emulator
private fun isEmulator(): Boolean {
    return (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
        || Build.FINGERPRINT.startsWith("generic")
        || Build.FINGERPRINT.startsWith("unknown")
        || Build.HARDWARE.contains("goldfish")
        || Build.HARDWARE.contains("ranchu")
        || Build.MODEL.contains("google_sdk")
        || Build.MODEL.contains("Emulator")
        || Build.MODEL.contains("Android SDK built for x86")
        || Build.MANUFACTURER.contains("Genymotion")
        || Build.PRODUCT.contains("sdk_google")
        || Build.PRODUCT.contains("google_sdk")
        || Build.PRODUCT.contains("sdk")
        || Build.PRODUCT.contains("sdk_x86")
        || Build.PRODUCT.contains("sdk_gphone64_arm64")
        || Build.PRODUCT.contains("vbox86p")
        || Build.PRODUCT.contains("emulator")
        || Build.PRODUCT.contains("simulator")
}
// Helper for running astap in a separate process.
// Thread safe.
class AstapRunner(
    val context: Context,
    val imageName: String,
    val solverParams: SolverParameters,
    // The destination path of the final .json file
    val solutionJsonPath: File,
    val onMessage: (message: String) -> Unit,
) {
    companion object {
        const val TAG = "AstapRunner"
    }

    data class Result(
        // Message produced by AstapRunner internally.
        // If this field is set, the fields that follow should be ignored
        // since the process may not have started.
        val error: String,
        // Astap process exit code. 0 = success. Value >=128 means death by a signal.
        val exitCode: Int,
        // Contents of the stdout
        val stdout: ByteArray,
        // Contents of the stderr
        val stderr: ByteArray,
    )

    // Starts the astap process. It blocks until the
    // process finishes. If astap finishes successfully,
    // it will create a file at solutionJsonPath.
    fun run(): Result {
        val imagePath = File(solverParams.imagePath)
        val wcsPath = replaceExt(imagePath, ".wcs")
        wcsPath.delete() // delete an old result file if any
        val result = runAstap(solverParams)
        Log.d(TAG, "astap stdout: " + String(result.stdout))
        Log.d(TAG, "astap stderr: " + String(result.stderr))
        if (!wcsPath.exists()) {
            if (result.exitCode != 0) return result
            return Result(
                error = "astap finished successfully, but it did not create file $wcsPath",
                exitCode = 0,
                stdout = byteArrayOf(),
                stderr = byteArrayOf()
            )
        }
        try {
            FileInputStream(wcsPath).use { wcsStream ->
                val wcs = AstapResultReader(wcsStream)

                // Reference pixel coordinate.
                // Typically the middle of the image
                val refPixel =
                    PixelCoordinate(
                        wcs.getDouble(AstapResultReader.CRPIX1),
                        wcs.getDouble(AstapResultReader.CRPIX2)
                    )
                // The corresponding celestial coordinate
                val refCel =
                    CelestialCoordinate(
                        wcs.getDouble(AstapResultReader.CRVAL1),
                        wcs.getDouble(AstapResultReader.CRVAL2)
                    )
                val dim = ImageDimension((refPixel.x * 2).toInt(), (refPixel.y * 2).toInt())
                val pixelToWcsMatrix = Matrix22(
                    wcs.getDouble(AstapResultReader.CD1_1), wcs.getDouble(AstapResultReader.CD1_2),
                    wcs.getDouble(AstapResultReader.CD2_1), wcs.getDouble(AstapResultReader.CD2_2)
                )
                val wcsToPixelMatrix = pixelToWcsMatrix.invert()

                var minRa = Double.MAX_VALUE
                var maxRa = -Double.MAX_VALUE
                var minDec = Double.MAX_VALUE
                var maxDec = -Double.MAX_VALUE

                val updateRange = fun(px: PixelCoordinate) {
                    val cel = convertPixelToCelestial(px, dim, refPixel, refCel, pixelToWcsMatrix)
                    if (minRa > cel.ra) minRa = cel.ra
                    if (maxRa < cel.ra) maxRa = cel.ra
                    if (minDec > cel.dec) minDec = cel.dec
                    if (maxDec < cel.dec) maxDec = cel.dec
                }
                updateRange(PixelCoordinate(0.0, 0.0))
                updateRange(PixelCoordinate(0.0, dim.height.toDouble()))
                updateRange(PixelCoordinate(dim.width.toDouble(), 0.0))
                updateRange(PixelCoordinate(dim.width.toDouble(), dim.height.toDouble()))

                val allMatchedStars =
                    WellKnownDsoSet.getSingleton()!!.findInRange(minRa, minDec, maxRa, maxDec)
                val validMatchedStars = ArrayList<WellKnownDso>()
                // Since the image rectangle may not be aligned with the wcs coordinate system,
                // findInRange will report stars outside the image rectangle. Remove them.
                for (m in allMatchedStars) {
                    val px =
                        convertCelestialToPixel(m.cel, dim, refPixel, refCel, wcsToPixelMatrix)
                    if (px.x < 0.0 || px.x >= dim.width || px.y < 0.0 || px.y >= dim.height) continue
                    validMatchedStars.add(m)
                }

                val solution = Solution(
                    version = Solution.CURRENT_VERSION,
                    params = solverParams,
                    refPixel = refPixel,
                    refWcs = refCel,
                    imageDimension = dim,
                    imageName = imageName,
                    pixelToWcsMatrix = pixelToWcsMatrix,
                    matchedStars = validMatchedStars
                )
                val js = Gson().toJson(solution)
                Log.d(TAG, "writing result to $solutionJsonPath")
                writeFileAtomic(solutionJsonPath, js.toByteArray())
                return result
            }
        } catch (ex: Exception) {
            return Result(
                error = "RunAstap failed with exception $ex",
                exitCode = 0,
                stdout = byteArrayOf(),
                stderr = byteArrayOf()
            )
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
            proc?.destroy()
        }
    }

    private fun runAstap(solverParams: SolverParameters): Result {
        val astapPath = getAstapCliPath(context)
        val cmdline = arrayListOf(
            astapPath.absolutePath,
            "-f", solverParams.imagePath,
            "-d", getStarDbDir(context, STARDB_DEFAULT).path,
            "-fov", solverParams.fovDeg.toString(),
        )

        if (!isEmulator()) {
            // Running astap_cli directly fails with "file not found".
            // We need to start the binary explicitly using the dynamic linker.
            //
            // TODO(saito) figure out why. Maybe the binary is compiled wrong?
            cmdline.add(0, "/system/bin/linker64")
        }
        if (solverParams.startSearch != null) {
            cmdline.add("-ra")
            cmdline.add("%f".format(solverParams.startSearch.ra))
            cmdline.add("-spd")
            cmdline.add("%f".format(solverParams.startSearch.dec + 90.0))
        }
        val cmdlineArray = cmdline.toTypedArray()
        Log.d(TAG, "cmdline=${cmdlineArray.contentToString()}")

        procMu.withLock {
            if (aborted) {
                return@runAstap Result(
                    error = "",
                    exitCode = 143, // emulate SIGTERM
                    stdout = byteArrayOf(),
                    stderr = byteArrayOf()
                )
            }
            proc = ProcessBuilder()
                .command(cmdline)
                .directory(astapPath.parentFile)
                .start()
        }

        val readOutputs = fun(stream: InputStream, out: OutputStream): Thread {
            val thread = Thread {
                val output = StringBuilder()
                try {
                    val scanner = Scanner(stream)
                    while (scanner.hasNextLine()) {
                        val line = scanner.nextLine()
                        output.appendLine(line)
                        out.write("${line}\n".toByteArray())
                        Log.d(TAG, "read: $line")
                        onMessage(output.toString())
                    }
                /*
                    while (true) {
                        val len = stream.read(buf)
                        if (len < 0) break
                        System.out.write(buf, 0, len)
                        out.write(buf)
                    }
                 */
                } catch (e: Exception) {
                    Log.e(TAG, "output reader: $e (ignored)")
                }
            }
            thread.start()
            return thread
        }
        val stdoutBuf = ByteArrayOutputStream()
        val stdoutReaderThread = readOutputs(proc!!.inputStream, stdoutBuf)
        val stderrBuf = ByteArrayOutputStream()
        val stderrReaderThread = readOutputs(proc!!.errorStream, stderrBuf)
        val exitCode = proc!!.waitFor()
        if (exitCode != 0) {
            Log.e(TAG, "exitCode=$exitCode")
        }
        stdoutReaderThread.join()
        stderrReaderThread.join()
        procMu.withLock {
            proc = null
        }
        return Result(
            error = "",
            exitCode = exitCode,
            stdout = stdoutBuf.toByteArray(),
            stderr = stderrBuf.toByteArray()
        )
    }
}

