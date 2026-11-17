package com.zichan.derivmonitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

data class CandleBar(val epoch: Long, val open: Double, val high: Double, val low: Double, val close: Double)
enum class DrawingTool { NONE, TRENDLINE, RAY, RECTANGLE }
private data class ChartMark(val tool: DrawingTool, val x1: Float, val y1: Float, val x2: Float, val y2: Float)

class MarketChartView(context: Context) : View(context) {
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(45, 150, 170, 195); strokeWidth = dp(1f) }
    private val up = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(34, 197, 150); strokeWidth = dp(1.4f) }
    private val down = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(244, 91, 111); strokeWidth = dp(1.4f) }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(145, 160, 182); textSize = dp(10f) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(61, 177, 255); strokeWidth = dp(2f); style = Paint.Style.STROKE }
    private val alertPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 177, 65); strokeWidth = dp(1.5f); pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f) }
    private val toolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(183, 149, 255); strokeWidth = dp(1.7f); style = Paint.Style.STROKE }
    private var bars: List<CandleBar> = emptyList()
    private val marks = mutableListOf<ChartMark>()
    var lineMode = false
        set(value) { field = value; invalidate() }
    var drawingTool = DrawingTool.NONE
        set(value) { field = value; invalidate() }
    var alertPrice: Double? = null
        set(value) { field = value; invalidate() }
    var alertPlacementMode = false
        set(value) { field = value; invalidate() }
    var onAlertPlaced: ((Double) -> Unit)? = null
    private var activeMark: ChartMark? = null
    private var previewPrice: Double? = null
    private var light = false
    private var minPrice = 0.0
    private var maxPrice = 1.0
    private var plot = RectF()

    init { setLayerType(View.LAYER_TYPE_SOFTWARE, null) }

    fun setData(data: List<CandleBar>, lightMode: Boolean = false) {
        bars = data.takeLast(140); light = lightMode; invalidate()
    }
    fun clearDrawings() { marks.clear(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(if (light) Color.rgb(249, 251, 254) else Color.rgb(10, 17, 30))
        plot = RectF(dp(12f), dp(20f), width - dp(65f), height - dp(28f))
        axis.color = if (light) Color.rgb(55, 69, 88) else Color.rgb(145, 160, 182)
        if (bars.isEmpty()) {
            axis.textSize = dp(13f); canvas.drawText("Loading historical candles…", plot.left + dp(10f), height / 2f, axis); return
        }
        minPrice = bars.minOf { it.low }; maxPrice = bars.maxOf { it.high }
        val padding = (maxPrice - minPrice) * 0.08
        if (padding <= 0.0) { minPrice -= 1.0; maxPrice += 1.0 } else { minPrice -= padding; maxPrice += padding }
        if (maxPrice <= minPrice) maxPrice = minPrice + 1.0
        for (i in 0..4) {
            val y = plot.top + plot.height() * i / 4f
            canvas.drawLine(plot.left, y, plot.right, y, grid)
            val value = maxPrice - (maxPrice - minPrice) * i / 4.0
            canvas.drawText(format(value), plot.right + dp(6f), y + dp(3f), axis)
        }
        for (i in 0..5) {
            val x = plot.left + plot.width() * i / 5f
            canvas.drawLine(x, plot.top, x, plot.bottom, grid)
        }
        if (lineMode) drawLineChart(canvas) else drawCandles(canvas)
        drawMarks(canvas)
        alertPrice?.let { price ->
            val y = yFor(price); canvas.drawLine(plot.left, y, plot.right, y, alertPaint)
            axis.color = Color.rgb(245, 177, 65); axis.textSize = dp(9f)
            canvas.drawText("ALERT  " + format(price), plot.left + dp(5f), y - dp(5f), axis)
        }
        if (alertPlacementMode && previewPrice != null) {
            val y = yFor(previewPrice!!); canvas.drawLine(plot.left, y, plot.right, y, alertPaint)
        }
    }

    private fun drawCandles(canvas: Canvas) {
        val step = plot.width() / bars.size.coerceAtLeast(1)
        val bodyWidth = (step * 0.62f).coerceIn(dp(2f), dp(12f))
        bars.forEachIndexed { index, candle ->
            val x = plot.left + step * (index + 0.5f)
            val wick = if (candle.close >= candle.open) up else down
            canvas.drawLine(x, yFor(candle.high), x, yFor(candle.low), wick)
            val top = yFor(max(candle.open, candle.close)); val bottom = yFor(min(candle.open, candle.close))
            val rect = RectF(x - bodyWidth / 2f, top, x + bodyWidth / 2f, max(top + dp(1f), bottom))
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = wick.color; style = Paint.Style.FILL }
            canvas.drawRect(rect, fill)
        }
    }

    private fun drawLineChart(canvas: Canvas) {
        val path = Path(); val step = plot.width() / (bars.size - 1).coerceAtLeast(1)
        bars.forEachIndexed { index, candle ->
            val x = plot.left + step * index; val y = yFor(candle.close)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }

    private fun drawMarks(canvas: Canvas) {
        val all = marks.toMutableList(); activeMark?.let { all.add(it) }
        canvas.save()
        canvas.clipRect(plot)
        all.forEach { mark ->
            when (mark.tool) {
                DrawingTool.TRENDLINE -> canvas.drawLine(mark.x1, mark.y1, mark.x2, mark.y2, toolPaint)
                DrawingTool.RAY -> {
                    val dx = mark.x2 - mark.x1; val dy = mark.y2 - mark.y1
                    val endY = if (dx == 0f) mark.y2 else mark.y1 + dy / dx * (plot.right - mark.x1)
                    canvas.drawLine(mark.x1, mark.y1, plot.right, endY, toolPaint)
                }
                DrawingTool.RECTANGLE -> canvas.drawRect(
                    min(mark.x1, mark.x2), min(mark.y1, mark.y2),
                    max(mark.x1, mark.x2), max(mark.y1, mark.y2), toolPaint
                )
                else -> Unit
            }
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bars.isEmpty()) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x.coerceIn(plot.left, plot.right); val y = event.y.coerceIn(plot.top, plot.bottom)
                if (alertPlacementMode) { previewPrice = valueForY(y); alertPrice = previewPrice }
                else if (drawingTool != DrawingTool.NONE) activeMark = ChartMark(drawingTool, x, y, x, y)
                else return false
                invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                val x = event.x.coerceIn(plot.left, plot.right); val y = event.y.coerceIn(plot.top, plot.bottom)
                if (alertPlacementMode) { previewPrice = valueForY(y); alertPrice = previewPrice }
                else activeMark = activeMark?.copy(x2 = x, y2 = y)
                invalidate(); return true
            }
            MotionEvent.ACTION_UP -> {
                if (alertPlacementMode) {
                    val value = previewPrice ?: valueForY(event.y.coerceIn(plot.top, plot.bottom))
                    alertPlacementMode = false; previewPrice = null; alertPrice = value; onAlertPlaced?.invoke(value)
                } else { activeMark?.let { marks.add(it) }; activeMark = null; drawingTool = DrawingTool.NONE }
                invalidate(); return true
            }
        }
        return true
    }

    private fun yFor(price: Double): Float = plot.top + ((maxPrice - price) / (maxPrice - minPrice)).toFloat() * plot.height()
    private fun valueForY(y: Float): Double {
        val ratio = ((y - plot.top) / plot.height().coerceAtLeast(1f)).toDouble()
        return maxPrice - ratio * (maxPrice - minPrice)
    }
    private fun format(v: Double): String {
        val digits = when { v >= 10000 -> 2; v >= 100 -> 3; v >= 1 -> 4; else -> 5 }
        return String.format(java.util.Locale.US, "%." + digits + "f", v)
    }
    private fun dp(v: Float) = v * resources.displayMetrics.density
}
