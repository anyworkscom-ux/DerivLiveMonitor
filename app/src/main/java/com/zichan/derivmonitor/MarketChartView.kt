package com.zichan.derivmonitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

data class CandleBar(val epoch: Long, val open: Double, val high: Double, val low: Double, val close: Double)
enum class DrawingTool { NONE, HORIZONTAL, TRENDLINE, RAY, RECTANGLE }

private data class ChartMark(
    val tool: DrawingTool,
    val index1: Float,
    val price1: Double,
    val index2: Float,
    val price2: Double
)

private enum class GestureMode { NONE, PAN, PINCH, PRICE_SCALE, DRAW, EDIT_MARK, PLACE_ALERT }

class MarketChartView(context: Context) : View(context) {
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(42, 135, 157, 184); strokeWidth = dp(1f) }
    private val up = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(38, 166, 154); strokeWidth = dp(1.65f); strokeCap = Paint.Cap.ROUND }
    private val down = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 83, 80); strokeWidth = dp(1.65f); strokeCap = Paint.Cap.ROUND }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(145, 160, 182); textSize = dp(10f) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(79, 176, 255); strokeWidth = dp(2.1f); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val emaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(45, 220, 125); strokeWidth = dp(1.8f); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; pathEffect = DashPathEffect(floatArrayOf(dp(2.5f), dp(4.5f)), 0f)
    }
    private val alertPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(247, 181, 68); strokeWidth = dp(1.6f)
        pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(4f)), 0f)
    }
    private val toolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(180, 153, 255); strokeWidth = dp(2f); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(65, 180, 153, 255); strokeWidth = dp(12f); style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(9f); typeface = android.graphics.Typeface.DEFAULT_BOLD }

    private var bars: List<CandleBar> = emptyList()
    private var firstBarEpoch: Long? = null
    private var pricePrecision = 4
    private var crosshairVisible = false
    private var crosshairX = 0f
    private var crosshairY = 0f
    private val marks = mutableListOf<ChartMark>()
    private var activeMark: ChartMark? = null
    private var selectedMarkIndex = -1
    private var originalSelectedMark: ChartMark? = null

    var lineMode = false
        set(value) { field = value; invalidate() }
    var drawingTool = DrawingTool.NONE
        set(value) { field = value; invalidate() }
    var alertPrice: Double? = null
        set(value) { field = value; invalidate() }
    var alertPlacementMode = false
        set(value) { field = value; invalidate() }
    var onAlertPlaced: ((Double) -> Unit)? = null
    var emaEnabled = true
        set(value) { field = value; invalidate() }
    var emaPeriod = 9
        set(value) { field = value.coerceIn(1, 200); invalidate() }
    var emaShift = 0
        set(value) { field = value.coerceIn(-100, 100); invalidate() }

    private var light = false
    private var minPrice = 0.0
    private var maxPrice = 1.0
    private var plot = RectF()
    private var visibleCount = 90f
    private var startIndex = 0f
    private var autoFollow = true
    private var priceZoom = 1f
    private var priceOffset = 0.0

    private var gestureMode = GestureMode.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var startPriceOffset = 0.0
    private var pinchDistance = 0f
    private var moved = false
    private var previewPrice: Double? = null
    private var originalAlertPrice: Double? = null
    private var pinchStartDistance = 0f
    private var pinchStartVisibleCount = 90f
    private var pinchStartPriceZoom = 1f
    private var pinchAnchorIndex = 0f
    private var pinchAnchorPrice = 0.0

    init {
        isClickable = true
        isFocusable = true
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    fun setData(data: List<CandleBar>, lightMode: Boolean = false) {
        val wasEmpty = bars.isEmpty()
        val oldFirstEpoch = firstBarEpoch
        val nextBars = data.takeLast(500)
        if (!wasEmpty && !autoFollow && nextBars.isNotEmpty() && oldFirstEpoch != null && nextBars.first().epoch > oldFirstEpoch) {
            val dropped = bars.count { it.epoch < nextBars.first().epoch }
            startIndex = (startIndex - dropped).coerceAtLeast(0f)
        }
        bars = nextBars
        firstBarEpoch = bars.firstOrNull()?.epoch
        light = lightMode
        if (bars.isNotEmpty()) {
            if (wasEmpty) {
                visibleCount = min(90f, bars.size.toFloat()).coerceAtLeast(12f)
                startIndex = max(0f, bars.size - visibleCount)
                autoFollow = true
            } else if (autoFollow) {
                startIndex = max(0f, bars.size - visibleCount)
            }
            startIndex = startIndex.coerceIn(0f, maxStartIndex())
        }
        invalidate()
    }

    fun setPricePrecision(digits: Int) {
        pricePrecision = digits.coerceIn(0, 8)
        invalidate()
    }

    fun setEmaSettings(enabled: Boolean, period: Int, shift: Int) {
        emaEnabled = enabled
        emaPeriod = period
        emaShift = shift
    }

    fun resetViewport() {
        visibleCount = min(90f, bars.size.toFloat()).coerceAtLeast(12f)
        startIndex = max(0f, bars.size - visibleCount)
        priceZoom = 1f
        priceOffset = 0.0
        autoFollow = true
        invalidate()
    }

    fun clearDrawings() {
        marks.clear()
        activeMark = null
        selectedMarkIndex = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(if (light) Color.rgb(249, 251, 254) else Color.rgb(8, 15, 27))
        plot = RectF(dp(12f), dp(18f), width - dp(88f), height - dp(34f))
        axis.color = if (light) Color.rgb(174, 188, 207) else Color.rgb(145, 160, 182)
        grid.color = if (light) Color.argb(38, 185, 198, 216) else Color.argb(42, 135, 157, 184)

        if (bars.isEmpty()) {
            axis.textSize = dp(13f)
            canvas.drawText("Waiting for historical candles…", plot.left + dp(10f), height / 2f, axis)
            return
        }

        calculatePriceRange()
        drawGrid(canvas)
        canvas.save()
        canvas.clipRect(plot)
        if (lineMode) drawLineChart(canvas) else drawCandles(canvas)
        if (emaEnabled) drawEma(canvas)
        drawMarks(canvas)
        drawAlert(canvas)
        canvas.restore()
        drawCurrentPrice(canvas)
        drawCrosshair(canvas)
        drawTimeAxis(canvas)
    }

    private fun calculatePriceRange() {
        val first = startIndex.toInt().coerceIn(0, bars.lastIndex)
        val last = (startIndex + visibleCount).toInt().coerceIn(first + 1, bars.size)
        val visible = bars.subList(first, last)
        var low = visible.minOf { it.low }
        var high = visible.maxOf { it.high }
        var range = high - low
        if (range <= 0.0) range = max(abs(high) * 0.001, 0.01)
        val pad = range * 0.08
        low -= pad; high += pad
        val center = (low + high) / 2.0 + priceOffset
        val adjustedRange = (high - low) / priceZoom.toDouble()
        minPrice = center - adjustedRange / 2.0
        maxPrice = center + adjustedRange / 2.0
        if (maxPrice <= minPrice) maxPrice = minPrice + 0.000001
    }

    private fun drawGrid(canvas: Canvas) {
        axis.textSize = dp(9.5f)
        axis.textAlign = Paint.Align.LEFT
        val step = (maxPrice - minPrice) / 12.0
        for (i in 0..12) {
            val value = maxPrice - step * i
            val y = yFor(value)
            canvas.drawLine(plot.left, y, plot.right, y, grid)
            canvas.drawText(format(value), plot.right + dp(6f), y + dp(3.5f), axis)
        }
        for (i in 0..5) {
            val x = plot.left + plot.width() * i / 5f
            canvas.drawLine(x, plot.top, x, plot.bottom, grid)
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (light) Color.rgb(58, 76, 101) else Color.rgb(39, 54, 76)
            style = Paint.Style.STROKE; strokeWidth = dp(1f)
        }
        canvas.drawRect(plot, border)
        canvas.drawLine(plot.right + dp(2f), plot.top, plot.right + dp(2f), plot.bottom, grid)
    }

    private fun nicePriceStep(raw: Double): Double {
        if (!raw.isFinite() || raw <= 0.0) return 1.0
        val exponent = floor(log10(raw))
        val magnitude = 10.0.pow(exponent)
        val fraction = raw / magnitude
        val nice = when {
            fraction <= 1.0 -> 1.0
            fraction <= 2.0 -> 2.0
            fraction <= 2.5 -> 2.5
            fraction <= 5.0 -> 5.0
            else -> 10.0
        }
        return nice * magnitude
    }

    private fun drawCandles(canvas: Canvas) {
        val step = plot.width() / visibleCount.coerceAtLeast(1f)
        val bodyWidth = (step * 0.66f).coerceIn(dp(2f), dp(10f))
        val first = max(0, startIndex.toInt() - 1)
        val last = min(bars.size - 1, (startIndex + visibleCount).toInt() + 1)
        for (index in first..last) {
            val candle = bars[index]
            val x = xForIndex(index + 0.5f)
            if (x < plot.left - bodyWidth || x > plot.right + bodyWidth) continue
            val paint = if (candle.close >= candle.open) up else down
            canvas.drawLine(x, yFor(candle.high), x, yFor(candle.low), paint)
            val top = yFor(max(candle.open, candle.close))
            val bottom = yFor(min(candle.open, candle.close))
            val rect = RectF(x - bodyWidth / 2f, top, x + bodyWidth / 2f, max(top + dp(1.2f), bottom))
            canvas.drawRoundRect(rect, dp(0.8f), dp(0.8f), paint)
        }
    }

    private fun drawLineChart(canvas: Canvas) {
        val path = Path()
        val first = max(0, startIndex.toInt() - 1)
        val last = min(bars.size - 1, (startIndex + visibleCount).toInt() + 1)
        for (index in first..last) {
            val x = xForIndex(index + 0.5f)
            val y = yFor(bars[index].close)
            if (index == first) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }

    private fun drawEma(canvas: Canvas) {
        if (bars.size < emaPeriod) return
        val alpha = 2.0 / (emaPeriod + 1.0)
        var ema = bars.first().close
        val values = DoubleArray(bars.size)
        for (i in bars.indices) {
            ema = if (i == 0) bars[i].close else bars[i].close * alpha + ema * (1.0 - alpha)
            values[i] = ema
        }
        val path = Path()
        val first = max(0, startIndex.toInt() - emaPeriod - 2)
        val last = min(bars.lastIndex, (startIndex + visibleCount).toInt() + 2)
        var started = false
        for (i in first..last) {
            val x = xForIndex(i + 0.5f + emaShift)
            val y = yFor(values[i])
            if (x < plot.left - dp(8f) || x > plot.right + dp(8f)) continue
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
        }
        canvas.drawPath(path, emaPaint)
    }

    private fun drawMarks(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(plot)
        marks.forEachIndexed { index, mark ->
            if (index == selectedMarkIndex) drawMark(canvas, mark, selectedPaint)
            drawMark(canvas, mark, toolPaint)
        }
        activeMark?.let { drawMark(canvas, it, toolPaint) }
        canvas.restore()
    }

    private fun drawMark(canvas: Canvas, mark: ChartMark, paint: Paint) {
        val x1 = xForIndex(mark.index1)
        val y1 = yFor(mark.price1)
        val x2 = xForIndex(mark.index2)
        val y2 = yFor(mark.price2)
        when (mark.tool) {
            DrawingTool.HORIZONTAL -> {
                canvas.drawLine(plot.left, y1, plot.right, y1, paint)
                if (paint === toolPaint) {
                    labelPaint.color = if (light) Color.rgb(8, 15, 27) else Color.WHITE
                    canvas.drawText(format(mark.price1), plot.right - dp(55f), y1 - dp(5f), labelPaint)
                }
            }
            DrawingTool.TRENDLINE -> canvas.drawLine(x1, y1, x2, y2, paint)
            DrawingTool.RAY -> {
                val dx = x2 - x1
                val dy = y2 - y1
                val endY = if (abs(dx) < 0.5f) y2 else y1 + dy / dx * (plot.right - x1)
                canvas.drawLine(x1, y1, plot.right, endY, paint)
            }
            DrawingTool.RECTANGLE -> canvas.drawRect(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2), paint)
            else -> Unit
        }
    }

    private fun drawAlert(canvas: Canvas) {
        alertPrice?.let { value ->
            val y = yFor(value)
            canvas.drawLine(plot.left, y, plot.right, y, alertPaint)
            drawPriceTag(canvas, "ALERT  " + format(value), y, Color.rgb(247, 181, 68))
        }
        if (alertPlacementMode && previewPrice != null) {
            val y = yFor(previewPrice!!)
            canvas.drawLine(plot.left, y, plot.right, y, alertPaint)
            drawPriceTag(canvas, "TARGET  " + format(previewPrice!!), y, Color.rgb(247, 181, 68))
        }
    }

    private fun drawCurrentPrice(canvas: Canvas) {
        val value = bars.lastOrNull()?.close ?: return
        val y = yFor(value)
        if (y < plot.top || y > plot.bottom) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (light) Color.rgb(14, 142, 132) else Color.rgb(48, 190, 166)
            strokeWidth = dp(1f)
            pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(4f)), 0f)
        }
        canvas.drawLine(plot.left, y, plot.right, y, p)
        drawAxisTag(canvas, format(value), y, p.color)
    }

    private fun drawCrosshair(canvas: Canvas) {
        if (!crosshairVisible || crosshairX < plot.left || crosshairX > plot.right || crosshairY < plot.top || crosshairY > plot.bottom) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (light) Color.rgb(65, 83, 108) else Color.rgb(205, 218, 235)
            strokeWidth = dp(1f)
            pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(4f)), 0f)
        }
        canvas.drawLine(crosshairX, plot.top, crosshairX, plot.bottom, p)
        canvas.drawLine(plot.left, crosshairY, plot.right, crosshairY, p)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.color; style = Paint.Style.FILL }
        canvas.drawCircle(crosshairX, crosshairY, dp(3f), dot)
        drawAxisTag(canvas, format(valueForY(crosshairY)), crosshairY, if (light) Color.rgb(45, 72, 105) else Color.rgb(64, 91, 125))
        val index = indexForX(crosshairX).toInt().coerceIn(0, bars.lastIndex)
        val time = java.text.SimpleDateFormat("MMM dd  HH:mm", java.util.Locale.US)
            .format(java.util.Date(bars[index].epoch * 1000L))
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = dp(9f); typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val labelWidth = textPaint.measureText(time) + dp(12f)
        val left = (crosshairX - labelWidth / 2f).coerceIn(plot.left, plot.right - labelWidth)
        val top = plot.bottom + dp(5f)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (light) Color.rgb(45, 72, 105) else Color.rgb(40, 61, 88) }
        canvas.drawRoundRect(RectF(left, top, left + labelWidth, top + dp(17f)), dp(4f), dp(4f), bg)
        canvas.drawText(time, left + dp(6f), top + dp(12f), textPaint)
    }

    private fun drawAxisTag(canvas: Canvas, text: String, y: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(9f); typeface = android.graphics.Typeface.DEFAULT_BOLD }
        val w = p.measureText(text) + dp(10f)
        val h = dp(18f)
        val top = (y - h / 2f).coerceIn(plot.top, plot.bottom - h)
        val rect = RectF(plot.right + dp(3f), top, min(width - dp(2f), plot.right + dp(3f) + w), top + h)
        p.color = color
        canvas.drawRoundRect(rect, dp(3f), dp(3f), p)
        p.color = Color.WHITE
        canvas.drawText(text, rect.left + dp(5f), rect.bottom - dp(5f), p)
    }

    private fun drawPriceTag(canvas: Canvas, text: String, y: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(9f); typeface = android.graphics.Typeface.DEFAULT_BOLD }
        val w = p.measureText(text) + dp(12f)
        val top = (y - dp(17f)).coerceIn(plot.top, plot.bottom - dp(20f))
        val rect = RectF(plot.left + dp(4f), top, plot.left + dp(4f) + w, top + dp(18f))
        p.color = color
        canvas.drawRoundRect(rect, dp(4f), dp(4f), p)
        p.color = Color.rgb(8, 15, 27)
        canvas.drawText(text, rect.left + dp(6f), rect.bottom - dp(5f), p)
    }

    private fun drawTimeAxis(canvas: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = axis.color; textSize = dp(8.5f); textAlign = Paint.Align.CENTER
        }
        for (i in 0..4) {
            val index = (startIndex + visibleCount * i / 4f).toInt().coerceIn(0, bars.lastIndex)
            val x = plot.left + plot.width() * i / 4f
            val stamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(java.util.Date(bars[index].epoch * 1000L))
            canvas.drawText(stamp, x, height - dp(9f), p)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bars.isEmpty()) return false

        val x = event.x.coerceIn(plot.left, plot.right)
        val y = event.y.coerceIn(plot.top, plot.bottom)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val onPriceAxis = event.x > plot.right && event.x < width &&
                    event.y >= plot.top && event.y <= plot.bottom
                if (!plot.contains(event.x, event.y) && !onPriceAxis) return false

                parent?.requestDisallowInterceptTouchEvent(true)
                downX = x
                downY = y
                lastX = x
                lastY = y
                crosshairX = x
                crosshairY = y
                crosshairVisible = true
                moved = false
                startPriceOffset = priceOffset
                originalAlertPrice = alertPrice
                selectedMarkIndex = -1

                if (onPriceAxis) {
                    gestureMode = GestureMode.PRICE_SCALE
                    return true
                }

                when {
                    alertPlacementMode || (alertPrice != null && abs(y - yFor(alertPrice!!)) <= dp(20f)) -> {
                        gestureMode = GestureMode.PLACE_ALERT
                        previewPrice = valueForY(y)
                        alertPrice = previewPrice
                    }
                    drawingTool != DrawingTool.NONE -> {
                        gestureMode = GestureMode.DRAW
                        val index = indexForX(x)
                        val value = valueForY(y)
                        activeMark = ChartMark(drawingTool, index, value, index, value)
                    }
                    else -> {
                        val hit = findMarkAt(x, y)
                        if (hit >= 0) {
                            selectedMarkIndex = hit
                            originalSelectedMark = marks[hit]
                            gestureMode = GestureMode.EDIT_MARK
                        } else {
                            gestureMode = GestureMode.PAN
                            autoFollow = false
                        }
                    }
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2 && !alertPlacementMode &&
                    gestureMode != GestureMode.DRAW && gestureMode != GestureMode.PLACE_ALERT) {
                    gestureMode = GestureMode.PINCH
                    pinchStartDistance = pointerDistance(event).coerceAtLeast(1f)
                    pinchStartVisibleCount = visibleCount
                    pinchStartPriceZoom = priceZoom
                    val centerX = (event.getX(0) + event.getX(1)) / 2f
                    val centerY = (event.getY(0) + event.getY(1)) / 2f
                    pinchAnchorIndex = indexForX(centerX.coerceIn(plot.left, plot.right))
                    pinchAnchorPrice = valueForY(centerY.coerceIn(plot.top, plot.bottom))
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                moved = true
                crosshairX = x
                crosshairY = y
                if (gestureMode == GestureMode.PINCH && event.pointerCount >= 2) {
                    val distance = pointerDistance(event).coerceAtLeast(1f)
                    val factor = (distance / pinchStartDistance).coerceIn(0.25f, 4f)
                    val centerX = (event.getX(0) + event.getX(1)) / 2f
                    val centerY = (event.getY(0) + event.getY(1)) / 2f
                    val anchorRatio = ((centerX - plot.left) / plot.width().coerceAtLeast(1f)).coerceIn(0f, 1f)

                    visibleCount = (pinchStartVisibleCount / factor)
                        .coerceIn(12f, bars.size.toFloat().coerceAtLeast(12f))
                    startIndex = (pinchAnchorIndex - visibleCount * anchorRatio)
                        .coerceIn(0f, maxStartIndex())

                    val anchorY = centerY.coerceIn(plot.top, plot.bottom)
                    priceZoom = (pinchStartPriceZoom * factor).coerceIn(0.25f, 8f)
                    calculatePriceRange()
                    priceOffset += pinchAnchorPrice - valueForY(anchorY)
                } else {
                    when (gestureMode) {
                        GestureMode.PAN -> {
                            val dx = x - lastX
                            val dy = y - lastY
                            startIndex = (startIndex - dx / plot.width().coerceAtLeast(1f) * visibleCount)
                                .coerceIn(0f, maxStartIndex())
                            val range = (maxPrice - minPrice).coerceAtLeast(0.0000001)
                            priceOffset += dy.toDouble() * range / plot.height().coerceAtLeast(1f)
                        }
                        GestureMode.PRICE_SCALE -> {
                            val anchorPrice = valueForY(downY)
                            val factor = kotlin.math.exp((y - lastY) * 0.007f)
                            priceZoom = (priceZoom * factor).coerceIn(0.25f, 8f)
                            calculatePriceRange()
                            priceOffset += anchorPrice - valueForY(downY)
                        }
                        GestureMode.PLACE_ALERT -> {
                            previewPrice = valueForY(y)
                            alertPrice = previewPrice
                        }
                        GestureMode.DRAW -> {
                            val old = activeMark ?: return true
                            val nextIndex = indexForX(x)
                            val nextPrice = valueForY(y)
                            activeMark = if (old.tool == DrawingTool.HORIZONTAL) {
                                old.copy(index2 = nextIndex, price1 = nextPrice, price2 = nextPrice)
                            } else old.copy(index2 = nextIndex, price2 = nextPrice)
                        }
                        GestureMode.EDIT_MARK -> {
                            val original = originalSelectedMark ?: return true
                            val deltaIndex = indexForX(x) - indexForX(downX)
                            val deltaPrice = valueForY(y) - valueForY(downY)
                            val updated = if (original.tool == DrawingTool.HORIZONTAL) {
                                original.copy(price1 = original.price1 + deltaPrice, price2 = original.price2 + deltaPrice)
                            } else {
                                original.copy(
                                    index1 = original.index1 + deltaIndex,
                                    index2 = original.index2 + deltaIndex,
                                    price1 = original.price1 + deltaPrice,
                                    price2 = original.price2 + deltaPrice
                                )
                            }
                            if (selectedMarkIndex in marks.indices) marks[selectedMarkIndex] = updated
                        }
                        else -> Unit
                    }
                }
                lastX = x
                lastY = y
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (gestureMode == GestureMode.PINCH) {
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    if (remaining < event.pointerCount) {
                        lastX = event.getX(remaining).coerceIn(plot.left, plot.right)
                        lastY = event.getY(remaining).coerceIn(plot.top, plot.bottom)
                    }
                    gestureMode = GestureMode.PAN
                    autoFollow = false
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                when (gestureMode) {
                    GestureMode.PLACE_ALERT -> {
                        val value = previewPrice ?: valueForY(y)
                        alertPlacementMode = false
                        previewPrice = null
                        alertPrice = value
                        onAlertPlaced?.invoke(value)
                    }
                    GestureMode.DRAW -> {
                        activeMark?.let { mark ->
                            val shouldKeep = mark.tool == DrawingTool.HORIZONTAL ||
                                abs(mark.index2 - mark.index1) > 0.15f ||
                                abs(mark.price2 - mark.price1) > 0.0
                            if (shouldKeep) marks.add(mark)
                        }
                        activeMark = null
                        drawingTool = DrawingTool.NONE
                    }
                    else -> Unit
                }
                gestureMode = GestureMode.NONE
                originalSelectedMark = null
                originalAlertPrice = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                activeMark = null
                if (gestureMode == GestureMode.EDIT_MARK &&
                    selectedMarkIndex in marks.indices && originalSelectedMark != null) {
                    marks[selectedMarkIndex] = originalSelectedMark!!
                }
                if (gestureMode == GestureMode.PLACE_ALERT) alertPrice = originalAlertPrice
                gestureMode = GestureMode.NONE
                originalSelectedMark = null
                previewPrice = null
                originalAlertPrice = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun findMarkAt(x: Float, y: Float): Int {
        val tolerance = dp(15f)
        for (i in marks.indices.reversed()) {
            val mark = marks[i]
            val x1 = xForIndex(mark.index1); val y1 = yFor(mark.price1)
            val x2 = xForIndex(mark.index2); val y2 = yFor(mark.price2)
            val distance = when (mark.tool) {
                DrawingTool.HORIZONTAL -> abs(y - y1)
                DrawingTool.TRENDLINE -> pointLineDistance(x, y, x1, y1, x2, y2)
                DrawingTool.RAY -> pointLineDistance(x, y, x1, y1, plot.right, if (abs(x2 - x1) < 0.5f) y2 else y1 + (y2 - y1) / (x2 - x1) * (plot.right - x1))
                DrawingTool.RECTANGLE -> {
                    val left = min(x1, x2); val right = max(x1, x2); val top = min(y1, y2); val bottom = max(y1, y2)
                    min(min(abs(x - left), abs(x - right)), min(abs(y - top), abs(y - bottom)))
                }
                else -> Float.MAX_VALUE
            }
            if (distance <= tolerance) return i
        }
        return -1
    }

    private fun pointLineDistance(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1; val dy = y2 - y1
        if (abs(dx) + abs(dy) < 0.001f) return hypot(px - x1, py - y1)
        val t = (((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
        return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
    }

    private fun pointerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return hypot(dx, dy)
    }

    private fun maxStartIndex(): Float = max(0f, bars.size - visibleCount + 12f)

    private fun xForIndex(index: Float): Float = plot.left + ((index - startIndex) / visibleCount.coerceAtLeast(1f)) * plot.width()
    private fun indexForX(x: Float): Float = startIndex + ((x - plot.left) / plot.width().coerceAtLeast(1f)) * visibleCount
    private fun yFor(price: Double): Float = plot.top + ((maxPrice - price) / (maxPrice - minPrice)).toFloat() * plot.height()
    private fun valueForY(y: Float): Double {
        val ratio = ((y - plot.top) / plot.height().coerceAtLeast(1f)).toDouble()
        return maxPrice - ratio * (maxPrice - minPrice)
    }

    private fun format(value: Double): String = String.format(java.util.Locale.US, "%." + pricePrecision + "f", value)

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
