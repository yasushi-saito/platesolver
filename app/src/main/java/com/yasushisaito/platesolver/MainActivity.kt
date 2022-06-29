package com.yasushisaito.platesolver

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
import android.view.View
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.google.android.material.navigation.NavigationView
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
            if (frag != null && frag.isVisible) {
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
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                for (i in 0 until n) {
                    val fragName = state.getString("FRAG_${i}")!!
                    val args = state.getBundle("ARG_${i}")
                    val frag = findOrCreateFragment(
                        supportFragmentManager,
                        FragmentType.valueOf(fragName),
                        args
                    )
                    replace(R.id.content_frame, frag, fragName)
                }
            }
        } else {
            val anyDbInstalled = (
                    isStarDbInstalled(this, STARDB_H17) ||
                    isStarDbInstalled(this, STARDB_H18) ||
                    isStarDbInstalled(this, STARDB_V17))
            val frag = when {
                anyDbInstalled-> findOrCreateFragment(supportFragmentManager, FragmentType.RunAstap, null)
                else -> findOrCreateFragment(supportFragmentManager, FragmentType.Settings, null)
            }
            val fragName = getFragmentType(frag).name
            supportFragmentManager.commit {
                replace(R.id.content_frame, frag, fragName)
                setReorderingAllowed(true)
                addToBackStack(fragName)
            }
        }

        WellKnownDsoSet.startLoadSingleton(assets, getWellKnownDsoCacheDir(this))
    }

    fun refreshSolutionMenuItems() {
        val addSolutionMenuItem = fun(e: SolutionSet.Entry) {
            val modTime = Instant.ofEpochMilli(e.modTime)
            val text ="${e.solution!!.params.imageFilename}\n${modTime}"
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

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        val fragment: Fragment? = when {
            item.itemId == R.id.nav_run_astap -> findOrCreateFragment(supportFragmentManager, FragmentType.RunAstap, null)
            item.itemId == R.id.nav_settings -> findOrCreateFragment(supportFragmentManager, FragmentType.Settings, null)
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
                    bundle.putString(FRAGMENT_ARG_SOLUTION_JSON_PATH, e.jsonPath.absolutePath)
                    val fragment = findOrCreateFragment(supportFragmentManager, FragmentType.Result, bundle)
                    fragment
                }
            }
            else -> null
        }
        if (fragment == null) return true

        val fragName = getFragmentType(fragment).name
        supportFragmentManager.commit {
            replace(R.id.content_frame, fragment, fragName)
            setReorderingAllowed(true)
            addToBackStack(fragName)
        }

        val drawer = findViewById<View>(R.id.layout_drawer) as DrawerLayout
        drawer.closeDrawer(GravityCompat.START)
        return true
    }
}
