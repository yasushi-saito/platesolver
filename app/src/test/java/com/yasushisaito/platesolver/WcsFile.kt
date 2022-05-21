package com.yasushisaito.platesolver

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class WcsTest {
    @Test
    fun read() {
        val testRoundTrip = fun (w: Solution, x: Double, y: Double) {
            val wcs = w.pixelToWcs(PixelCoordinate(x, y))
            val pix = w.wcsToPixel(wcs)
             assertEquals(pix.x, x, 1.0)
            assertEquals(pix.y, y, 1.0)
        }
        val stream =javaClass.classLoader.getResource("m42.wcs").openStream()
        stream.use {
            val wcs = Wcs(stream)

            val refPixel = PixelCoordinate(wcs.getDouble(Wcs.CRPIX1), wcs.getDouble(Wcs.CRPIX2))
            val refWcs = WcsCoordinate(wcs.getDouble(Wcs.CRVAL1), wcs.getDouble(Wcs.CRVAL2))
            val dim = Wcs.ImageDimension((refPixel.x*2).toInt(), (refPixel.y*2).toInt())
            val pixelToWcsMatrix = Matrix22(
                wcs.getDouble(Wcs.CD1_1), wcs.getDouble(Wcs.CD1_2),
                wcs.getDouble(Wcs.CD2_1), wcs.getDouble(Wcs.CD2_2)
            )
            val solution = Solution(refPixel=refPixel,
                imagePath="foo",
                refWcs=refWcs,
                imageDimension = dim,
                pixelToWcsMatrix = pixelToWcsMatrix,
                matchedStars = ArrayList<DeepSkyEntry>())

            assertEquals(8.0, wcs.getDouble("BITPIX"), 1e-6)
            assertEquals(6025, dim.width)
            assertEquals(4025, dim.height)


            var wcsCoord = solution.pixelToWcs(PixelCoordinate(0.0, 0.0))
            assertEquals(82.782, wcsCoord.ra, 1e-3)
            assertEquals(-2.934, wcsCoord.dec, 1e-3)

            wcsCoord = solution.pixelToWcs(PixelCoordinate(6000.0, 4000.0))
            assertEquals(85.330, wcsCoord.ra, 1e-3)
            assertEquals(-6.505, wcsCoord.dec, 1e-3)

            testRoundTrip(solution, 0.0, 0.0)
            testRoundTrip(solution, 100.0, 100.0);
            testRoundTrip(solution, dim.width.toDouble(), dim.height.toDouble())
        }
    }
}