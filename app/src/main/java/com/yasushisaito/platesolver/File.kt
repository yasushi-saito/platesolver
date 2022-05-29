package com.yasushisaito.platesolver

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream

// Reports the abs path of the astap_cli executable.
fun getAstapCliPath(context: Context): File {
    return File(context.filesDir, "astap_cli")
}

// Reports the abs path of the directory containing star DB files, such as h17*.
fun getStarDbDir(context: Context): File {
    return File(context.getExternalFilesDir(null), "stardb")
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
    path.parentFile.mkdirs()
    val tmpPath = File.createTempFile("tmp", "tmp", path.parentFile)
    FileOutputStream(tmpPath).use { stream ->
        stream.write(contents)
    }
    tmpPath.renameTo(path)
}
