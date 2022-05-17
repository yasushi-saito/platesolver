package com.yasushisaito.platesolver

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.*
import android.view.View
import android.webkit.MimeTypeMap
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.Process
import java.security.MessageDigest

const val EVENT_MESSAGE = 0
const val EVENT_OK = 1
const val EVENT_ERROR = 2

// Remove the extension from the given path.
// If the path's filename is missing '.',
// it returns the path itself.
//
// Example:
//   removeExt(File(x, "bar.jpg") -> File(x, "bar")
fun removeExt(path: File): File {
    val name = path.name
    val i = name.lastIndexOf('.')
    if (i < 0) return path
    val basename = name.substring(0, i)
    return File(path.parent, basename)
}

fun replaceExt(path: File, ext: String): File {
    val basename = removeExt(path)
    return File(basename.parentFile, basename.name + ext)
}

// Computes a sha256 hex digest of the stream contents.
fun inputStreamDigest(stream: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.reset()
    val buf = ByteArray(1024 * 1024)
    while (true) {
        val n = stream.read(buf)
        if (n < 0) break
        digest.update(buf, 0, n)
    }
    val hash = digest.digest()
    return hash.joinToString("") { byte -> "%02x".format(byte) }
}

class MainActivity : AppCompatActivity() {
    // Reports the abs path of the astap_cli executable.
    private fun getAstapCliPath(): File {
        return File(filesDir, "astap_cli")
    }

