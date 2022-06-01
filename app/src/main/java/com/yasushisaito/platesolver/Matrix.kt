package com.yasushisaito.platesolver

data class Vector2(val x: Double, val y: Double)

// Represents a 2 by 2 matrix,
// [ v00 v01
//   v10 v11 ]
data class Matrix22(val v00: Double, val v01: Double, val v10: Double, val v11: Double) {
    // Multiple matrix and the vector
    fun multiply(v: Vector2): Vector2 {
        return Vector2(v00 * v.x + v01 * v.y, v01* v.x + v11 * v.y)
    }

    // Compute the inverse of this matrix. Raises an exception if the matrix is not
    // independent.
    fun invert(): Matrix22 {
        val det = 1.0 / (v00*v11 - v01*v10)
        return Matrix22(v11 * det, -v01 * det, -v10 * det, v00 * det)
    }
}