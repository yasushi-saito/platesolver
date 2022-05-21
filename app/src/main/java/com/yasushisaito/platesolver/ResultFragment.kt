package com.yasushisaito.platesolver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import java.io.File

class ResultFragment : Fragment() {
    companion object {
        const val BUNDLE_KEY_SOLUTION_JSON_PATH = "solutionJsonPath"
    }

    lateinit var solution: Solution

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val jsonPath = arguments?.getString(BUNDLE_KEY_SOLUTION_JSON_PATH) ?: throw Error("$BUNDLE_KEY_SOLUTION_JSON_PATH not found")
        solution = readSolution(File(jsonPath))
        println("RESULTFLAG: found solution: $solution")
        return inflater.inflate(R.layout.fragment_result, container, false)
    }
}
