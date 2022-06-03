package com.yasushisaito.platesolver

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.File
import java.io.FileOutputStream

private const val TAG = "File"

// Reports the abs path of the astap_cli executable.
//
// https://docs.microsoft.com/en-us/answers/questions/369506/android-problem-starting-the-program-from-the-shel.html
// https://stackoverflow.com/questions/63800440/android-cant-execute-process-for-android-api-29-android-10-from-lib-arch?utm_source=feedburner&utm_medium=feed&utm_campaign=Feed%3A+stackoverflow%2FOxiZ+%28%5Bandroid+questions%5D%29&utm_content=Google+Feedfetcher
// https://issuetracker.google.com/issues/152645643
// https://stackoverflow.com/questions/64786837/what-path-to-put-executable-to-run-on-android-29
fun getAstapCliPath(context: Context): File {
    var dir = File(context.applicationInfo.nativeLibraryDir)
    var path = File(dir, "libastapcli.so")
    assert(path.exists(), { Log.e(TAG, "astap executable $path does not exist") })
    return path
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
