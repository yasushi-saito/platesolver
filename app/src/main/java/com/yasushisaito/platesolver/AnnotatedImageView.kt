package com.yasushisaito.platesolver

// Partially copied from https://github.com/arjunr00/android-pan-zoom-canvas by Arjun Raghavan

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.*
import com.skydoves.balloon.Balloon
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

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
        val c = convertPixelToCanvas(solution.celestialToPixel(e.cel), imageDim, canvasDim)
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
        val c = convertPixelToCanvas(solution.celestialToPixel(e.cel), imageDim, canvasDim)

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
        val center = convertPixelToCanvas(solution.celestialToPixel(e.cel), imageDim, canvasDim)
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

// https://stackoverflow.com/questions/55257981/how-to-fix-pinch-zoom-focal-point-in-a-custom-view
class AnnotatedImageView(context: Context, attributes: AttributeSet) : View(context, attributes) {
    private val paint = Paint() // Paint object for coloring shapes

    // Detector for scaling gestures.
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    // Detector for panning & clicks.
    private val gestureDetector = GestureDetector(context, GestureListener())
    // Fraction of solution.matchedStars that are shown.
    // Darker objects will be hidden with small fraction values.
    // Value must be in range [0, 1].
    private var matchedStarsDisplayFraction = 1.0

    // List of labels and their locations on canvas. It contains all the labels in the solution,
    // regardless of the value of matchedStarsDisplayFraction.
    // It's a function of the solution and the image scaling factor (translationMatrix).
    // It must be cleared when any of the inputs change.
    private var labelPlacements = ArrayList<LabelPlacement>()

    companion object {
        const val TAG = "AnnotatedImageView"
        private var TEXT_SIZE = 40f
    }

    // The image to show.
    private var optionalImageBitmap: Bitmap? = null
    // The star / dso labels to show.
    private var optionalSolution: Solution? = null

    // Color of the text to be used when drawing outside the image's frame.
    private var offImageTextColor = getColorInTheme(context, R.attr.textColor)

    private val translationMatrix = Matrix()

    // Tmp used to get the scale factor from translationMatrix.
    private val tmpMatrixValues = FloatArray(9)

    fun setImage(path: String) {
        optionalImageBitmap = BitmapFactory.decodeFile(path)
        invalidate()
    }

    fun setSolution(s: Solution) {
        optionalImageBitmap = BitmapFactory.decodeFile(s.params.imagePath)
        optionalSolution = s
        labelPlacements.clear()
        invalidate()
    }

