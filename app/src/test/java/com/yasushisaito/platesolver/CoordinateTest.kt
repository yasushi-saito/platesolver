package com.yasushisaito.platesolver
import org.junit.Test

import org.junit.Assert.*

class CoordinateTest {
    @Test
    fun pixelCanvasCoords_wideImage() {
        val imageDim = ImageDimension(1000, 200)
        val canvasDim = CanvasDimension(500, 200)
        val c = PixelCoordinate(100.0, 50.0).toCanvasCoordinate(imageDim, canvasDim)
        assertEquals(50.0, c.x, 1e-3)
        assertEquals(25.0, c.y, 1e-3)
    }

    @Test
    fun pixelCanvasCoords_tallImage() {
        val imageDim = ImageDimension(200, 1000)
        val canvasDim = CanvasDimension(500, 200)
        val c = PixelCoordinate(100.0, 50.0).toCanvasCoordinate(imageDim, canvasDim)
        assertEquals(20.0, c.x, 1e-3)
        assertEquals(10.0, c.y, 1e-3)
    }

    @Test
    fun pixelCanvasCoords_roundtrip() {
        val imageDim = ImageDimension(200, 1000)
        val canvasDim = CanvasDimension(500, 200)

        val roundtrip = fun(px: Int, py: Int) {
            val c = PixelCoordinate(px.toDouble(), py.toDouble()).toCanvasCoordinate(imageDim, canvasDim)
            val p = c.toPixelCoordinate(imageDim, canvasDim)
            assertEquals(px.toDouble(), p.x, 1e-3)
            assertEquals(py.toDouble(), p.y, 1e-3)
        }
        roundtrip(0, 0)
        roundtrip(100, 50)
        roundtrip(50, 100)
        roundtrip(200, 1000)
    }

}