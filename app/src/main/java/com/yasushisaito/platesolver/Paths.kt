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

