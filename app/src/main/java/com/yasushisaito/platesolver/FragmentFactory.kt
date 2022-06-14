package com.yasushisaito.platesolver

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager

private const val TAG = "FragmentFactory"

// Fragment.argument bundle keys
//
// Used by ResultFragment to set the *.json file that contains
// the astap solution. The value is a string.
const val FRAGMENT_ARG_SOLUTION_JSON_PATH = "solutionJsonPath"

// List of fragment types.
enum class FragmentType {
    Result,
    RunAstap,
    Settings
}

fun getFragmentType(frag: Fragment): FragmentType {
    return when (frag) {
        is ResultFragment -> FragmentType.Result
        is RunAstapFragment -> FragmentType.RunAstap
        is SettingsFragment -> FragmentType.Settings
        else -> {
            throw Exception("Unknown fragment $frag")
        }
    }
}

fun findOrCreateFragment(ft: FragmentManager, type: FragmentType, args: Bundle?): Fragment {
    Log.d(TAG, "findOrCreateFrag: type $type args $args")
    for (frag in ft.fragments) {
        if (frag == null || getFragmentType(frag) != type) continue
        val gotJsonPath = args?.getString(FRAGMENT_ARG_SOLUTION_JSON_PATH)
        val wantJsonPath = frag.arguments?.getString(FRAGMENT_ARG_SOLUTION_JSON_PATH)
        if (gotJsonPath != wantJsonPath) continue
        return frag
    }
    val frag = when (type) {
        FragmentType.RunAstap -> RunAstapFragment()
        FragmentType.Result -> ResultFragment()
        FragmentType.Settings -> SettingsFragment()
    }
    if (args != null) frag.arguments = args
    return frag
}
