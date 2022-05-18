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
        val testRoundTrip = fun (w: Wcs, x: Double, y: Double) {
            val wcs = w.pixelToWcs(PixelCoordinate(x, y))
            val pix = w.wcsToPixel(wcs)
             assertEquals(pix.x, x, 1.0)
            assertEquals(pix.y, y, 1.0)
        }
        val stream =javaClass.classLoader.getResource("m42.wcs").openStream()
        stream.use {
            val fits = Wcs(stream)
            assertEquals(8.0, fits.getDouble("BITPIX"), 1e-6)
            assertEquals(6025, fits.getImageDimension().width)
            assertEquals(4025, fits.getImageDimension().height)

            var wcs = fits.pixelToWcs(PixelCoordinate(0.0, 0.0))
            assertEquals(82.782, wcs.ra, 1e-3)
            assertEquals(-2.934, wcs.dec, 1e-3)

            wcs = fits.pixelToWcs(PixelCoordinate(6000.0, 4000.0))
            assertEquals(85.330, wcs.ra, 1e-3)
            assertEquals(-6.505, wcs.dec, 1e-3)

            testRoundTrip(fits, 0.0, 0.0)
            testRoundTrip(fits, 100.0, 100.0);
            testRoundTrip(fits, fits.getImageDimension().width.toDouble(), fits.getImageDimension().height.toDouble())
        }
    }
}