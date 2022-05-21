package com.yasushisaito.platesolver

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.MenuItem
import android.view.View
import android.webkit.MimeTypeMap
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.google.android.material.navigation.NavigationView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


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

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {
    // Reports the abs path of the astap_cli executable.
    private fun getAstapCliPath(): File {
        return File(filesDir, "astap_cli")
    }

    // Reports the abs path of the directory containing star DB files, such as h17*.
    private fun getStarDbDir(): File {
        return File(getExternalFilesDir(null), "stardb")
    }

    private val initializedMu = ReentrantLock()
    private var initialized = false
    private lateinit var cachedDeepSkyObjects: DeepSkyCsv

    private fun getDeepSkyObjects() : DeepSkyCsv {
        initializedMu.withLock {
            if (!initialized) throw Error("blah")
            return cachedDeepSkyObjects
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        val drawer = findViewById<View>(R.id.layout_drawer) as DrawerLayout
        val toggle = ActionBarDrawerToggle(
            this,
            drawer,
            toolbar,
            R.string.nav_open_drawer,
            R.string.nav_close_drawer
        )
        drawer.addDrawerListener(toggle)
        toggle.syncState()

        val navigationView = findViewById<View>(R.id.nav_view) as NavigationView
        navigationView.setNavigationItemSelectedListener(this)

        val fragment = SetupFragment()
        val ft = supportFragmentManager.beginTransaction()
        ft.add(R.id.content_frame, fragment)
        ft.commit()

        Thread(Runnable {
            var csv: DeepSkyCsv
            assets.open("deep_sky.csv").use { inputStream ->
                csv = DeepSkyCsv(inputStream)
            }

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
            println("initialized")
            initializedMu.withLock {
                initialized = true
                cachedDeepSkyObjects = csv
            }
        }).start()
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
                FileInputStream(wcsPath).use { wcsStream ->
                    val wcs = Wcs(wcsStream)
                    val dim = wcs.getImageDimension()
                    var minRa = Double.MAX_VALUE
                    var maxRa = -Double.MAX_VALUE
                    var minDec = Double.MAX_VALUE
                    var maxDec = -Double.MAX_VALUE

                    val updateRange = fun (wcs: WcsCoordinate) {
                        if (minRa > wcs.ra) minRa = wcs.ra
                        if (maxRa < wcs.ra) maxRa = wcs.ra
                        if (minDec > wcs.dec) minDec = wcs.dec
                        if (maxDec < wcs.dec) maxDec = wcs.dec
                    }
                    val wcs00 = wcs.pixelToWcs(PixelCoordinate(0.0, 0.0))
                    updateRange(wcs00)
                    val wcs01 = wcs.pixelToWcs(PixelCoordinate(0.0, dim.height.toDouble()))
                    updateRange(wcs01)
                    val wcs10 = wcs.pixelToWcs(PixelCoordinate(dim.width.toDouble(), 0.0))
                    updateRange(wcs10)
                    val wcs11 = wcs.pixelToWcs(PixelCoordinate(dim.width.toDouble(), dim.height.toDouble()))
                    updateRange(wcs11)
                    /*println("RUNASTAP: 00=${wcs00}")
                    println("RUNASTAP: 01=${wcs01}")
                    println("RUNASTAP: 10=${wcs10}")
                    println("RUNASTAP: 01=${wcs11}")*/
                    val matchedStars = getDeepSkyObjects().findInRange(minRa, minDec, maxRa, maxDec)
                    for (m in matchedStars) {
                        val px = wcs.wcsToPixel(m.wcs)
                        println("RUNASTAP: Match $m at px=$px")
                    }
                }
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

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        val id: Int = item.getItemId()
        println("NAVITEM: $id")
        var fragment: Fragment? = null
        when (id) {
            R.id.nav_result -> fragment = ResultFragment()
            R.id.nav_setup -> fragment = SetupFragment()
            else -> throw Error("Invalid menu item: ${id}")
        }
        if (fragment != null) {
            val ft = supportFragmentManager.beginTransaction()
            ft.replace(R.id.content_frame, fragment)
            ft.commit()
        }
        val drawer = findViewById<View>(R.id.layout_drawer) as DrawerLayout
        drawer.closeDrawer(GravityCompat.START)
        return true
    }
}