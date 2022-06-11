package com.yasushisaito.platesolver

import org.junit.Test

import org.junit.Assert.*

class WellKnownDsoTest {
    @Test
    fun read() {
        val data = """typ,ra,dec,mag,names
Gxy,0.0381,32.7384,13,IC5370
Gxy,0.8116665000000001,16.1453,11.0,NGC7814/C43"""
        val stream = data.byteInputStream()
        stream.use {
            val ds = parseCsvToWellKnownDsoSet(stream)
            var v0 = ds.findByName("IC5370")
            assertNotNull(v0)
            assertEquals(0.0381, v0!!.cel.ra, 1e-6)
            assertEquals(32.7384, v0.cel.dec, 1e-6)

            v0 = ds.findByName("NGC7814")
            assertNotNull(v0)
            assertEquals(0.8116665, v0!!.cel.ra, 1e-6)
            assertEquals(16.1453, v0.cel.dec, 1e-6)
            assertEquals(v0, ds.findByName("C43"))
        }

    }
}