    // Reports the abs path of the directory containing star DB files, such as h17*.
    private fun getStarDbDir(): File {
        return File(getExternalFilesDir(null), "stardb")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        println("FILESDIR:cli=${getAstapCliPath()}, db=${getStarDbDir()}")

        val astapCliPath = getAstapCliPath()
        assets.open("astap_cli").use { inputStream ->
            FileOutputStream(astapCliPath).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        if (!astapCliPath.setExecutable(true)) {
            println("FILESDIR: cannot set $astapCliPath executable")
        } else {
            println("FILESDIR: successfully set $astapCliPath executable")
        }
    }

    private fun newLauncher(cb: (Intent) -> Unit): ActivityResultLauncher<Intent> {
        val launch =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult? ->
                if (result?.resultCode != Activity.RESULT_OK) {
                    println("launch activity failed: $result")
                    return@registerForActivityResult
                }
                cb(result.data!!)
            }
        return launch
    }

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> }.show()
    }

    private fun runAstap(imagePath: File) {
        val cmdline = arrayOf(
            getAstapCliPath().path,
            "-f", imagePath.path,
            "-d", getStarDbDir().path,
            "-fov", "2"
        )
        println("RUNASTAP: cmdline=${cmdline.contentToString()}")
        val proc: Process = Runtime.getRuntime().exec(cmdline, null, imagePath.parentFile)
        val readOutputs = fun(stream: InputStream) {
            Thread {
                val buf = ByteArray(8192)
                while (true) {
                    val len = stream.read(buf)
                    if (len < 0) break
                    System.out.write(buf, 0, len)
                }
            }.start()
        }
        readOutputs(proc.inputStream)
        readOutputs(proc.errorStream)
        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            println("RUNASTAP: exitcode=$exitCode")
            return
        }
    }

    private fun copyUriTo(uri: Uri, destPath: File) {
        println("Copying $uri to $destPath")
        val inputStream = contentResolver.openInputStream(uri)
        if (inputStream == null) {
            println("could not open $uri")
            return
        }
        inputStream.use {
            FileOutputStream(destPath).use { outputStream ->
                println("stardb: copying $uri to $destPath")
                inputStream.copyTo(outputStream)
            }
        }
        println("Copied $uri to $destPath")
    }

    private val installStarDbLauncher = newLauncher { intent: Intent ->
        val eventHandler = Handler(Looper.getMainLooper()) { msg: Message ->
            println("HANDLER $msg")
            true
        }
        Thread(Runnable {
            val uri: Uri = intent.data!!
            println("install URI: $uri")
            val outputPath = File(this.getExternalFilesDir(null), "h17.zip")
            copyUriTo(uri, outputPath)

            val starDbDir = getStarDbDir()
            if (!starDbDir.mkdirs()) {
                println("mkdir $starDbDir failed")
                return@Runnable
            }

            val proc: Process = Runtime.getRuntime().exec(
                arrayOf("unzip", outputPath.path),
                null,
                starDbDir
            )
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                showErrorDialog("unzip $outputPath failed")
            }
            println("stardb: unzipped to ${outputPath}!!!")
            eventHandler.dispatchMessage(Message.obtain(eventHandler, 0, "DoneDoneDone"))
        }).start()
    }

    fun onInstallStarDb(@Suppress("UNUSED_PARAMETER") unused: View) {
        val intent = Intent()
            .setType("application/zip")
            .setAction(Intent.ACTION_GET_CONTENT)
        installStarDbLauncher.launch(intent)
/*
        println("install stardb")
        val context: Context = this
        val fd = ZipFile(Environment.getExternalStorageDirectory().path + "/Download/h17.zip")
        val dbDir = File(context.getExternalFilesDir(null), "h17")
        if (!dbDir.mkdirs()) {
            AlertDialog.Builder(this)
                .setTitle("Cannot create directory")
                .setMessage("Create directory $dbDir")
                .setPositiveButton("OK") {_, _ ->}.show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("CREATE DIR!!")
            .setMessage("Created directory $dbDir")
            .setPositiveButton("OK") {  _, _ -> }.show()

        for (e in fd.entries()) {
            println("ZIP FILENAME: ${e.name}")
            //val in = fd.getInputStream(e)
            //val appSpecificExternalDir = File(context.getExternalFilesDir(null), filename)
        }
*/
    }


    private val pickFileLauncher = newLauncher { intent: Intent ->
        val eventHandler = Handler(Looper.getMainLooper()) { msg: Message ->
            when (msg.what) {
                EVENT_MESSAGE -> {

                }
                EVENT_OK -> {

                }
                EVENT_ERROR -> {

                }
                else -> {
                    throw Error("Invalid message $msg")
                }
            }
            println("HANDLER $msg")
            true
        }
        val dispatchMessage = fun(what: Int, message: String) {
            eventHandler.dispatchMessage(
                Message.obtain(eventHandler, what, message)
            )
        }
        println("PICKFILE start")
        Thread(Runnable {
            try {
                println("PICKFILE start2")
                val uri: Uri = intent.data!!
                dispatchMessage(EVENT_MESSAGE, "copying file")
                val resolver: ContentResolver = contentResolver
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri))
                val inputStream = resolver.openInputStream(uri)
                if (inputStream == null) {
                    showErrorDialog("cannot open $uri")
                    return@Runnable
                }
                val sha256 = inputStream.use {
                    inputStreamDigest(inputStream)
                }
                val copyPath = File(this.getExternalFilesDir(null), "${sha256}.$ext")
                if (!copyPath.exists()) {
                    copyUriTo(uri, copyPath)
                    dispatchMessage(EVENT_MESSAGE, "copied file")
                } else {
                    dispatchMessage(EVENT_MESSAGE, "file already exists; skipping copying")
                }
                val wcsPath = replaceExt(copyPath, ".wcs")
                if (!wcsPath.exists()) {
                    runAstap(copyPath)
                    if (!wcsPath.exists()) {
                        println("RUNASTAP: file $wcsPath does not exist")
                        return@Runnable
                    }
                }
                val wcs = FileInputStream(wcsPath).use { stream ->
                    Fits(stream)
                }
                println("RUNASTAP: wcs=$wcs")
            } finally {
                dispatchMessage(EVENT_OK, "all done")
            }
        }).start()
    }

    fun onPickFile(@Suppress("UNUSED_PARAMETER") unused: View) {
        println("start picking file")
        val intent = Intent()
            .setType("*/*")
            .setAction(Intent.ACTION_GET_CONTENT)
        pickFileLauncher.launch(intent)
    }
}