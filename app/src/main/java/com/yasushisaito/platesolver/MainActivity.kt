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
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.Process
import java.security.MessageDigest


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
    return File(path.parent, name.substring(0, i))
}

fun replaceExt(path: File, ext: String): File {
    return File(removeExt(path), ext)
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

    private fun runAstap(imageUri: Uri) {
        println("RUNASTAP: uri=$imageUri")
        val cacheDir: File = cacheDir
        val tmpImagePath = File.createTempFile("", ".jpg", cacheDir)
        copyUriTo(imageUri, tmpImagePath)
        // /data/user/0/com.yasushisaito.platesolver/files/astap_cli -f /sdcard/Download/m42.jpg -d /storage/emulated/0/Android/data/com.yasushisaito.platesolver/files/stardb -fov 2
        val proc: Process = Runtime.getRuntime().exec(
            arrayOf(
                getAstapCliPath().path,
                "-f", tmpImagePath.path,
                "-d", getStarDbDir().path,
                "-fov", "2"
            ),
            null,
            getStarDbDir()
        )
        val exitCode = proc.waitFor()
        println("ASTAP: exitcode=$exitCode")
    }

    private fun copyUriTo(uri: Uri, destPath: File) {
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
        val uri: Uri = intent.data!!
        val resolver: ContentResolver = contentResolver
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri))
        val inputStream = resolver.openInputStream(uri)
        if (inputStream == null) {
            showErrorDialog("cannot open $uri")
            return@newLauncher
        }
        val sha256 = inputStream.use {
            inputStreamDigest(inputStream)
        }
        val copyPath = File(this.getExternalFilesDir(null),"${sha256}.$ext")
        copyUriTo(uri, copyPath)
    }

    fun onPickFile(@Suppress("UNUSED_PARAMETER") unused: View) {
        println("start picking file")
        val intent = Intent()
            .setType("*/*")
            .setAction(Intent.ACTION_GET_CONTENT)
        pickFileLauncher.launch(intent)
    }
}