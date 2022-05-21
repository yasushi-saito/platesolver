package com.yasushisaito.platesolver
import org.junit.Test

import org.junit.Assert.*
import java.io.File
import java.io.FileInputStream

class UtilTest {
    @Test
    fun writeFileAtomic() {
        val testPath = File.createTempFile("tmp", "tmp")
        com.yasushisaito.platesolver.writeFileAtomic(testPath, "hellohello")
        FileInputStream(testPath).use {stream->
            assertEquals(String(stream.readBytes()), "hellohello")
        }
    }
}