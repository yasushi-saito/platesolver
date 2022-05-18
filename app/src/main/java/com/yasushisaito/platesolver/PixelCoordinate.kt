package com.yasushisaito.platesolver

// pixel image (x, y) to astronomertrical coordinate.
// x is in range [0, image width).
// y is in range [0, image height).
// (0,0) is at the upper left corner of the image.
data class PixelCoordinate(val x: Double, val y: Double)
