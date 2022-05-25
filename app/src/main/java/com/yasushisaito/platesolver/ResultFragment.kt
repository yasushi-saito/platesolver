package com.yasushisaito.platesolver

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment
import java.io.File
import java.time.Instant

class ResultFragment : Fragment() {
    companion object {
        const val BUNDLE_KEY_SOLUTION_JSON_PATH = "solutionJsonPath"
    }

    private lateinit var solution: Solution

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val jsonPath = arguments?.getString(BUNDLE_KEY_SOLUTION_JSON_PATH)
            ?: throw Error("$BUNDLE_KEY_SOLUTION_JSON_PATH not found")
        solution = readSolution(File(jsonPath))
        println("RESULTFLAG: found solution: $solution")
        return inflater.inflate(R.layout.fragment_result, container, false)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val annotatedImageView = view.findViewById<AnnotatedImageView>(R.id.view_annotatedimage)
        annotatedImageView.setSolution(solution)
        val setText = fun(viewId: Int, value: String) {
            var text = view.findViewById<TextView>(viewId)
            text.setText(value)
        }

        val setCoord = fun(px: Double, py: Double, viewId: Int) {
            val wcsCoord = solution.pixelToWcs(PixelCoordinate(px, py))
            setText(viewId, "ra:%.3f dec:%.3f".format(wcsCoord.ra, wcsCoord.dec))
        }
        setText(
            R.id.text_result_imagedimension,
            "%d*%d".format(solution.imageDimension.width, solution.imageDimension.height)
        )
        val imagePath = File(solution.params.imagePath)
        val modTime = Instant.ofEpochMilli(imagePath.lastModified())
        setText(R.id.text_result_imagelastupdate, modTime.toString())
        setText(R.id.text_result_imagename, solution.imageName)
        setText(R.id.text_result_fov_deg, "%.02f".format(solution.params.fovDeg))
        setCoord(0.0, 0.0, R.id.text_corner00)
        setCoord(0.0, solution.imageDimension.height.toDouble(), R.id.text_corner01)
        setCoord(solution.imageDimension.width.toDouble(), 0.0, R.id.text_corner10)
        setCoord(
            solution.imageDimension.width.toDouble(),
            solution.imageDimension.height.toDouble(),
            R.id.text_corner11
        )
        setCoord(solution.refPixel.x, solution.refPixel.y, R.id.text_center)
    }
}
