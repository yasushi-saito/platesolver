package com.yasushisaito.platesolver

// Partially copied from https://github.com/arjunr00/android-pan-zoom-canvas by Arjun Raghavan

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.MotionEvent.*
import android.view.ScaleGestureDetector
import android.view.View

private data class CanvasCoordinate(val x: Double, val y: Double)

class AnnotatedImageView(context: Context, attributes: AttributeSet) : View(context, attributes) {
    private val paint = Paint() // Paint object for coloring shapes

    private var initX = 0f // See onTouchEvent
    private var initY = 0f // See onTouchEvent

    private var canvasX = 0f // x-coord of canvas (0,0)
    private var canvasY = 0f // y-coord of canvas (0,0)
    private var dispWidth = 0f // (Supposed to be) width of entire canvas
    private var dispHeight = 0f // (Supposed to be) height of entire canvas

    private var dragging = false // May be unnecessary
    private var firstDraw = true

    // Detector for scaling gestures (i.e. pinching or double tapping
    private var detector = ScaleGestureDetector(context, ScaleListener())
    private var scaleFactor = 1f // Zoom level (initial value is 1x)

    companion object {
        private const val TAG = "AnnotatedImageView"
        private const val MIN_ZOOM = 0.1f
        private const val MAX_ZOOM = 10f
    }

    private lateinit var solution: Solution
    private lateinit var imageBitmap: Bitmap
    private lateinit var imageBitmapRect: Rect

    fun setSolution(s: Solution) {
        solution = s
        imageBitmap = BitmapFactory.decodeFile(s.params.imagePath)
        imageBitmapRect = Rect(0, 0, imageBitmap.width, imageBitmap.height)
    }

    private fun pixelCoordToCanvasCoord(p: PixelCoordinate): CanvasCoordinate {
        return CanvasCoordinate(
            p.x / solution.imageDimension.width * width,
            p.y / solution.imageDimension.height * height)
    }


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.save() // save() and restore() are used to reset canvas data after each draw

        // Set the canvas origin to the center of the screen only on the first time onDraw is called
        //  (otherwise it'll break the panning code)
        if (firstDraw) {
            canvasX = 0f
            canvasY = 0f
            firstDraw = false
        }
        canvas.scale(scaleFactor, scaleFactor) // Scale the canvas according to scaleFactor

        // Just draw a bunch of circles (this is for testing panning and zooming
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#00e0e0")
        canvas.translate(canvasX, canvasY)

        canvas.drawBitmap(
            imageBitmap,
            imageBitmapRect,
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            paint
        )

        for (e in solution.matchedStars) {
            val px = solution.wcsToPixel(e.wcs)
            val c = pixelCoordToCanvasCoord(px)
            if (e.names.contains("m42") or e.names.contains("m43")) {
                Log.d(TAG, "CENTER: px=$px canas=$c")
                canvas.drawCircle(c.x.toFloat(), c.y.toFloat(), 10f, paint)
            }
        }
        Log.d(TAG, "CENTER: ($canvasX, $canvasY) width: (${width}, ${height})")
        /*
        canvas.drawCircle(0f,0f,radius,paint)
        for (i in 2..40 step 2) {
            canvas.drawCircle(radius*i,0f,radius,paint)
            canvas.drawCircle(-radius*i,0f,radius,paint)
            canvas.drawCircle(0f,radius*i,radius,paint)
            canvas.drawCircle(0f,-radius*i,radius,paint)
            canvas.drawCircle(radius*i,radius*i,radius,paint)
            canvas.drawCircle(radius*i,-radius*i,radius,paint)
            canvas.drawCircle(-radius*i,radius*i,radius,paint)
            canvas.drawCircle(-radius*i,-radius*i,radius,paint)
        }*/
        canvas.restore()
    }

    // performClick isn't being overridden (should be for accessibility purposes), but it doesn't
    //  really matter here.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // These two are the coordinates of the user's finger whenever onTouchEvent is called
        val x: Float = event.x
        val y: Float = event.y

        //@TODO: HIGH PRIORITY
        // - Prevent user from scrolling past ends of canvas

        //@TODO: LOW PRIORITY
        // - Add functionality such that initX and initY snap to the position of whichever
        //    finger is up first, be it pointer or main (to prevent jumpiness)
        // - Make sure that when the user zooms in or out the focal point is the midpoint of a line
        //    connecting the main and pointer fingers

        when(event.action and ACTION_MASK) {
            ACTION_DOWN -> {
                // Might not be necessary; check out later
                dragging = true
                // We want to store the coords of the user's finger as it is before they move
                //  in order to calculate dx and dy
                initX = event.x
                initY = event.y
            }
            ACTION_MOVE -> {
                // Self explanatory; the difference in x- and y-coords between successive calls to
                //  onTouchEvent
                val dx: Float = event.x - initX
                val dy: Float = event.y - initY

                if (dragging) {
                    // Move the canvas dx units right and dy units down
                    // dx and dy are divided by scaleFactor so that panning speeds are consistent
                    //  with the zoom level
                    canvasX += dx/scaleFactor
                    canvasY += dy/scaleFactor

                    invalidate() // Re-draw the canvas

                    // Change initX and initY to the new x- and y-coords
                    initX = x
                    initY = y
                }
            }
            ACTION_POINTER_UP -> {
                // This sets initX and initY to the position of the pointer finger so that the
                //  screen doesn't jump when it's lifted with the main finger still down
                initX = x
                initY = y
            }
            ACTION_UP -> dragging = false // Again, may be unnecessary
        }

        detector.onTouchEvent(event) // Listen for scale gestures (i.e. pinching or double tap+drag
        // Just some useful coordinate data
        Log.d(TAG, "x: $x, y: $y,\ninit: ($initX, $initY) " +
                "canvas: ($canvasX, $canvasY),\nwidth: $dispWidth, height: $dispHeight\n" +
                "focusX: ${detector.focusX}, focusY: ${detector.focusY}")
        // Data pertaining to fingers for responsiveness and stuff
        Log.d(TAG, "Action: ${event.action and MotionEvent.ACTION_MASK}\n")

        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // Self-explanatory
            scaleFactor *= detector.scaleFactor
            // If scaleFactor is less than 0.5x, default to 0.5x as a minimum. Likewise, if
            //  scaleFactor is greater than 10x, default to 10x zoom as a maximum.
            scaleFactor = Math.max(MIN_ZOOM, Math.min(scaleFactor, MAX_ZOOM))

            invalidate() // Re-draw the canvas

            return true
        }
    }

}