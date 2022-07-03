package com.yasushisaito.platesolver

import com.google.gson.Gson
import java.io.File
import java.io.FileReader

// Result of a successful run of astap.
data class Solution(
    val version: String, // = CURRENT_VERSION
    val params: SolverParameters,
    // The true FOV of the image, as reported in astap_cli stderr.
    // Unit is degrees [0-180].
    // The value is typically different from params.fov, which is the
    // user-reported FOV value.
    val trueFovDeg: Double,
    // width and height of the image, for display only.
    val imageDimension: ImageDimension,
    // The pixel coord of the center of the image
    val refPixel: PixelCoordinate,
    // (ra, dec) of the center of the image
    val refCelestial: CelestialCoordinate,
    // A matrix that converts (ra, dec) to pixel (x, y).
    val pixelToCelestialMatrix: Matrix22,
    // List of stars and DSOs contained in the image.
    val matchedStars: ArrayList<WellKnownDso>,
) {
    companion object {
        // CURRENT_VERSION must be updated every time the structure of Solution changes.
        // Doing so will invalidate the cache.
        const val CURRENT_VERSION = "20220628"
    }

    // Converts pixel (x, y) to (ra, dec).
    private val celestialToPixelMatrix = pixelToCelestialMatrix.invert()

    // Checks the validity of this object. It should be called after deserializing from
    // a JSON file.
    fun isValid(): Boolean {
        return version == CURRENT_VERSION
    }

    // Convert a pixel to celestial coordinate.
    fun toCelestialCoordinate(p: PixelCoordinate): CelestialCoordinate {
        return p.toCelestialCoordinate(imageDimension, refPixel, refCelestial, pixelToCelestialMatrix)
    }

    // Convert a celestial to pixel coordinate. Inverse of pixelToCelestial.
    fun toPixelCoordinate(wcs: CelestialCoordinate): PixelCoordinate {
        return wcs.toPixelCoordinate(imageDimension, refPixel, refCelestial, celestialToPixelMatrix)
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
