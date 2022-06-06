package com.yasushisaito.platesolver

import android.content.Context
import android.util.TypedValue

// Get the color with the given resource ID. It must be defined in the current
// theme.
fun getColorInTheme(context: Context, resourceId: Int): Int {
    val typedValue =  TypedValue()
    val theme = context.theme
    theme.resolveAttribute(resourceId, typedValue, true)
    return typedValue.data
}

