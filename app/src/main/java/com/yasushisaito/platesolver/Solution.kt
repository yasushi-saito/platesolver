package com.yasushisaito.platesolver

import com.google.gson.Gson
import java.io.File
import java.io.FileReader

data class Solution(
    val version: String,
    val params: SolverParameters,
    val imageName: String, // The user-defined filename of the image
    val imageDimension: ImageDimension,
    val refPixel: PixelCoordinate,
    val refWcs: CelestialCoordinate,
    val pixelToWcsMatrix: Matrix22,
    val matchedStars: ArrayList<WellKnownDso>,
) {
    companion object {
        const val CURRENT_VERSION = "20220529"
    }
    private val wcsToPixelMatrix = pixelToWcsMatrix.invert()

    fun isValid(): Boolean {
        if (version != CURRENT_VERSION) return false
        return true
    }
    // Convert pixel coordinate to WCS.
    fun pixelToCelestial(p: PixelCoordinate): CelestialCoordinate {
        return convertPixelToCelestial(p, imageDimension, refPixel, refWcs, pixelToWcsMatrix)
    }

    // Convert WCS to the pixel coordinate. Inverse of pixelToWcs.
    fun wcsToPixel(wcs: CelestialCoordinate): PixelCoordinate {
        return convertCelestialToPixel(wcs, imageDimension, refPixel, refWcs, wcsToPixelMatrix)
    }
}

// Try read a solution json file.
fun readSolution(jsonPath: File): Solution {
    val solution: Solution = FileReader(jsonPath).use { stream ->
        Gson().fromJson(stream, Solution::class.java)
    }
    if (!solution.isValid()) throw Exception("invalid solution")
    return solution
}
