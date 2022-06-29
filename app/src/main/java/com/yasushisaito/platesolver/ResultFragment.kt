package com.yasushisaito.platesolver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.io.File
import java.time.Instant

// Fragment for showing a solution.
class ResultFragment : Fragment() {
    private lateinit var solution: Solution

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val jsonPath = arguments?.getString(FRAGMENT_ARG_SOLUTION_JSON_PATH)
            ?: throw Exception("$FRAGMENT_ARG_SOLUTION_JSON_PATH not found")
        solution = readSolution(File(jsonPath))
        return inflater.inflate(R.layout.fragment_result, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val annotatedImageView = view.findViewById<AnnotatedImageView>(R.id.view_annotatedimage)
        annotatedImageView.setSolution(solution)
        val setText = fun(viewId: Int, value: String) {
            view.findViewById<TextView>(viewId).text = value
        }

        val imagePath = File(solution.params.imagePath)
        val modTime = Instant.ofEpochMilli(imagePath.lastModified())
        setText(R.id.text_result_imagelastupdate, modTime.toString())
        setText(R.id.text_result_imagename, solution.params.imageFilename)
        setText(R.id.text_result_fov_deg, "%.02f".format(solution.trueFovDeg))

        class SeekBarListener : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                annotatedImageView.setMatchedStarsDisplayFraction(progress.toDouble() / 100.0)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

        val seekbar = view.findViewById<SeekBar>(R.id.seekbar_result_matchedstars)
        seekbar.setOnSeekBarChangeListener(SeekBarListener())
    }
}
