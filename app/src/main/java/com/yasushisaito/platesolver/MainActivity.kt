package com.yasushisaito.platesolver

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
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
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

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
        const val SOLUTION_SUBMENU_ITEM_ID: Int = 0x4444454
        const val SOLUTION_MENU_ITEM_ID: Int = 0x4444455
    }

    private lateinit var drawerMenuView: NavigationView
    private lateinit var solutionsSubmenu: SubMenu

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

        drawerMenuView = findViewById<NavigationView>(R.id.nav_view)
        drawerMenuView.setNavigationItemSelectedListener(this)
        solutionsSubmenu = drawerMenuView.menu.addSubMenu("Results")

        updateSolutionMenuItems()

        val fragment = when {
            isStarDbInstalled(this, STARDB_ANY) -> {
                RunAstapFragment()
            }
            else -> {
                SettingsFragment()
            }
        }
        val ft = supportFragmentManager.beginTransaction()
        ft.add(R.id.content_frame, fragment)
        ft.commit()

        WellKnownDsoSet.startLoadSingleton(assets, getWellKnownDsoCacheDir(this))

        Thread {
            val astapCliPath = getAstapCliPath(this)
            assets.open("astap_cli").use { inputStream ->
                FileOutputStream(astapCliPath).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            if (!astapCliPath.setExecutable(true)) {
                throw Exception("cannot set $astapCliPath executable")
            }
            Log.d(TAG, "successfully set $astapCliPath executable")
        }.start()
    }

    private fun updateSolutionMenuItems() {
        Log.d(TAG, "UPDATESOLUTION")
        val addSolutionMenuItem = fun(name: String) {
            solutionsSubmenu.add(
                Menu.NONE,
                SOLUTION_MENU_ITEM_ID,
                Menu.CATEGORY_SECONDARY,
                name
            )
        }
        solutionsSubmenu.removeGroup(Menu.NONE)
        Log.d(TAG, "UPDATESOLUTION2")
        val pastSolutions = SolutionSet.getSingleton(getSolutionDir(this))
        Log.d(TAG, "UPDATESOLUTION3")
        for (e in pastSolutions.refresh()) {
            addSolutionMenuItem(e.solution!!.imageName)
        }
        Log.d(TAG, "UPDATESOLUTION4")
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

            val starDbDir = getStarDbDir(this, STARDB_DEFAULT)
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
        val fragment: Fragment = when (val id: Int = item.itemId) {
            /*R.id.nav_result -> ResultFragment()*/
            R.id.nav_run_astap -> RunAstapFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> throw Exception("Invalid menu item: $id")
        }
        val ft = supportFragmentManager.beginTransaction()
        ft.replace(R.id.content_frame, fragment)
        ft.commit()

        val drawer = findViewById<View>(R.id.layout_drawer) as DrawerLayout
        drawer.closeDrawer(GravityCompat.START)
        return true
    }
}