    fun setMatchedStarsDisplayFraction(fraction: Double) {
        assert(fraction in 0.0..1.0) { Log.e(TAG, "Bad fraction $fraction") }
        if (fraction != matchedStarsDisplayFraction) {
            matchedStarsDisplayFraction = fraction
            invalidate()
        }
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (optionalImageBitmap == null) return
        val imageBitmap = optionalImageBitmap!!

        // Get the scale (magnification) factor.
        // X and Y scales are the same, so it suffices to get the X scale factor.
        translationMatrix.getValues(tmpMatrixValues)
        val scaleFactor = tmpMatrixValues[Matrix.MSCALE_X]

        canvas.save() // save() and restore() are used to reset canvas data after each draw
        paint.color = offImageTextColor
        paint.textSize = TEXT_SIZE / scaleFactor

        val canvasDim = CanvasDimension(width, height)
        val imageDim = ImageDimension(imageBitmap.width, imageBitmap.height)
        canvas.concat(translationMatrix)

        //canvas.scale(scaleFactor, scaleFactor) // Scale the canvas according to scaleFactor

        // Just draw a bunch of circles (this is for testing panning and zooming
        //canvas.translate(canvasX, canvasY)

        // Preserve the aspect ratio of the original image.
        val canvasImageRect = convertPixelToCanvas(
            PixelCoordinate(
                imageBitmap.width.toDouble(),
                imageBitmap.height.toDouble(),
                //solution.imageDimension.width.toDouble(),
                // solution.imageDimension.height.toDouble()
            ),
            imageDim,
            canvasDim
        )
        canvas.drawBitmap(
            imageBitmap,
            Rect(0, 0, imageBitmap.width, imageBitmap.height),
            RectF(0f, 0f, canvasImageRect.x.toFloat(), canvasImageRect.y.toFloat()),
            paint
        )

        optionalSolution?.let { solution ->
            if (labelPlacements.isEmpty()) { // The first call, or the scaleFactor changed.
                labelPlacements = placeDsoLabels(
                    solution = optionalSolution!!,
                    canvasDim = canvasDim,
                    imageDim = ImageDimension(imageBitmap.width, imageBitmap.height),
                    paint = paint,
                    scaleFactor = scaleFactor
                )
                assert(labelPlacements.size == optionalSolution!!.matchedStars.size)
            }

            paint.color = context.getColor(R.color.imageAnnotationColor)

            val nStarsToShow = ceil(solution.matchedStars.size * matchedStarsDisplayFraction).toInt()
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
                paint,
                canvas,
            )
            drawCelestialCoordinate(
                PixelCoordinate(solution.imageDimension.width.toDouble(), 0.0),
                20f, -20f,
                solution,
                canvasDim,
                paint,
                canvas,
            )
            drawCelestialCoordinate(
                PixelCoordinate(0.0, solution.imageDimension.height.toDouble()),
                -20f, 20f,
                solution,
                canvasDim,
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
                paint,
                canvas,
            )
        }
        canvas.restore()
    }

    private fun drawCelestialCoordinate(
        px: PixelCoordinate,
        baseX: Float,
        baseY: Float,
        solution: Solution,
        canvasDim: CanvasDimension,
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
            convertPixelToCanvas(px, imageDim = solution.imageDimension, canvasDim = canvasDim)

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
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        return true
    }

    private fun findNearestDso(
        labelPlacements: ArrayList<LabelPlacement>,
        canvasX: Double, canvasY: Double,
        matchedStarsDisplayFraction: Double) : WellKnownDso? {
        val distanceSquared = fun (x0: Double, y0: Double, x1: Double, y1: Double): Double {
            return (x0-x1)*(x0-x1) + (y0-y1)*(y0-y1)
        }
        if (optionalSolution == null) return null
        val solution = optionalSolution!!
        var minDist = 400.0 // match a DSO only within a 20px radius.
        val nToSearch = ceil(labelPlacements.size * matchedStarsDisplayFraction).toInt()

        var nearestDso: WellKnownDso? = null
        for (i in 0 until nToSearch-1) {
            val dso = solution.matchedStars[i]
            val p = labelPlacements[i]
            val d = distanceSquared(p.circle.centerX, p.circle.centerY, canvasX, canvasY)
            if (d < minDist) {
                minDist = d
                nearestDso = dso
            }
        }
        return nearestDso
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e0: MotionEvent, e1: MotionEvent, dX: Float, dY: Float): Boolean {
            translationMatrix.postTranslate(-dX, -dY)
            invalidate()
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (optionalSolution == null) return
            val solution = optionalSolution!!
            val im = Matrix()
            translationMatrix.invert(im)
            val coord = floatArrayOf(e.x, e.y)
            im.mapPoints(coord)
            val dso = findNearestDso(
                labelPlacements,
                coord[0].toDouble(), coord[1].toDouble(),
                matchedStarsDisplayFraction)
            val message = if (dso != null) {
                val buf = StringBuilder()
                for (name in dso.names) {
                    buf.append("<b>${name}</b><br>")
                }
                buf.append("RA: %s<br>Dec: %s".format(
                    rightAscensionToString(dso.cel.ra),
                    declinationToString(dso.cel.dec)))
                buf.toString()
            } else {
                val p = convertCanvasToPixel(
                    CanvasCoordinate(coord[0].toDouble(), coord[1].toDouble()),
                    solution.imageDimension,
                    CanvasDimension(width, height))
                val cel = solution.pixelToCelestial(p)
                val buf = StringBuilder()
                buf.append("RA: %s<br>Dec: %s".format(
                    rightAscensionToString(cel.ra),
                    declinationToString(cel.dec)))
                buf.toString()
            }
            // https://medium.com/swlh/a-lightweight-tooltip-popup-for-android-ef9484a992d7
            val balloon = Balloon.Builder(context).setArrowSize(10)
                .setCornerRadius(4f)
                .setAlpha(0.9f)
                .setArrowSize(0)
                .setText(message)
                .setTextSize(16f)
                .setTextColor(Color.BLACK)
                .setTextGravity(Gravity.LEFT)
                .setTextIsHtml(true)
                .setBackgroundColor(Color.WHITE)
                .setAutoDismissDuration(3000L)
                .build()
            balloon.showAsDropDown(this@AnnotatedImageView, e.x.toInt(), e.y.toInt())
            //balloon.showAlignLeft(this@AnnotatedImageView, e.x.toInt(), e.y.toInt())
        }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newFactor = detector.scaleFactor
            translationMatrix.postScale(newFactor, newFactor, width / 2f, height / 2f)
            labelPlacements.clear()
            invalidate()
            return true
        }
    }

}
