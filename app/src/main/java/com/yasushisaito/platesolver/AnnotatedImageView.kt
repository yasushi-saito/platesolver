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

// Represents a circle with a text label in a canvas
private data class LabelCircle(
    val center: CanvasCoordinate,
    val radius: Double,
    val label: String
) {
    init {
        assert(radius > 0.0) { Log.e("Circle", "$radius") }
    }

    fun overlaps(r: LabelRect): Boolean {
        return (center.x >= r.min.x - radius &&
                center.x <= r.max.x + radius &&
                center.y >= r.min.y - radius &&
                center.y <= r.max.y + radius)

    }
}

// Represents a rectangle with a text label in a canvas.
private data class LabelRect(
    val min: CanvasCoordinate, // upper left corner
    val max: CanvasCoordinate, // lower right corner
    val label: String
) {
    init {
        assert(min.x <= max.x) { Log.e("LabelRect", "$min $max") }
        assert(min.y <= max.y) { Log.e("LabelRect", "$min $max") }
    }

    fun overlaps(r: LabelRect): Boolean {
        return (min.x < r.max.x &&
                max.x > r.min.x &&
                min.y < r.max.y &&
                max.y > r.min.y)
    }
}

// Detects overlaps among circles and rectangles.
private class ConflictDetector {
    private val circles = ArrayList<LabelCircle>()
    private val rects = ArrayList<LabelRect>()

    fun addCircle(c: LabelCircle) {
        circles.add(c)
    }

    fun addRect(r: LabelRect) {
        rects.add(r)
    }

    // Checks if the given rectanble overlaps circles and rectangles
    // added through addCircle and addRect. Returns a score (>=0). Score of zero means
    // no overlap was found. The larger the score, the more overlaps.
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

// Defines location of a star label within a canvas.
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

// Compute the locations of stars and DSO (deep sky object) labels.
// They are placed close to their location in the image,
// and preferably they avoid overlapping with each other.
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
        val c = solution.toPixelCoordinate(e.cel).toCanvasCoordinate(imageDim, canvasDim)
        conflictDetector.addCircle(LabelCircle(c, circleRadius, label = name))
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
        val c = solution.toPixelCoordinate(e.cel).toCanvasCoordinate(imageDim, canvasDim)

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
                    min = CanvasCoordinate(minX + offX, minY + offY),
                    max = CanvasCoordinate(maxX + offX, maxY + offY),
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
        val center = solution.toPixelCoordinate(e.cel).toCanvasCoordinate(imageDim, canvasDim)
        placements.add(
            LabelPlacement(
                circle = LabelCircle(center, circleRadius, label = name),
                label = bestRect,
                labelOffX = -bestOffX,
                labelOffY = -bestOffY
            )
        )
    }
    return placements
}

// A view that shows the user-uploaded image superimposed with names of the stars and DSOs
// contained within.
//
// https://stackoverflow.com/questions/55257981/how-to-fix-pinch-zoom-focal-point-in-a-custom-view
class AnnotatedImageView(context: Context, attributes: AttributeSet) : View(context, attributes) {
    private val paint = Paint() // Paint object for coloring shapes

