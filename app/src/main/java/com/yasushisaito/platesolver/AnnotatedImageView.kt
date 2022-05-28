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
import kotlin.math.cos
import kotlin.math.sin

private data class CanvasCoordinate(val x: Double, val y: Double)

private data class LabelCircle(val centerX: Double, val centerY: Double, val radius: Double, val label: String) {
        init {
            assert(radius > 0.0) { Log.e("Circle", "$radius") }
        }
        fun overlaps(r: LabelRect): Boolean {
            return (centerX >= r.minX - radius &&
                    centerX <= r.maxX + radius &&
                    centerY >= r.minY - radius &&
                    centerY <= r.maxY + radius)

        }
    }
private data class LabelRect(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double, val label: String) {
        init {
            assert(minX <= maxX) { Log.e("LabelRect", "$minX $maxX") }
            assert(minY <= maxY) { Log.e("LabelRect", "$minY $maxY") }
        }
        fun overlaps(r: LabelRect): Boolean {
            return (minX < r.maxX &&
                    maxX > r.minX &&
                    minY < r.maxY &&
                    maxY > r.minY)
        }
    }

private class ConflictDetector {
    private val circles = ArrayList<LabelCircle>()
    private val rects = ArrayList<LabelRect>()

    fun addCircle(c: LabelCircle) {
        circles.add(c)
    }

    fun addRect(r: LabelRect) {
        rects.add(r)
    }

    fun findOverlaps(r: LabelRect): Double {
        var n = 0.0
        for (c in circles) {
            if (c.overlaps(r)) {
                n += 1.0
            }
        }
        for (c in rects) {
            if (c.overlaps(r)) {
                n += 1.0
            }
        }
        return n
    }
}

data class CanvasDimension(val width: Float, val height: Float)

private fun pixelCoordToCanvasCoord(
    p: PixelCoordinate,
    imageDim: Wcs.ImageDimension,
    canvasDim: CanvasDimension,
): CanvasCoordinate {
    return CanvasCoordinate(
        p.x / imageDim.width * canvasDim.width,
        p.y / imageDim.height * canvasDim.height
    )
}

// Defines the canvas location of a star label
private data class LabelPlacement(
    // The location of the circle that marks the star or nebula
    val circle: LabelCircle,
    // The location of the text label
    val label: LabelRect,
    // The amount of label shifting. That is, the label's left-bottom corner should be at
    // (circle.centerX + labelOffX, circle.centerY + labelOffY)
    val labelOffX: Double,
    val labelOffY: Double,
)

private fun placeLabels(
    solution: Solution,
    paint: Paint,
    imageDim: Wcs.ImageDimension,
    canvasDim: CanvasDimension,
    scaleFactor: Float
): ArrayList<LabelPlacement> {
    val circleRadius = 16.0 / scaleFactor
    val conflictDetector = ConflictDetector()

    for (e in solution.matchedStars) {
        val name = e.names[0]
        val c = pixelCoordToCanvasCoord(solution.wcsToPixel(e.wcs), imageDim, canvasDim)
        conflictDetector.addCircle(LabelCircle(c.x, c.y, circleRadius, label = name))
    }

    val distsFromCenter = arrayOf(8.0, 24.0, 40.0, 56.0)
    val angles = arrayOf(
        0.0, Math.PI / 3, Math.PI * 2 / 3,
        Math.PI * 3 / 3, Math.PI * 4 / 3, Math.PI * 5 / 3
    )

    // Place star names in visible magnitude order (brightest first).
    val textBounds = Rect()
    val placements = ArrayList<LabelPlacement>()
    for (e in solution.matchedStars) {
        val name = e.names[0]

        paint.getTextBounds(name, 0, name.length, textBounds)
        val c = pixelCoordToCanvasCoord(solution.wcsToPixel(e.wcs), imageDim, canvasDim)

        // Try a few points to place the label. Stop if we find a point what doesn't overlap
        // with existing labels. If all points overlap with existing labels, pick the one
        // with minimal overlaps.
        var bestRect: LabelRect? = null
        var bestOffX = 0.0
        var bestOffY = 0.0
        var bestRectScore: Double = Double.MAX_VALUE
        outerLoop@ for (dist in distsFromCenter) {
            for (angle in angles) {
                val baseX = dist * cos(angle) / scaleFactor
                val baseY = dist * sin(angle) / scaleFactor
                val minX = c.x + baseX
                val maxX = minX + textBounds.width()

                // When we place the label left of the circle, align the
                // label to its right edge.
                val offX = if (baseX >= 0) 0.0 else -textBounds.width().toDouble()

                val minY = c.y + baseY
                val maxY = minY + textBounds.height()
                // When we place the label below the circle, align the
                // label to its top edge.
                val offY = if (baseY >= 0) -textBounds.height().toDouble() else 0.0
                val rect = LabelRect(
                    minX = minX + offX,
                    minY = minY + offY,
                    maxX = maxX + offX,
                    maxY = maxY + offY,
                    label = name
                )
                val score = conflictDetector.findOverlaps(rect)
                if (score < bestRectScore) {
                    bestRect = rect
                    bestOffX = offX
                    bestOffY = offY
                    bestRectScore = score
                    if (score == 0.0) break@outerLoop
                }
            }
        }
        conflictDetector.addRect(bestRect!!)
        val center = pixelCoordToCanvasCoord(solution.wcsToPixel(e.wcs), imageDim, canvasDim)
        placements.add(LabelPlacement(
            circle = LabelCircle(center.x, center.y, circleRadius, label=name),
            label = bestRect,
            labelOffX = -bestOffX,
            labelOffY = -bestOffY
        ))
    }
    return placements
}

