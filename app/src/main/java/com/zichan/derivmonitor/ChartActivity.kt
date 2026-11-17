package com.zichan.derivmonitor

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class ChartActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SYMBOL = "symbol"
        const val EXTRA_NAME = "name"
        const val EXTRA_CATEGORY = "category"
    }
    private val endpoint = "wss://api.derivws.com/trading/v1/options/ws/public"
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(25, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var socketReady = false
    private var symbol = ""
    private var displayName = ""
    private var category = ""
    private var timeframe = "5m"
    private var lineMode = false
    private var bars = mutableListOf<CandleBar>()
    private lateinit var chart: MarketChartView
    private lateinit var status: TextView
    private lateinit var price: TextView
    private lateinit var timeframeRow: LinearLayout
    private lateinit var chartTypeRow: LinearLayout
    private var isLight = false
    private var emaEnabled = true
    private var emaPeriod = 9
    private var emaShift = 0
    private lateinit var emaSettingsButton: TextView
    private val frames = listOf("1m", "2m", "3m", "5m", "10m", "15m", "30m", "1h", "2h", "4h", "8h", "1D", "1W", "1M")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        symbol = intent.getStringExtra(EXTRA_SYMBOL).orEmpty()
        displayName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        category = intent.getStringExtra(EXTRA_CATEGORY).orEmpty()
        isLight = PrefsManager.getTheme(this) == "light"
        emaEnabled = PrefsManager.getEmaEnabled(this)
        emaPeriod = PrefsManager.getEmaPeriod(this)
        emaShift = PrefsManager.getEmaShift(this)
        if (symbol.isBlank()) { finish(); return }
        buildScreen(); connect()
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg()) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(18), dp(16), dp(26)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(action("‹", "#FFFFFF", "#17243A") { finish() })
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) } }
        titleBox.addView(label(displayName, 16f, primary(), true))
        titleBox.addView(label(category.uppercase(Locale.US) + "  ·  " + symbol, 9.5f, secondary(), false))
        top.addView(titleBox); top.addView(action("↻", "#8BDFFF", "#123B5B") { loadHistory() })
        root.addView(top); root.addView(space(14))
        val quote = card()
        quote.addView(label("LIVE MARKET PRICE", 9.5f, accent(), true))
        price = label("Waiting for live tick…", 23f, primary(), true).apply { setPadding(0, dp(5), 0, dp(3)) }
        quote.addView(price)
        status = label("●  CONNECTING TO DERIV", 10f, "#FBBF24", true); quote.addView(status)
        root.addView(quote); root.addView(space(13))
        root.addView(section("CHART VIEW"))
        chartTypeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(chartTypeRow)
        val frameScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        timeframeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(10)) }
        frameScroll.addView(timeframeRow); root.addView(frameScroll)

        val indicatorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(10))
        }
        val emaSwitch = Switch(this).apply {
            text = "EMA"
            textSize = 11f
            setTextColor(Color.parseColor(primary()))
            isChecked = emaEnabled
            setOnCheckedChangeListener { _, checked ->
                emaEnabled = checked
                PrefsManager.setEmaEnabled(this@ChartActivity, checked)
                if (::chart.isInitialized) chart.setEmaSettings(emaEnabled, emaPeriod, emaShift)
            }
        }
        emaSettingsButton = action("EMA 9  ·  SHIFT 0", "#8BE5B1", "#123D35") { showEmaSettingsDialog() }
        updateEmaButton()
        indicatorRow.addView(emaSwitch)
        indicatorRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        indicatorRow.addView(emaSettingsButton)
        root.addView(indicatorRow)

        val alertRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        alertRow.addView(action("＋  SET PRICE ALERT", "#07111F", "#F5B141") {
            chart.drawingTool = DrawingTool.NONE; chart.alertPlacementMode = true
            toast("Drag the horizontal line to your target price, then release")
        }, LinearLayout.LayoutParams(0, -2, 1f))
        alertRow.addView(spaceWidth(8))
        alertRow.addView(action("REMOVE ALERT", "#FCA5A5", "#402331") {
            PrefsManager.removeAlert(this, symbol); chart.alertPrice = null; toast("Price alert removed")
        })
        root.addView(alertRow); root.addView(space(14))
        chart = MarketChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(430))
            onAlertPlaced = { value -> confirmAlert(value) }
        }
        chart.setEmaSettings(emaEnabled, emaPeriod, emaShift)
        chart.setData(emptyList(), isLight)
        root.addView(chart); root.addView(space(10))

        val chartActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        chartActions.addView(action("RESET VIEW", "#A8DFFF", "#123B5B") { chart.resetViewport() })
        chartActions.addView(spaceWidth(7))
        chartActions.addView(action("CLEAR DRAWINGS", "#D8C6FF", "#2B2150") { chart.clearDrawings() })
        root.addView(chartActions)
        root.addView(space(10))
        root.addView(section("DRAWING TOOLS"))
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(action("HORIZONTAL", "#D8C6FF", "#2B2150") { chart.drawingTool = DrawingTool.HORIZONTAL; toast("Tap and drag to place a horizontal line") })
        tools.addView(spaceWidth(6))
        tools.addView(action("TRENDLINE", "#D8C6FF", "#2B2150") { chart.drawingTool = DrawingTool.TRENDLINE; toast("Drag to draw a trendline") })
        tools.addView(spaceWidth(6))
        tools.addView(action("RAY", "#D8C6FF", "#2B2150") { chart.drawingTool = DrawingTool.RAY; toast("Draw a ray on the chart") })
        tools.addView(spaceWidth(6))
        tools.addView(action("RECTANGLE", "#D8C6FF", "#2B2150") { chart.drawingTool = DrawingTool.RECTANGLE; toast("Drag to draw a rectangle") })
        tools.addView(spaceWidth(6))
        tools.addView(action("CLEAR", "#FCA5A5", "#402331") { chart.clearDrawings() })
        val toolScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tools) }
        root.addView(toolScroll); root.addView(space(12))
        root.addView(label("Drag to pan. Pinch to zoom. Drag the price axis area to adjust scale. Drawings stay anchored to market price and time.", 10f, secondary(), false))
        scroll.addView(root); setContentView(scroll)
        renderTimeframes(); renderChartType()
        chart.alertPrice = PrefsManager.getAlerts(this).firstOrNull { it.symbol == symbol }?.targetPrice
    }

    private fun renderTimeframes() {
        timeframeRow.removeAllViews()
        frames.forEach { frame ->
            val selected = frame == timeframe
            val button = action(frame, if (selected) "#FFFFFF" else secondary(), if (selected) "#1464A5" else if (isLight) "#FFFFFF" else "#15243A") {
                timeframe = frame; renderTimeframes(); loadHistory()
            }
            timeframeRow.addView(button, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        }
    }

    private fun renderChartType() {
        chartTypeRow.removeAllViews()
        chartTypeRow.addView(action("CANDLESTICK", if (!lineMode) "#FFFFFF" else secondary(), if (!lineMode) "#1464A5" else "#15243A") {
            lineMode = false; chart.lineMode = false; renderChartType()
        })
        chartTypeRow.addView(spaceWidth(8))
        chartTypeRow.addView(action("LINE", if (lineMode) "#FFFFFF" else secondary(), if (lineMode) "#1464A5" else "#15243A") {
            lineMode = true; chart.lineMode = true; renderChartType()
        })
    }

    private fun connect() {
        setStatus("●  CONNECTING TO DERIV", "#FBBF24")
        socket = client.newWebSocket(Request.Builder().url(endpoint).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketReady = true
                requestHistory(webSocket)
                webSocket.send(JSONObject().put("ticks", symbol).put("subscribe", 1).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.has("error")) {
                        val message = json.getJSONObject("error").optString("message", "Deriv API error")
                        runOnUiThread { setStatus("●  " + message.take(60), "#FCA5A5") }; return
                    }
                    when (json.optString("msg_type")) {
                        "candles", "history" -> handleHistory(json)
                        "tick" -> handleTick(json)
                    }
                } catch (_: Exception) { runOnUiThread { setStatus("●  INVALID MARKET DATA", "#FCA5A5") } }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socketReady = false
                runOnUiThread { setStatus("●  CONNECTION ERROR  ·  " + (response?.code ?: t.javaClass.simpleName), "#FCA5A5") }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { socketReady = false }
        })
    }

    private fun requestHistory(ws: WebSocket) {
        ws.send(JSONObject().put("ticks_history", symbol).put("adjust_start_time", 1)
            .put("count", if (timeframe == "1W" || timeframe == "1M") 500 else 180)
            .put("end", "latest").put("style", "candles").put("granularity", apiGranularity()).toString())
    }

    private fun loadHistory() {
        if (!socketReady) return
        bars.clear(); chart.setData(emptyList(), isLight)
        setStatus("●  LOADING " + timeframe + " HISTORY", "#FBBF24")
        socket?.let { requestHistory(it) }
    }

    private fun apiGranularity(): Int = when (timeframe) {
        "1m" -> 60; "2m" -> 120; "3m" -> 180; "5m" -> 300; "10m" -> 600; "15m" -> 900; "30m" -> 1800
        "1h" -> 3600; "2h" -> 7200; "4h" -> 14400; "8h" -> 28800; "1D", "1W", "1M" -> 86400; else -> 300
    }

    private fun handleHistory(json: JSONObject) {
        val array = json.optJSONArray("candles") ?: json.optJSONObject("history")?.optJSONArray("candles") ?: return
        val raw = mutableListOf<CandleBar>()
        for (i in 0 until array.length()) {
            val c = array.optJSONObject(i) ?: continue
            raw.add(CandleBar(c.optLong("epoch"), c.optDouble("open"), c.optDouble("high"), c.optDouble("low"), c.optDouble("close")))
        }
        val ready = if (timeframe == "1W" || timeframe == "1M") aggregate(raw, timeframe) else raw
        runOnUiThread {
            bars = ready.takeLast(500).toMutableList(); chart.setData(bars, isLight)
            setStatus("●  LIVE  ·  " + bars.size + " CANDLES", "#34D399")
            bars.lastOrNull()?.let { price.text = formatPrice(it.close) }
        }
    }

    private fun aggregate(input: List<CandleBar>, frame: String): List<CandleBar> {
        val result = mutableListOf<CandleBar>()
        input.sortedBy { it.epoch }.forEach { c ->
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply { timeInMillis = c.epoch * 1000L }
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            if (frame == "1W") {
                cal.setFirstDayOfWeek(Calendar.MONDAY)
                val diff = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
                cal.add(Calendar.DAY_OF_MONTH, -diff)
            } else cal.set(Calendar.DAY_OF_MONTH, 1)
            val key = cal.timeInMillis / 1000L
            val last = result.lastOrNull()
            if (last == null || last.epoch != key) result.add(CandleBar(key, c.open, c.high, c.low, c.close))
            else result[result.lastIndex] = last.copy(high = maxOf(last.high, c.high), low = minOf(last.low, c.low), close = c.close)
        }
        return result
    }

    private fun handleTick(json: JSONObject) {
        val tick = json.optJSONObject("tick") ?: return
        if (tick.optString("symbol") != symbol) return
        val quote = tick.optDouble("quote", Double.NaN); if (quote.isNaN()) return
        val epoch = tick.optLong("epoch", System.currentTimeMillis() / 1000L)
        runOnUiThread {
            price.text = formatPrice(quote)
            val bucket = candleBucket(epoch)
            val last = bars.lastOrNull()
            if (last == null || last.epoch != bucket) bars.add(CandleBar(bucket, quote, quote, quote, quote))
            else bars[bars.lastIndex] = last.copy(high = maxOf(last.high, quote), low = minOf(last.low, quote), close = quote)
            if (bars.size > 500) bars.removeAt(0)
            chart.setData(bars, isLight)
        }
    }

    private fun candleBucket(epoch: Long): Long {
        if (timeframe == "1W" || timeframe == "1M") {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply { timeInMillis = epoch * 1000L }
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            if (timeframe == "1W") {
                val diff = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
                cal.add(Calendar.DAY_OF_MONTH, -diff)
            } else cal.set(Calendar.DAY_OF_MONTH, 1)
            return cal.timeInMillis / 1000L
        }
        val span = apiGranularity().toLong()
        return epoch / span * span
    }

    private fun updateEmaButton() {
        if (::emaSettingsButton.isInitialized) {
            emaSettingsButton.text = "EMA $emaPeriod  ·  SHIFT $emaShift"
            emaSettingsButton.alpha = if (emaEnabled) 1f else 0.55f
        }
    }

    private fun showEmaSettingsDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(2))
        }
        val periodInput = EditText(this).apply {
            hint = "Period (1–200)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(emaPeriod.toString())
            setSingleLine(true)
        }
        val shiftInput = EditText(this).apply {
            hint = "Shift (-100 to 100)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(emaShift.toString())
            setSingleLine(true)
        }
        box.addView(label("PERIOD · CLOSE PRICE SOURCE", 10f, accent(), true))
        box.addView(periodInput)
        box.addView(space(8))
        box.addView(label("PLOT SHIFT · 0 = NO SHIFT", 10f, accent(), true))
        box.addView(shiftInput)
        AlertDialog.Builder(this)
            .setTitle("EMA Indicator Settings")
            .setView(box)
            .setPositiveButton("APPLY") { _, _ ->
                val nextPeriod = (periodInput.text.toString().toIntOrNull() ?: 9).coerceIn(1, 200)
                val nextShift = (shiftInput.text.toString().toIntOrNull() ?: 0).coerceIn(-100, 100)
                emaPeriod = nextPeriod
                emaShift = nextShift
                PrefsManager.setEmaPeriod(this, emaPeriod)
                PrefsManager.setEmaShift(this, emaShift)
                chart.setEmaSettings(emaEnabled, emaPeriod, emaShift)
                updateEmaButton()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun confirmAlert(value: Double) {
        AlertDialog.Builder(this)
            .setTitle("Confirm price alert")
            .setMessage("Set a price alert for " + displayName + " at " + formatPrice(value) + "?")
            .setPositiveButton("SET ALERT") { _, _ ->
                PrefsManager.saveAlert(this, PriceAlert(symbol, displayName, value))
                val watchlist = PrefsManager.getSelectedSymbols(this)
                if (watchlist.none { it.code == symbol }) {
                    watchlist.add(MarketSymbol(symbol, displayName, category))
                    PrefsManager.saveSelectedSymbols(this, watchlist)
                }
                chart.alertPrice = value
                if (Settings.canDrawOverlays(this)) {
                    val i = Intent(this, FloatingMonitorService::class.java).apply { action = FloatingMonitorService.ACTION_REFRESH }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
                } else {
                    AlertDialog.Builder(this).setTitle("Enable background monitoring")
                        .setMessage("Allow the floating monitor permission so price alerts can be checked while you use other apps.")
                        .setPositiveButton("ENABLE") { _, _ -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                        .setNegativeButton("Later", null).show()
                }
                toast("Price alert saved")
            }
            .setNegativeButton("CANCEL") { _, _ -> chart.alertPrice = PrefsManager.getAlerts(this).firstOrNull { it.symbol == symbol }?.targetPrice }
            .show()
    }

    private fun setStatus(text: String, color: String) { status.text = text; status.setTextColor(Color.parseColor(color)) }
    private fun formatPrice(value: Double): String {
        val digits = if (value >= 10000) 2 else if (value >= 100) 3 else 5
        return String.format(Locale.US, "%." + digits + "f", value)
    }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(13), dp(14), dp(13)); background = panel(if (isLight) "#FFFFFF" else "#121F33", border()) }
    private fun section(t: String) = label(t, 11f, accent(), true).apply { setPadding(0, dp(3), 0, dp(9)) }
    private fun label(t: String, s: Float, c: String, b: Boolean) = TextView(this).apply { text=t; setTextColor(Color.parseColor(c)); setTextSize(TypedValue.COMPLEX_UNIT_SP,s); if(b) typeface=Typeface.DEFAULT_BOLD }
    private fun action(t: String, fg: String, bg: String, click: () -> Unit) = TextView(this).apply {
        text=t; textSize=10.5f; typeface=Typeface.DEFAULT_BOLD; gravity=Gravity.CENTER
        setTextColor(Color.parseColor(fg)); setPadding(dp(12),dp(10),dp(12),dp(10)); background=panel(bg,bg); setOnClickListener{click()}
    }
    private fun action(t: String, fg: String, bg: String, lp: LinearLayout.LayoutParams, click: () -> Unit): TextView = action(t,fg,bg,click).apply { layoutParams=lp }
    private fun panel(bg: String, stroke: String) = GradientDrawable().apply { cornerRadius=dp(14).toFloat(); setColor(Color.parseColor(bg)); setStroke(dp(1),Color.parseColor(stroke)) }
    private fun space(h:Int)=View(this).apply{layoutParams=LinearLayout.LayoutParams(1,dp(h))}
    private fun spaceWidth(w:Int)=View(this).apply{layoutParams=LinearLayout.LayoutParams(dp(w),1)}
    private fun dp(v:Int)=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,v.toFloat(),resources.displayMetrics).toInt()
    private fun bg()=Color.parseColor(if(isLight)"#F4F7FB" else "#080D19")
    private fun primary()=if(isLight)"#101828" else "#F7FAFF"
    private fun secondary()=if(isLight)"#65748B" else "#91A0B6"
    private fun accent()=if(isLight)"#087BB8" else "#67D5FF"
    private fun border()=if(isLight)"#D9E2EE" else "#27364C"
    override fun onDestroy() { socket?.cancel(); client.dispatcher.executorService.shutdown(); super.onDestroy() }
}
