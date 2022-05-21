package com.yasushisaito.platesolver

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

data class SolverParameters(val imagePath: String, val fovDeg: Double) {
    fun hashString(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.reset()

        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        dos.writeChars(imagePath)
        dos.writeDouble(fovDeg)
        dos.flush()
        digest.update(bos.toByteArray())
        val hash = digest.digest()
        return hash.joinToString("") { byte -> "%02x".format(byte) }
    }
}
