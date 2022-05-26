package com.yasushisaito.platesolver

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
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
import java.io.*
import java.security.MessageDigest


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
    companion object {
        const val TAG = "MainActivity"
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

        WellKnownDsoReader.startLoadSingleton(assets)

        Thread(Runnable {
            val astapCliPath = getAstapCliPath(this)
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
            Log.d(TAG, "initialized")
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

    private val installStarDbLauncher = newLauncher { intent: Intent ->
        Thread(Runnable {
            val uri: Uri = intent.data!!
            println("install URI: $uri")
            val outputPath = File(this.getExternalFilesDir(null), "h17.zip")
            copyUriTo(contentResolver, uri, outputPath)

            val starDbDir = getStarDbDir(this)
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
        }).start()
    }

    fun onInstallStarDb(@Suppress("UNUSED_PARAMETER") unused: View) {
        val intent = Intent()
            .setType("application/zip")
            .setAction(Intent.ACTION_GET_CONTENT)
        installStarDbLauncher.launch(intent)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        val id: Int = item.itemId
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