    // Detector for pinch-zooming gestures.
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())

    // Detector for panning & long clicks.
    private val gestureDetector = GestureDetector(context, GestureListener())

    // Fraction of solution.matchedStars that are shown.
    // Fainter objects will be hidden with small fraction values.
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

    private val translationMatrix = Matrix()
    private var imageChanged = false

    // Tmp used to get the scale factor from translationMatrix.
    private val tmpMatrixValues = FloatArray(9)

    private fun internalSetImage(path: String) {
        optionalImageBitmap = BitmapFactory.decodeFile(path)
        imageChanged = true
    }

    fun setImage(path: String) {
        internalSetImage(path)
        invalidate()
    }

    fun setSolution(s: Solution) {
        internalSetImage(s.params.imagePath)
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
        canvas.drawColor(Color.parseColor("#404040"))
        if (optionalImageBitmap == null) return

        val imageBitmap = optionalImageBitmap!!
        val canvasDim = CanvasDimension(width, height)
        val imageDim = ImageDimension(imageBitmap.width, imageBitmap.height)

        // compute the part of the canvas that shows the image.
        // It preserves the aspect ratio of the image.
        val canvasImageRect = PixelCoordinate(
            imageBitmap.width.toDouble(),
            imageBitmap.height.toDouble()
        ).toCanvasCoordinate(imageDim, canvasDim)


        // Get the scale (magnification) factor.
        // X and Y scales are the same, so it suffices to get the X scale factor.
        translationMatrix.getValues(tmpMatrixValues)
        val scaleFactor = tmpMatrixValues[Matrix.MSCALE_X]

        canvas.save() // save() and restore() are used to reset canvas data after each draw
        paint.color = Color.parseColor("#b0b0b0")
        paint.textSize = TEXT_SIZE / scaleFactor
        paint.strokeWidth = 4f / scaleFactor

        if (imageChanged) {
            // Loaded a new image.
            // Reset the scale factor and center the initial image.
            translationMatrix.set(Matrix())
            val canvasCenterX = width / 2f
            val canvasCenterY = height / 2f
            val canvasImageCenterX = canvasImageRect.x.toFloat() / 2f
            val canvasImageCenterY = canvasImageRect.y.toFloat() / 2f
            translationMatrix.postTranslate(
                canvasCenterX - canvasImageCenterX,
                canvasCenterY - canvasImageCenterY
            )

            imageChanged = false
        }

        canvas.concat(translationMatrix)

        canvas.drawBitmap(
            imageBitmap,
            Rect(0, 0, imageBitmap.width, imageBitmap.height),
            RectF(0f, 0f, canvasImageRect.x.toFloat(), canvasImageRect.y.toFloat()),
            paint
        )

        optionalSolution?.let { solution ->
            if (labelPlacements.isEmpty()) { // The first call to this function, or scaleFactor changed.
                labelPlacements = placeDsoLabels(
                    solution = optionalSolution!!,
                    canvasDim = canvasDim,
                    imageDim = ImageDimension(imageBitmap.width, imageBitmap.height),
                    paint = paint,
                    scaleFactor = scaleFactor
                )
                assert(labelPlacements.size == optionalSolution!!.matchedStars.size)
            }


            val nStarsToShow =
                ceil(solution.matchedStars.size * matchedStarsDisplayFraction).toInt()
            for (i in 0 until nStarsToShow) {
                val e = solution.matchedStars[i]
                val placement = labelPlacements[i]

                paint.style = Paint.Style.STROKE
                canvas.drawLine(
                    placement.circle.center.x.toFloat(),
                    placement.circle.center.y.toFloat(),
                    (placement.label.min.x + placement.labelOffX).toFloat(),
                    (placement.label.min.y + placement.labelOffY).toFloat(),
                    paint
                )

                paint.style = Paint.Style.FILL
                canvas.drawText(
                    e.names[0],
                    placement.label.min.x.toFloat(),
                    placement.label.max.y.toFloat(),
                    paint
                )
            }

            paint.style = Paint.Style.FILL
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
        val c = solution.toCelestialCoordinate(px)
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
        val cx = px.toCanvasCoordinate(imageDim = solution.imageDimension, canvasDim = canvasDim)

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
        matchedStarsDisplayFraction: Double
    ): WellKnownDso? {
        val distanceSquared = fun(x0: Double, y0: Double, x1: Double, y1: Double): Double {
            return (x0 - x1) * (x0 - x1) + (y0 - y1) * (y0 - y1)
        }
        if (optionalSolution == null) return null
        val solution = optionalSolution!!
        var minDist = 400.0 // match a DSO only within a 20px radius.
        val nToSearch = ceil(labelPlacements.size * matchedStarsDisplayFraction).toInt()

        var nearestDso: WellKnownDso? = null
        for (i in 0 until nToSearch - 1) {
            val dso = solution.matchedStars[i]
            val p = labelPlacements[i]
            val d = distanceSquared(p.circle.center.x, p.circle.center.y, canvasX, canvasY)
            if (d < minDist) {
                minDist = d
                nearestDso = dso
            }
        }
        return nearestDso
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e0: MotionEvent, e1: MotionEvent, dX: Float, dY: Float): Boolean {
            // Panning.
            translationMatrix.postTranslate(-dX, -dY)
            invalidate()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            // Show a balloon help containing the name(s) and RA/Dec of the matched star (or DSO).
            if (optionalSolution == null) return
            val solution = optionalSolution!!
            val im = Matrix()
            translationMatrix.invert(im)
            val coord = floatArrayOf(e.x, e.y)
            im.mapPoints(coord)
            val dso = findNearestDso(
                labelPlacements,
                coord[0].toDouble(), coord[1].toDouble(),
                matchedStarsDisplayFraction
            )
            val message = if (dso != null) {
                val buf = StringBuilder()
                for (name in dso.names) {
                    buf.append("<b>${name}</b><br>")
                }
                buf.append(
                    "RA: <b>%s</b><br>Dec: <b>%s</b>".format(
                        rightAscensionToString(dso.cel.ra),
                        declinationToString(dso.cel.dec)
                    )
                )
                buf.toString()
            } else {
                val p =
                    CanvasCoordinate(coord[0].toDouble(), coord[1].toDouble()).toPixelCoordinate(
                        solution.imageDimension,
                        CanvasDimension(width, height)
                    )
                val cel = solution.toCelestialCoordinate(p)
                val buf = StringBuilder()
                buf.append(
                    "RA: <b>%s</b><br>Dec: <b>%s</b>".format(
                        rightAscensionToString(cel.ra),
                        declinationToString(cel.dec)
                    )
                )
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
