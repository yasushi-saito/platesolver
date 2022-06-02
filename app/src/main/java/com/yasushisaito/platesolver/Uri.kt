package com.yasushisaito.platesolver

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream

fun copyUriTo(contentResolver: ContentResolver, uri: Uri, destPath: File) {
    val inputStream = contentResolver.openInputStream(uri) ?: throw Exception("copyUriTo: could not open $uri")
    inputStream.use {
        FileOutputStream(destPath).use { outputStream ->
            inputStream.copyTo(outputStream)
        }
    }
}

fun getUriMimeType(contentResolver: ContentResolver, uri: Uri): String? {
    return MimeTypeMap.getSingleton().getExtensionFromMimeType(contentResolver.getType(uri))
}

fun getUriFilename(contentResolver: ContentResolver, uri: Uri): String {
    // https://stackoverflow.com/questions/5568874/how-to-extract-the-file-name-from-uri-returned-from-intent-action-get-content
    var filename = ""
    if (uri.scheme.equals("content")) {
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor.use {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                assert(index >= 0)
                filename = cursor.getString(index)
            }
        }
    }
    if (filename.isEmpty()) {
        val path = uri.path
        if (path != null) {
            val cut = path.lastIndexOf('/')
            if (cut != -1) filename = path.substring(cut + 1)
        }
    }
    return filename
}
