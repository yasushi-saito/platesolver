package com.yasushisaito.platesolver
import org.junit.Test

import org.junit.Assert.*
import java.io.File
import java.io.FileInputStream

class CoordinateTest {
    @Test
    fun pixelCanvasCoords_wideImage() {
        val imageDim = ImageDimension(1000, 200)
        val canvasDim = CanvasDimension(500, 200)
        val c = convertPixelToCanvas(PixelCoordinate(100.0, 50.0), imageDim, canvasDim)
        assertEquals(50.0, c.x, 1e-3)
        assertEquals(25.0, c.y, 1e-3)
    }

    @Test
    fun pixelCanvasCoords_tallImage() {
        val imageDim = ImageDimension(200, 1000)
        val canvasDim = CanvasDimension(500, 200)
        val c = convertPixelToCanvas(PixelCoordinate(100.0, 50.0), imageDim, canvasDim)
        assertEquals(100.0, c.x, 1e-3)
        assertEquals(50.0, c.y, 1e-3)
    }

    @Test
    fun pixelCanvasCoords_roundtrip() {
        val imageDim = ImageDimension(200, 1000)
        val canvasDim = CanvasDimension(500, 200)

        val roundtrip = fun(px: Int, py: Int) {
            val c = convertPixelToCanvas(PixelCoordinate(px.toDouble(), py.toDouble()), imageDim, canvasDim)
            val p = convertCanvasToPixel(c, imageDim, canvasDim)
            assertEquals(px.toDouble(), p.x, 1e-3)
            assertEquals(py.toDouble(), p.y, 1e-3)
        }
        roundtrip(0, 0)
        roundtrip(100, 50)
        roundtrip(50, 100)
        roundtrip(200, 1000)
    }

}