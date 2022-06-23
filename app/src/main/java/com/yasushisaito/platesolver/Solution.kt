package com.yasushisaito.platesolver

import com.google.gson.Gson
import java.io.File
import java.io.FileReader

data class Solution(
    val version: String,
    val params: SolverParameters,
    // The true FOV of the image, as reported in astap_cli stderr.
    // Unit is degrees [0-180].
    // The value is typically different from params.fov, which is the
    // user-reported FOV value.
    val trueFovDeg: Double,
    // The user-defined filename of the image
    val imageName: String,
    val imageDimension: ImageDimension,
    val refPixel: PixelCoordinate,
    val refWcs: CelestialCoordinate,
    val pixelToWcsMatrix: Matrix22,
    val matchedStars: ArrayList<WellKnownDso>,
) {
    companion object {
        const val CURRENT_VERSION = "20220620"
    }
    private val wcsToPixelMatrix = pixelToWcsMatrix.invert()

    // Checks the validity of this object. It should be called after deserializing from
    // a JSON file.
    fun isValid(): Boolean {
        return version == CURRENT_VERSION
    }

    // Convert a pixel to celestial coordinate.
    fun toCelestialCoordinate(p: PixelCoordinate): CelestialCoordinate {
        return p.toCelestialCoordinate(imageDimension, refPixel, refWcs, pixelToWcsMatrix)
    }

    // Convert a celestial to pixel coordinate. Inverse of pixelToCelestial.
    fun toPixelCoordinate(wcs: CelestialCoordinate): PixelCoordinate {
        return wcs.toPixelCoordinate(imageDimension, refPixel, refWcs, wcsToPixelMatrix)
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
