package com.yasushisaito.platesolver

import android.os.Bundle
import androidx.fragment.app.Fragment

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

fun newFragment(type: FragmentType): Fragment {
    val frag = when (type) {
        FragmentType.RunAstap -> RunAstapFragment()
        FragmentType.Result -> ResultFragment()
        FragmentType.Settings -> SettingsFragment()
    }
    return frag
}
