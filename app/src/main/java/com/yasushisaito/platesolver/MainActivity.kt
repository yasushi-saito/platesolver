package com.yasushisaito.platesolver

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.*
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.Process
import java.util.zip.ZipFile

const val starDbDir = "stardb"

class MainActivity : AppCompatActivity() {
    private fun getAstapCliPath(): File {
        return File(filesDir, "astap_cli")
    }

    private fun getStarDbDir(): File {
        return File(getExternalFilesDir(null), "stardb")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        println("FILESDIR: ${filesDir}")

        val files = getAssets().list("")
        val inputStream = getAssets().open("astap_cli")
        val outputStream = FileOutputStream(getAstapCliPath())
        inputStream.copyTo(outputStream)
        inputStream.close()
        outputStream.close()
        if (!outputPath.setExecutable(true)) {
            println("FILESDIR: cannot set ${getAstapCliPath()} executable")
        } else {
            println("FILESDIR: successfully set ${getAstapCliPath()} executable")
        }
    }

    private fun newLauncher(cb: (Intent)->Unit) : ActivityResultLauncher<Intent> {
        val launch = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult? ->
            if (result?.resultCode != Activity.RESULT_OK) {
                println("launch activity failed: $result")
                return@registerForActivityResult
            }
            cb(result.data!!)
        }
        return launch
    }

    private val pickFileLauncher = newLauncher { intent: Intent ->
        val uri: Uri = intent.data!!
        println("URI: $uri")
    }

/*    private val pickFileLaunch: = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult? ->
        if (result?.resultCode != Activity.RESULT_OK) {
            println("pick file: $result")
            return@registerForActivityResult
        }
        val intent: Intent = result.data!!
        val uri: Uri = intent.data!!
        println("URI: $uri")
    }*/

    private val installStarDbLauncher = newLauncher { intent: Intent ->
        val eventHandler = Handler(Looper.getMainLooper()){ msg: Message ->
            println("HANDLER $msg")
            true
        }
        Thread(Runnable {
            val uri: Uri = intent.data!!
            println("install URI: $uri")
            val input = contentResolver.openInputStream(uri)
            if (input == null) {
                println("stream null")
                return@Runnable
            }
            val outputPath = File(this.getExternalFilesDir(null), "h17.zip")
            val output = FileOutputStream(outputPath)
            println("stardb: copying $uri to ${outputPath}!!!")
            input.copyTo(output)
            input.close()
            output.close()

            val starDbDir = File(this.getExternalFilesDir(null), starDbDir)
            if (!starDbDir.mkdir()) {
                println("mkdir $starDbDir failed")
                return@Runnable
            }

            val proc: Process = Runtime.getRuntime().exec(
                arrayOf("unzip", outputPath.path),
                null,
                starDbDir)
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                AlertDialog.Builder(this)
                    .setTitle("unzip failed")
                    .setMessage("unzip $outputPath failed")
                    .setPositiveButton("OK") {  _, _ -> }.show()
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

    fun onPickFile(@Suppress("UNUSED_PARAMETER") unused: View) {
        println("start picking file")
        val intent = Intent()
            .setType("*/*")
            .setAction(Intent.ACTION_GET_CONTENT)
        pickFileLauncher.launch(intent)
    }
}