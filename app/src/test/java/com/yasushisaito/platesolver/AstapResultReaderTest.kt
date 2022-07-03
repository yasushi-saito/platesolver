package com.yasushisaito.platesolver

import org.junit.Test

import org.junit.Assert.*

const val TAG = "AstapResultReaderTest"

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class AstapResultReaderTest {
    fun testRoundTrip(w: Solution, x: Double, y: Double) {
        val wcs = w.toCelestialCoordinate(PixelCoordinate(x, y))
        val pix = w.toPixelCoordinate(wcs)
        println("Roundtrip: org=($x,$y) wcs=$wcs pix=$pix")
        assertEquals(pix.x, x, 1.0)
        assertEquals(pix.y, y, 1.0)
    }

    @Test
    fun read() {
        val stream =javaClass.classLoader!!.getResource("m42.wcs").openStream()
        stream.use {
            val wcs = AstapResultReader(stream)

            val refPixel = PixelCoordinate(wcs.getDouble(AstapResultReader.CRPIX1), wcs.getDouble(AstapResultReader.CRPIX2))
            val refCel = CelestialCoordinate(wcs.getDouble(AstapResultReader.CRVAL1), wcs.getDouble(AstapResultReader.CRVAL2))
            val dim = ImageDimension((refPixel.x*2).toInt(), (refPixel.y*2).toInt())
            val pixelToWcsMatrix = Matrix22(
                wcs.getDouble(AstapResultReader.CD1_1), wcs.getDouble(AstapResultReader.CD1_2),
                wcs.getDouble(AstapResultReader.CD2_1), wcs.getDouble(AstapResultReader.CD2_2)
            )
            val solution = Solution(refPixel=refPixel,
                params=SolverParameters(imagePath="foo", fovDeg=2.0, startSearch=null, dbName="h17", imageFilename="foo.jpg"),
                version=Solution.CURRENT_VERSION,
                refCelestial=refCel,
                imageDimension = dim,
                pixelToCelestialMatrix = pixelToWcsMatrix,
                trueFovDeg=3.0,
                matchedStars = ArrayList())

            assertEquals(8.0, wcs.getDouble("BITPIX"), 1e-6)
            assertEquals(6025, dim.width)
            assertEquals(4025, dim.height)

            testRoundTrip(solution, refPixel.x, refPixel.y)
            testRoundTrip(solution, 0.0, 0.0)
            testRoundTrip(solution, 100.0, 100.0)
            testRoundTrip(solution, dim.width.toDouble(), dim.height.toDouble())
            testRoundTrip(solution, 0.0, dim.height.toDouble())
            testRoundTrip(solution, dim.width.toDouble(), 0.0)
        }
    }
}