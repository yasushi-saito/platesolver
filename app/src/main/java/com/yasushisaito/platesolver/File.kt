package com.yasushisaito.platesolver

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

// Reports the abs path of the astap_cli executable.
fun getAstapCliPath(context: Context): File {
    return File(context.filesDir, "astap_cli")
}

const val STARDB_DEFAULT = "h17"
const val STARDB_ANY = STARDB_DEFAULT

// Reports the abs path of the directory containing star DB files, such as h17*.
fun getStarDbDir(context: Context, name: String): File {
    //return File(context.getExternalFilesDir(null), "stardb")
    return File(context.getExternalFilesDir(null), name)
}

fun isStarDbInstalled(context: Context, dbName: String): Boolean {
    try {
        val readyPath = getStarDbReadyPath(context, dbName)
        val data = String(readyPath.readBytes())
        return (data == "ready")
    } catch (ex: Exception) {
        Log.d(TAG, "isStardbExpanded: exception $ex")
    }
    return false
}

// A marker file that's created after a star DB (h17, etc) is successfully installed.
fun getStarDbReadyPath(context: Context, dbName: String): File {
    val dir = getStarDbDir(context, dbName)
    return File(dir, "ready.txt")
}

// Reports the directory for json files that store Solution objects.
fun getSolutionDir(context: Context): File {
    return File(context.getExternalFilesDir(null), "solutions")
}

fun getWellKnownDsoCacheDir(context: Context): File {
    return File(context.getExternalFilesDir(null), "wellknowndso")
}

// Writes the given contents to the file. It guarantees that
// either the file is created with the full contents, or the file does
// not exist.
fun writeFileAtomic(path: File, contents: ByteArray) {
    path.delete()
    path.parentFile?.mkdirs()
    val tmpPath = File.createTempFile("tmp", "tmp", path.parentFile)
    FileOutputStream(tmpPath).use { stream ->
        stream.write(contents)
    }
    tmpPath.renameTo(path)
}
