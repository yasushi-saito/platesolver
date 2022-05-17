package com.yasushisaito.platesolver

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class FitsTest {
    @Test
    fun read() {
        val stream =javaClass.classLoader.getResource("m42.wcs").openStream()
        stream.use {
            val fits = Fits(stream)
            assertEquals(8.0, fits.getDouble("BITPIX"), 1e-6)
        }
    }
}