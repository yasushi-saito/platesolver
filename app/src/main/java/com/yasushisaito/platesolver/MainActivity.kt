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
import com.google.gson.Gson
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.io.DataOutputStream as DataOutputStream1


const val EVENT_MESSAGE = 0
const val EVENT_OK = 1
const val EVENT_ERROR = 2
const val EVENT_SHOW_SOLUTION = 3

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

// Writes the given contents to the file. It guarantees that
// either the file is created with the full contents, or the file does
// not exist.
fun writeFileAtomic(path: File, contents: String) {
    path.delete()
    val tmpPath = File.createTempFile("tmp", "tmp", path.parentFile)
    FileOutputStream(tmpPath).use { stream ->
        stream.write(contents.toByteArray())
    }
    tmpPath.renameTo(path)
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

    private fun runAstap(imagePath: File, fovDeg: Double) {
        val cmdline = arrayOf(
            getAstapCliPath().path,
            "-f", imagePath.path,
            "-d", getStarDbDir().path,
            "-fov", fovDeg.toString(),
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

    val eventHandler = Handler(Looper.getMainLooper()) { msg: Message ->
        println("EVENTHANDLER: what=${msg.what} msg=${msg.obj}")
        when (msg.what) {
            EVENT_MESSAGE -> {

            }
            EVENT_OK -> {

            }
            EVENT_ERROR -> {

            }
            EVENT_SHOW_SOLUTION ->                 startSolutionFragment(msg.obj as String)
            else ->                 throw Error("Invalid message $msg")
        }
        println("HANDLER $msg")
        true
    }

    private val pickFileLauncher = newLauncher { intent: Intent ->
        val dispatchMessage = fun(what: Int, message: String) {
            eventHandler.dispatchMessage(
                Message.obtain(eventHandler, what, message)
            )
        }
        Thread(Runnable {
            try {
                println("PICKFILE start2")
                val fovDeg: Double = 2.0
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
                val imagePath = File(this.getExternalFilesDir(null), "${sha256}.$ext")
                val solverParams = SolverParameters(imagePath.absolutePath, fovDeg)
                val solutionJsonPath = File(this.getExternalFilesDir(null), "${solverParams.hashString()}.json")
                var solution: Solution? = null
                try {
                    solution = readSolution(solutionJsonPath)
                } catch (e: Exception) {
                    println("readSolution: could not read cached solution in $solutionJsonPath: $e. Running astap")
                }

                if (solution == null) {
                    if (!imagePath.exists()) {
                        copyUriTo(uri, imagePath)
                        dispatchMessage(EVENT_MESSAGE, "copied file")
                    } else {
                        dispatchMessage(EVENT_MESSAGE, "file already exists; skipping copying")
                    }

                    val wcsPath = replaceExt(imagePath, ".wcs")
                    wcsPath.delete() // delete an old result file if any
                    runAstap(imagePath, fovDeg)
                    if (!wcsPath.exists()) {
                        println("RUNASTAP: file $wcsPath does not exist")
                        return@Runnable
                    }
                    FileInputStream(wcsPath).use { wcsStream ->
                        val wcs = Wcs(wcsStream)

                        // Reference pixel coordinate.
                        // Typically the middle of the image
                        val refPixel = PixelCoordinate(wcs.getDouble(Wcs.CRPIX1), wcs.getDouble(Wcs.CRPIX2))
                        // The corresponding astrometric coordinate
                        val refWcs = WcsCoordinate(wcs.getDouble(Wcs.CRVAL1), wcs.getDouble(Wcs.CRVAL2))
                        val dim = Wcs.ImageDimension((refPixel.x*2).toInt(), (refPixel.y*2).toInt())
                        val pixelToWcsMatrix = Matrix22(
                            wcs.getDouble(Wcs.CD1_1), wcs.getDouble(Wcs.CD1_2),
                            wcs.getDouble(Wcs.CD2_1), wcs.getDouble(Wcs.CD2_2)
                        )
                        val wcsToPixelMatrix = pixelToWcsMatrix.invert()

                        var minRa = Double.MAX_VALUE
                        var maxRa = -Double.MAX_VALUE
                        var minDec = Double.MAX_VALUE
                        var maxDec = -Double.MAX_VALUE

                        val updateRange = fun (px: PixelCoordinate) {
                            val wcs = convertPixelToWcs(px,                           dim, refPixel, refWcs, pixelToWcsMatrix)
                            if (minRa > wcs.ra) minRa = wcs.ra
                            if (maxRa < wcs.ra) maxRa = wcs.ra
                            if (minDec > wcs.dec) minDec = wcs.dec
                            if (maxDec < wcs.dec) maxDec = wcs.dec
                        }
                        updateRange(PixelCoordinate(0.0, 0.0))
                        updateRange(PixelCoordinate(0.0, dim.height.toDouble()))
                        updateRange(PixelCoordinate(dim.width.toDouble(), 0.0))
                        updateRange(PixelCoordinate(dim.width.toDouble(), dim.height.toDouble()))

                        val allMatchedStars = getDeepSkyObjects().findInRange(minRa, minDec, maxRa, maxDec)
                        val validMatchedStars = ArrayList<DeepSkyEntry>()
                        // Since the image rectangle may not be aligned with the wcs coordinate system,
                        // findInRange will report stars outside the image rectangle. Remove them.
                        for (m in allMatchedStars) {
                            val px = convertWcsToPixel(m.wcs, dim, refPixel, refWcs, wcsToPixelMatrix)
                            if (px.x < 0.0 || px.x >= dim.width || px.y < 0.0 || px.y >= dim.height) continue
                            println("MATCH: $m")
                            validMatchedStars.add(m)
                        }

                        solution = Solution(
                            params=solverParams,
                            refPixel = refPixel,
                            refWcs=refWcs,
                            imageDimension = dim,
                            pixelToWcsMatrix = pixelToWcsMatrix,
                            matchedStars=validMatchedStars
                        )
                        val js = Gson().toJson(solution)
                        println("RUNASTAP: write result to $solutionJsonPath")
                        writeFileAtomic(solutionJsonPath, js)
                    }
                }
                if (solution == null) {
                    throw Error("could not build solution")
                }
                dispatchMessage(EVENT_SHOW_SOLUTION, solutionJsonPath.absolutePath)
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

    fun startSolutionFragment(solutionJsonPath: String) {
        println("RUNASTAP: start solution frag: $solutionJsonPath")
        val bundle = Bundle()
        bundle.putString(ResultFragment.BUNDLE_KEY_SOLUTION_JSON_PATH, solutionJsonPath)
        val fragment = ResultFragment()
        fragment.setArguments(bundle)
        val ft = supportFragmentManager.beginTransaction()
        ft.replace(R.id.content_frame, fragment)
        ft.commit()
    }
}