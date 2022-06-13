package com.yasushisaito.platesolver

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
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
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant

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
        const val SOLUTION_MENU_ITEM_ID: Int = 0x4444455
    }

    private lateinit var drawerMenuView: NavigationView
    private lateinit var pastSolutionsSubmenu: SubMenu
    private lateinit var solutionSet: SolutionSet

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        var n = 0
        for (frag in supportFragmentManager.fragments) {
            if (frag != null && frag.isVisible()) {
                val type = getFragmentType(frag)
                outState.putString("FRAG_${n}", type.name)
                frag.arguments?.let {
                    outState.putBundle("ARG_${n}", it)
                }
                n++
            }
            outState.putInt("N_FRAGS", n)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
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

        drawerMenuView = findViewById(R.id.nav_view)
        drawerMenuView.setNavigationItemSelectedListener(this)
        pastSolutionsSubmenu = drawerMenuView.menu.addSubMenu("Past Solutions")
        solutionSet = SolutionSet.getSingleton(getSolutionDir(this))
        refreshSolutionMenuItems()

        if (state != null) {
            val n = state.getInt("N_FRAGS")
            val ft = supportFragmentManager.beginTransaction()
            for (i in 0 until n) {
                val fragName = state.getString("FRAG_${i}")!!
                val frag = newFragment(FragmentType.valueOf(fragName))
                state.getBundle("ARG_${i}").let {
                    frag.arguments = it
                }
                ft.add(R.id.content_frame, frag, fragName)
            }
        } else {
            val ft = supportFragmentManager.beginTransaction()
            val frag = when {
                isStarDbInstalled(this, STARDB_ANY) -> RunAstapFragment()
                else -> SettingsFragment()
            }
            ft.replace(R.id.content_frame, frag, getFragmentType(frag).name)
            ft.commit()
        }

        WellKnownDsoSet.startLoadSingleton(assets, getWellKnownDsoCacheDir(this))
    }

    fun refreshSolutionMenuItems() {
        val addSolutionMenuItem = fun(e: SolutionSet.Entry) {
            val modTime = Instant.ofEpochMilli(e.modTime)
            val text ="${e.solution!!.imageName}\n${modTime}"
            pastSolutionsSubmenu.add(
                Menu.NONE,
                SOLUTION_MENU_ITEM_ID + e.id,
                Menu.CATEGORY_SECONDARY,
                text
            )
        }
        pastSolutionsSubmenu.removeGroup(Menu.NONE)
        Thread {
            val entries = solutionSet.refresh()
            runOnUiThread {
                for (e in entries) {
                    addSolutionMenuItem(e)
                }
            }
        }.start()
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
        val fragment: Fragment? = when {
            item.itemId == R.id.nav_run_astap -> RunAstapFragment()
            item.itemId == R.id.nav_settings -> SettingsFragment()
            item.itemId >= SOLUTION_MENU_ITEM_ID && item.itemId < SOLUTION_MENU_ITEM_ID + SolutionSet.MAX_ENTRIES -> {
                val id = item.itemId - SOLUTION_MENU_ITEM_ID
                val e = solutionSet.findWithId(id)
                if (e == null) {
                    Log.e(TAG, "no solution found with id $id")
                    val dialog = AlertDialog.Builder(this)
                        .setTitle("Error")
                        .setMessage("No solution found with id $id")
                        .create()
                    dialog.show()
                    null
                } else {
                    val bundle = Bundle()
                    bundle.putString(ResultFragment.BUNDLE_KEY_SOLUTION_JSON_PATH, e.jsonPath.absolutePath)
                    val fragment = ResultFragment()
                    fragment.arguments = bundle
                    fragment
                }
            }
            else -> null
        }
        if (fragment == null) return true

        val ft = supportFragmentManager.beginTransaction()
        ft.replace(R.id.content_frame, fragment)
        ft.commit()

        val drawer = findViewById<View>(R.id.layout_drawer) as DrawerLayout
        drawer.closeDrawer(GravityCompat.START)
        return true
    }
}
