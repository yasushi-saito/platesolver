package com.yasushisaito.platesolver

import com.google.gson.Gson
import java.io.File
import java.io.FileReader

/*

 if mainwindow.flip_horizontal1.checked then flipH:=-1 else flipH:=+1;
 if mainwindow.flip_vertical1.checked then flipV:=-1 else flipV:=+1;

  cdelt1_a:=sqrt(CD1_1*CD1_1+CD1_2*CD1_2);{length of a pixel diagonal in direction RA in arcseconds}

  moveToex(mainwindow.image_north_arrow1.Canvas.handle,round(xpos),round(ypos),nil);
  det:=CD2_2*CD1_1-CD1_2*CD2_1;{this result can be negative !!}
  dRa:=0;
  dDec:=cdelt1_a*leng;
  x := (CD1_2*dDEC - CD2_2*dRA) / det;
  y := (CD1_1*dDEC - CD2_1*dRA) / det;
  lineTo(mainwindow.image_north_arrow1.Canvas.handle,round(xpos-x*flipH),round(ypos-y*flipV)); {arrow line}
  dRa:=cdelt1_a*-3;
  dDec:=cdelt1_a*(leng-5);
  x := (CD1_2*dDEC - CD2_2*dRA) / det;
  y := (CD1_1*dDEC - CD2_1*dRA) / det;
  lineTo(mainwindow.image_north_arrow1.Canvas.handle,round(xpos-x*flipH),round(ypos-y*flipV)); {arrow pointer}
  dRa:=cdelt1_a*+3;
  dDec:=cdelt1_a*(leng-5);
  x := (CD1_2*dDEC - CD2_2*dRA) / det;
  y := (CD1_1*dDEC - CD2_1*dRA) / det;
  lineTo(mainwindow.image_north_arrow1.Canvas.handle,round(xpos-x*flipH),round(ypos-y*flipV)); {arrow pointer}
  dRa:=0;
  dDec:=cdelt1_a*leng;
  x := (CD1_2*dDEC - CD2_2*dRA) / det;
  y := (CD1_1*dDEC - CD2_1*dRA) / det;
  lineTo(mainwindow.image_north_arrow1.Canvas.handle,round(xpos-x*flipH),round(ypos-y*flipV)); {arrow pointer}


  moveToex(mainwindow.image_north_arrow1.Canvas.handle,round(xpos),round(ypos),nil);{east pointer}
  dRa:= cdelt1_a*leng/3;
  dDec:=0;
  x := (CD1_2*dDEC - CD2_2*dRA) / det;
  y := (CD1_1*dDEC - CD2_1*dRA) / det;
  lineTo(mainwindow.image_north_arrow1.Canvas.handle,round(xpos-x*flipH),round(ypos-y*flipV)); {east pointer}

*/

data class Solution(
    val version: String,
    val params: SolverParameters,
    val imageName: String, // The user-defined filename of the image
    val imageDimension: AstapResultReader.ImageDimension,
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
