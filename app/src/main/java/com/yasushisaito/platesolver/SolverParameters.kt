package com.yasushisaito.platesolver

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

data class SolverParameters(
    // The user-specified file is copied to an app-specific directory with
    // its contents hash as the filename. imagePath specifies the
    // The image pathname the file's path inside the app-specific directory.
    val imagePath: String,
    // The filename of the original file. Used only for display.
    val imageFilename: String,
    // Assumed field of view of the whole image, in degrees.
    val fovDeg: Double,
    // The (ra, dec) to start searching from.
    val startSearch: CelestialCoordinate?,
    // The database name, e.g. "v17", "h18".
    val dbName: String) {
    fun hashString(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.reset()

        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        dos.writeChars(imagePath)
        dos.writeDouble(fovDeg)
        if (startSearch != null) {
            dos.writeDouble(startSearch.ra)
            dos.writeDouble(startSearch.dec)
        }
        dos.flush()
        digest.update(bos.toByteArray())
        val hash = digest.digest()
        return hash.joinToString("") { byte -> "%02x".format(byte) }
    }
}
