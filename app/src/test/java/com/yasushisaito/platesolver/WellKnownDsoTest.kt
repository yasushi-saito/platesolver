package com.yasushisaito.platesolver

import org.junit.Test

import org.junit.Assert.*

class WellKnownDsoTest {
    @Test
    fun read() {
        val data = """ASTAP/CCDCIEL DEEPSKY DATABASE (extract from HNSKY level 3 database), 30000 objects. Based on SAC81, Wolfgang Steinicke's REV NGC&IC, Leda, Sh2,vdB,HCG,LND,PK.DWB. GX>=1_arcmin. IAU named stars included. GC of M31, M33 added. Version 2021-06-15.
RA[0..864000], DEC[-324000..324000], name(s), length [0.1 min], width[0.1 min], orientation[degrees]
863691,323599,NP_2020
431691,-323599,SP_2020
863614,323499,NP_2025
431614,-323499,SP_2025
57,324000,NP_2000
432057,-324000,SP_2000
243089,-60178,Sirius/α_CMa
230371,-189704,Canopus/α_Car
513397,69057,Arcturus/α_Boo
527765,-219002,Rigil_Kentaurus/α_Cen
        """
        val stream = data.byteInputStream()
        stream.use {
            val ds = parseCsvToWellKnownDsoSet(stream)
            var v0 = ds.findByName("NP_2020")
            assertNotNull(v0)
            assertEquals(863691.0*360/864000, v0!!.cel.ra, 1e-6)
            assertEquals(323599.0*90/324000, v0.cel.dec, 1e-6)

            v0 = ds.findByName("α_CMa")
            assertNotNull(v0)
            assertEquals(243089.0*360/864000, v0!!.cel.ra, 1e-6)
            assertEquals(-60178.0*90/324000, v0.cel.dec, 1e-6)
            assertEquals(v0, ds.findByName("Sirius"))
        }

    }
}