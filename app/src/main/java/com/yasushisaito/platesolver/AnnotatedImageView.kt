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

private data class LabelCircle(
    val centerX: Double,
    val centerY: Double,
    val radius: Double,
    val label: String
) {
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

private data class LabelRect(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
    val label: String
) {
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

private fun pixelCoordToCanvasCoord(
    p: PixelCoordinate,
    imageDim: ImageDimension,
    canvasDim: CanvasDimension,
): CanvasCoordinate {
    // Preserve the aspect ratio of the original image.
    val imageAspectRatio = imageDim.width / imageDim.height
    val canvasAspectRatio = canvasDim.width / canvasDim.height

    // Size of the subpart of the canvas that's used to draw the bitmap
    val canvasImageWidth: Float
    val canvasImageHeight: Float
    if (imageAspectRatio > canvasAspectRatio) {
        // If the image is horizontally oblong
        canvasImageWidth = canvasDim.width.toFloat()
        canvasImageHeight = canvasDim.width.toFloat() / imageAspectRatio
    } else {
        // If the image is vertically oblong
        canvasImageHeight = canvasDim.height.toFloat()
        canvasImageWidth = canvasDim.height.toFloat() * imageAspectRatio
    }
    return CanvasCoordinate(
        p.x / imageDim.width * canvasImageWidth,
        p.y / imageDim.height * canvasImageHeight
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

// Compute the locations of DSO (deep sky object) labels.
// They are placed close to their location in the image,
// and preferably they don't overlap.
private fun placeDsoLabels(
    solution: Solution,
    paint: Paint,
    imageDim: ImageDimension,
    canvasDim: CanvasDimension,
    scaleFactor: Float
): ArrayList<LabelPlacement> {
    val circleRadius = 16.0 / scaleFactor
    val conflictDetector = ConflictDetector()

    for (e in solution.matchedStars) {
        val name = e.names[0]
        val c = pixelCoordToCanvasCoord(solution.celestialToPixel(e.cel), imageDim, canvasDim)
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
        val c = pixelCoordToCanvasCoord(solution.celestialToPixel(e.cel), imageDim, canvasDim)

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
        val center = pixelCoordToCanvasCoord(solution.celestialToPixel(e.cel), imageDim, canvasDim)
        placements.add(
            LabelPlacement(
                circle = LabelCircle(center.x, center.y, circleRadius, label = name),
                label = bestRect,
                labelOffX = -bestOffX,
                labelOffY = -bestOffY
            )
        )
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
        const val TAG = "AnnotatedImageView"
        private const val MIN_ZOOM = 0.1f
        private const val MAX_ZOOM = 10f
    }

    private lateinit var solution: Solution
    private lateinit var imageBitmap: Bitmap

    // Fraction of solution.matchedStars that are shown.
    // Darker objects will be hidden with small fraction values.
    // Value is in range [0, 1]
    private var matchedStarsDisplayFraction = 1.0

    // Color of the text to be used when drawing outside the image's frame.
    private var offImageTextColor = getColorInTheme(context, R.attr.textColor)
    private var textSize = 40f

    fun setSolution(s: Solution) {
        solution = s
        imageBitmap = BitmapFactory.decodeFile(s.params.imagePath)
    }

    fun setMatchedStarsDisplayFraction(fraction: Double) {
        assert(fraction >= 0.0 && fraction <= 1.0, { Log.e(TAG, "Bad fraction $fraction") })
        if (fraction != matchedStarsDisplayFraction) {
            matchedStarsDisplayFraction = fraction
            invalidate()
        }
    }

    /*
        private fun pixelCoordToCanvasCoord(p: PixelCoordinate): CanvasCoordinate {
            return CanvasCoordinate(
                p.x / solution.imageDimension.width * width,
                p.y / solution.imageDimension.height * height
            )
        }
    */
    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save() // save() and restore() are used to reset canvas data after each draw
        paint.color = offImageTextColor
        paint.textSize = textSize / scaleFactor

        val canvasDim = CanvasDimension(width, height)
        if (labelPlacements.isEmpty()) { // The first call, or the scaleFactor changed.
            labelPlacements = placeDsoLabels(
                solution = solution,
                canvasDim = canvasDim,
                imageDim = solution.imageDimension,
                paint = paint,
                scaleFactor = scaleFactor
            )
            assert(labelPlacements.size == solution.matchedStars.size)
        }
        canvas.scale(scaleFactor, scaleFactor) // Scale the canvas according to scaleFactor

        // Just draw a bunch of circles (this is for testing panning and zooming
        canvas.translate(canvasX, canvasY)

        // Preserve the aspect ratio of the original image.
        val canvasImageRect = pixelCoordToCanvasCoord(
            PixelCoordinate(
                solution.imageDimension.width.toDouble(),
                solution.imageDimension.height.toDouble()
            ),
            solution.imageDimension,
            canvasDim
        )
        canvas.drawBitmap(
            imageBitmap,
            Rect(0, 0, solution.imageDimension.width, solution.imageDimension.height),
            RectF(0f, 0f, canvasImageRect.x.toFloat(), canvasImageRect.y.toFloat()),
            paint
        )

        paint.color = context.getColor(R.color.imageAnnotationColor)
        val nStarsToShow =
            Math.ceil(solution.matchedStars.size * matchedStarsDisplayFraction).toInt()
        for (i in 0 until nStarsToShow) {
            val e = solution.matchedStars[i]
            val placement = labelPlacements[i]

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f / scaleFactor
            canvas.drawLine(
                placement.circle.centerX.toFloat(),
                placement.circle.centerY.toFloat(),
                (placement.label.minX + placement.labelOffX).toFloat(),
                (placement.label.minY + placement.labelOffY).toFloat(),
                paint
            )

            paint.style = Paint.Style.FILL
            canvas.drawText(
                e.names[0],
                placement.label.minX.toFloat(),
                placement.label.maxY.toFloat(),
                paint
            )
        }

        paint.color = offImageTextColor
        drawCelestialCoordinate(
            PixelCoordinate(0.0, 0.0),
            -20f, -20f,
            solution,
            canvasDim,
            scaleFactor,
            paint,
            canvas,
        )
        drawCelestialCoordinate(
            PixelCoordinate(solution.imageDimension.width.toDouble(), 0.0),
            20f, -20f,
            solution,
            canvasDim,
            scaleFactor,
            paint,
            canvas,
        )
        drawCelestialCoordinate(
            PixelCoordinate(0.0, solution.imageDimension.height.toDouble()),
            -20f, 20f,
            solution,
            canvasDim,
            scaleFactor,
            paint,
            canvas,
        )
        drawCelestialCoordinate(
            PixelCoordinate(
                solution.imageDimension.width.toDouble(),
                solution.imageDimension.height.toDouble()
            ),
            20f, 20f,
            solution,
            canvasDim,
            scaleFactor,
            paint,
            canvas,
        )
        canvas.restore()
    }

    private fun drawCelestialCoordinate(
        px: PixelCoordinate,
        baseX: Float,
        baseY: Float,
        solution: Solution,
        canvasDim: CanvasDimension,
        scaleFactor: Float,
        paint: Paint,
        canvas: Canvas
    ) {
        val c = solution.pixelToCelestial(px)
        // https://stackoverflow.com/questions/6756975/draw-multi-line-text-to-canvas
        val texts = c.toDisplayString().split("\n")
        var textWidth = -1
        val thisRect = Rect()
        for (text in texts) {
            paint.getTextBounds(text, 0, text.length, thisRect)
            textWidth = Integer.max(textWidth, thisRect.right - thisRect.left)
        }
        val textHeight = (texts.size - 1) * paint.textSize

        val offX = if (baseX >= 0) 0.0 else textWidth.toDouble()
        val offY = if (baseY >= 0) -textHeight.toDouble() else 0.0
        val cx =
            pixelCoordToCanvasCoord(px, imageDim = solution.imageDimension, canvasDim = canvasDim)

        var y = cx.y + baseY - offY
        for (text in texts) {
            canvas.drawText(
                text,
                (cx.x + baseX - offX).toFloat(),
                y.toFloat(),
                paint
            )
            y += paint.textSize
        }
        canvas.drawLine(
            cx.x.toFloat(), cx.y.toFloat(),
            (cx.x + baseX).toFloat(),
            (cx.y + baseY).toFloat(),
            paint
        )
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