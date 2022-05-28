package com.yasushisaito.platesolver

import com.google.gson.Gson
import java.io.File
import java.io.FileReader

// Convert pixel coordinate to WCS.
fun convertPixelToWcs(
    p: PixelCoordinate,
    imageDimension: Wcs.ImageDimension,
    refPixel: PixelCoordinate,
    refWcs: WcsCoordinate,
    matrix: Matrix22): WcsCoordinate {
    val v = matrix.mult(Vector2(
        p.x - refPixel.x,
        imageDimension.height - p.y - refPixel.y))
    return WcsCoordinate(ra = v.x + refWcs.ra, dec = v.y + refWcs.dec)
}

// Convert WCS to the pixel coordinate. Inverse of pixelToWcs.
fun convertWcsToPixel(wcs: WcsCoordinate,
                      imageDimension: Wcs.ImageDimension,
                      refPixel: PixelCoordinate,
                      refWcs: WcsCoordinate,
                      matrix: Matrix22): PixelCoordinate {
    val pv = matrix.mult(Vector2(wcs.ra - refWcs.ra, wcs.dec - refWcs.dec))
    // wcsToPixel's Y coordinate moves up, but PixelCoordinate moves down.
    return PixelCoordinate(
        pv.x + refPixel.x,
        (imageDimension.height - (pv.y + refPixel.y)))
}

data class Solution(
    val version: String,
    val params: SolverParameters,
    val imageName: String, // The user-defined filename of the image
    val imageDimension: Wcs.ImageDimension,
    val refPixel: PixelCoordinate,
    val refWcs: WcsCoordinate,
    val pixelToWcsMatrix: Matrix22,
    val matchedStars: ArrayList<WellKnownDso>,
) {
    companion object {
        const val CURRENT_VERSION = "20220527"
    }
    private val wcsToPixelMatrix = pixelToWcsMatrix.invert()

    fun isValid(): Boolean {
        if (version != CURRENT_VERSION) return false
        if (params == null) return false
        return true
    }
    // Convert pixel coordinate to WCS.
    fun pixelToWcs(p: PixelCoordinate): WcsCoordinate {
        return convertPixelToWcs(p, imageDimension, refPixel, refWcs, pixelToWcsMatrix)
    }

    // Convert WCS to the pixel coordinate. Inverse of pixelToWcs.
    fun wcsToPixel(wcs: WcsCoordinate): PixelCoordinate {
        return convertWcsToPixel(wcs, imageDimension, refPixel, refWcs, wcsToPixelMatrix)
    }
}

// Try read a solution json file.
fun readSolution(jsonPath: File): Solution {
    return FileReader(jsonPath).use { stream ->
        return Gson().fromJson(stream, Solution::class.java)
    }
}