class AnnotatedImageView(context: Context, attributes: AttributeSet) : View(context, attributes) {
    private val paint = Paint() // Paint object for coloring shapes

    private var initX = 0f // See onTouchEvent
    private var initY = 0f // See onTouchEvent

    private var canvasX = 0f // x-coord of canvas (0,0)
    private var canvasY = 0f // y-coord of canvas (0,0)

    private var dragging = false // May be unnecessary

    // Detector for scaling gestures (i.e. pinching or double tapping
    private val detector = ScaleGestureDetector(context, ScaleListener())
    private var scaleFactor = 1f // Zoom level (initial value is 1x)
    private var labelPlacements = ArrayList<LabelPlacement>()

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
            p.y / solution.imageDimension.height * height
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save() // save() and restore() are used to reset canvas data after each draw

        // Set the canvas origin to the center of the screen only on the first time onDraw is called
        //  (otherwise it'll break the panning code)
        paint.textSize = 32f / scaleFactor

        if (labelPlacements.isEmpty()) {
            labelPlacements = placeLabels(
                solution = solution,
                canvasDim = CanvasDimension(width.toFloat(), height.toFloat()),
                imageDim = solution.imageDimension,
                paint = paint,
                scaleFactor = scaleFactor
            )
            assert(labelPlacements.size == solution.matchedStars.size)
        }
        canvas.scale(scaleFactor, scaleFactor) // Scale the canvas according to scaleFactor

        // Just draw a bunch of circles (this is for testing panning and zooming
        canvas.translate(canvasX, canvasY)

        canvas.drawBitmap(
            imageBitmap,
            imageBitmapRect,
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            paint
        )

        paint.color = Color.parseColor("#00e0e0")
        for (i in 0..solution.matchedStars.size-1) {
            val e = solution.matchedStars[i]
            val placement = labelPlacements[i]

            val px = solution.wcsToPixel(e.wcs)
            val c = pixelCoordToCanvasCoord(px)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f / scaleFactor
            canvas.drawCircle(
                placement.circle.centerX.toFloat(),
                placement.circle.centerY.toFloat(),
                placement.circle.radius.toFloat(),
                paint)
            canvas.drawLine(
                placement.circle.centerX.toFloat(),
                placement.circle.centerY.toFloat(),
                (placement.label.minX + placement.labelOffX).toFloat(),
                (placement.label.minY + placement.labelOffY).toFloat(),
                paint)

            paint.style = Paint.Style.FILL
            canvas.drawText(
                e.names[0],
                placement.label.minX.toFloat(),
                placement.label.maxY.toFloat(),
                paint
            )
        }
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

        when (event.action and ACTION_MASK) {
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
                    canvasX += dx / scaleFactor
                    canvasY += dy / scaleFactor

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
        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var newScaleFactor = scaleFactor * detector.scaleFactor
            // If scaleFactor is less than 0.5x, default to 0.5x as a minimum. Likewise, if
            //  scaleFactor is greater than 10x, default to 10x zoom as a maximum.
            newScaleFactor = MIN_ZOOM.coerceAtLeast(newScaleFactor.coerceAtMost(MAX_ZOOM))

            if (newScaleFactor != scaleFactor) {
                scaleFactor = newScaleFactor
                labelPlacements.clear()
                invalidate()
            }
            return true
        }
    }

}