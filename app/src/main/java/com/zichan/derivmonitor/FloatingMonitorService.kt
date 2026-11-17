package com.zichan.derivmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class FloatingMonitorService : Service() {

    private lateinit var windowManager: WindowManager
    private var rootContainer: LinearLayout? = null
    private var headerTv: TextView? = null
    private var rowsContainer: LinearLayout? = null
    private var minimizedBadge: TextView? = null
    private var isMinimized = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webSocket: WebSocket? = null
    private var endpointIdx = 0
    private var isSocketOpen = false

    private val endpoints = listOf(
        "wss://ws.binaryws.com/websockets/v3?app_id=1089",
        "wss://frontend.binaryws.com/websockets/v3?app_id=1089",
        "wss://ws.derivws.com/websockets/v3?app_id=1089",
        "wss://green.binaryws.com/websockets/v3?app_id=1089",
        "wss://blue.binaryws.com/websockets/v3?app_id=1089"
    )

    private val dohBootstrapClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val customDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            // 1. Try Cloudflare DoH over direct IP 1.1.1.1 to bypass ISP DNS blocks
            try {
                val req = Request.Builder()
                    .url("https://1.1.1.1/dns-query?name=$hostname&type=A")
                    .header("Accept", "application/dns-json")
                    .build()
                dohBootstrapClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string()
                    if (!body.isNullOrEmpty()) {
                        val json = JSONObject(body)
                        val ans = json.optJSONArray("Answer")
                        if (ans != null) {
                            val list = mutableListOf<InetAddress>()
                            for (i in 0 until ans.length()) {
                                val obj = ans.getJSONObject(i)
                                if (obj.optInt("type") == 1) {
                                    val ip = obj.optString("data")
                                    if (ip.isNotEmpty()) {
                                        list.add(InetAddress.getByName(ip))
                                    }
                                }
                            }
                            if (list.isNotEmpty()) return list
                        }
                    }
                }
            } catch (_: Exception) {}

            // 2. Fallback to system DNS preferring IPv4
            val sys = Dns.SYSTEM.lookup(hostname)
            val ipv4 = sys.filterIsInstance<Inet4Address>()
            return if (ipv4.isNotEmpty()) ipv4 else sys
        }
    }

    private val client = OkHttpClient.Builder()
        .dns(customDns)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val priceViews = mutableMapOf<String, TextView>()
    private val aliasToRowKey = mutableMapOf<String, String>()
    private val lastPrices = mutableMapOf<String, Double>()
    private var activeSymbols = listOf<MarketSymbol>()

    private val pingLoop = object : Runnable {
        override fun run() {
            try {
                if (isSocketOpen) {
                    webSocket?.send("""{"ping":1}""")
                }
            } catch (_: Exception) {}
            mainHandler.postDelayed(this, 20000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        createFloatingView()
        mainHandler.postDelayed(pingLoop, 20000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        showOverlayIfHidden()
        updateVisualStyling()
        val updated = PrefsManager.getSelectedSymbols(this)
        val oldCodes = activeSymbols.map { it.code }
        val newCodes = updated.map { it.code }
        if (oldCodes != newCodes || !isSocketOpen || priceViews.isEmpty()) {
            reloadPairsAndConnect()
        }
        return START_STICKY
    }

    private fun showOverlayIfHidden() {
        rootContainer?.visibility = View.VISIBLE
        if (isMinimized) {
            setMinimizedState(false)
        }
    }

    private fun createFloatingView() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val header = TextView(this).apply {
            text = "● CONNECTING..."
            setTextColor(Color.parseColor("#FACC15"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(3))
        }

        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val mini = TextView(this).apply {
            text = "📊"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            visibility = View.GONE
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }

        container.addView(header)
        container.addView(rows)
        container.addView(mini)

        rootContainer = container
        headerTv = header
        rowsContainer = rows
        minimizedBadge = mini

        updateVisualStyling()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 220
        }

        setupTouchListeners(container, params)
        windowManager.addView(container, params)
    }

    private fun updateVisualStyling() {
        val opacityPercent = PrefsManager.getOpacity(this)
        val alphaInt = ((opacityPercent / 100.0) * 255).toInt().coerceIn(60, 255)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(Color.argb(alphaInt, 15, 23, 42))
            setStroke(dp(1), Color.argb(130, 56, 189, 248))
        }
        rootContainer?.background = bg

        val textSize = PrefsManager.getTextSizeSp(this)
        for ((_, tv) in priceViews) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
        }
    }

    private fun setupTouchListeners(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isLongPressTriggered = false
        var lastTapTime = 0L

        val longPressRunnable = Runnable {
            isLongPressTriggered = true
            vibrateShort()
            view.visibility = View.GONE
            Toast.makeText(
                this,
                "প্রাইস মনিটর হাইড হয়েছে। আবার দেখতে অ্যাপটি ওপেন করুন।",
                Toast.LENGTH_SHORT
            ).show()
        }

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isLongPressTriggered = false
                    mainHandler.postDelayed(longPressRunnable, 850)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) {
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (!isLongPressTriggered) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)
                    if (!isLongPressTriggered && dx < 12 && dy < 12) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 320) {
                            setMinimizedState(!isMinimized)
                        } else if (isMinimized) {
                            setMinimizedState(false)
                        }
                        lastTapTime = now
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setMinimizedState(minimize: Boolean) {
        isMinimized = minimize
        headerTv?.visibility = if (minimize) View.GONE else View.VISIBLE
        rowsContainer?.visibility = if (minimize) View.GONE else View.VISIBLE
        minimizedBadge?.visibility = if (minimize) View.VISIBLE else View.GONE
    }

    private fun reloadPairsAndConnect() {
        activeSymbols = PrefsManager.getSelectedSymbols(this)
        rowsContainer?.removeAllViews()
        priceViews.clear()
        aliasToRowKey.clear()

        val textSize = PrefsManager.getTextSizeSp(this)

        for (sym in activeSymbols) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
            }

            val shortTitle = sym.displayName
                .replace(" Index", "")
                .replace(" (XAU/USD)", "")
                .replace(" (BTC)", "")
                .replace(" (ETH)", "")

            val nameTv = TextView(this).apply {
                text = "$shortTitle: "
                setTextColor(Color.parseColor("#E2E8F0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize - 0.5f)
                typeface = Typeface.DEFAULT_BOLD
            }

            val priceTv = TextView(this).apply {
                text = "..."
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
                typeface = Typeface.MONOSPACE
            }

            row.addView(nameTv)
            row.addView(priceTv)
            rowsContainer?.addView(row)
            priceViews[sym.code] = priceTv
            aliasToRowKey[sym.code] = sym.code
        }

        connectDerivWebSocket()
    }

    private fun sendTickSubscription(ws: WebSocket, symbolCode: String) {
        val req = JSONObject().apply {
            put("ticks_history", symbolCode)
            put("adjust_start_time", 1)
            put("count", 1)
            put("end", "latest")
            put("style", "ticks")
            put("subscribe", 1)
        }
        ws.send(req.toString())
    }

    private fun connectDerivWebSocket() {
        isSocketOpen = false
        webSocket?.cancel()
        if (activeSymbols.isEmpty()) return

        val endpointUrl = endpoints[endpointIdx % endpoints.size]
        mainHandler.post {
            headerTv?.text = "● CONNECTING..."
            headerTv?.setTextColor(Color.parseColor("#FACC15"))
        }

        val request = Request.Builder()
            .url(endpointUrl)
            .header("Origin", "https://app.deriv.com")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/126.0.0.0 Mobile Safari/537.36")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isSocketOpen = true
                mainHandler.post {
                    headerTv?.text = "● DERIV LIVE"
                    headerTv?.setTextColor(Color.parseColor("#4ADE80"))
                }

                ws.send("""{"active_symbols":"brief","product_type":"basic"}""")

                for (sym in activeSymbols) {
                    val normalized = when (sym.code.uppercase(Locale.US)) {
                        "CRASH50" -> "CRASH50N"
                        "BOOM50" -> "BOOM50N"
                        "CRASH150" -> "CRASH150N"
                        "BOOM150" -> "BOOM150N"
                        "CRASH300" -> "CRASH300N"
                        "BOOM300" -> "BOOM300N"
                        else -> sym.code
                    }
                    aliasToRowKey[normalized] = sym.code
                    sendTickSubscription(ws, normalized)
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val msgType = json.optString("msg_type")

                    if (msgType == "active_symbols") {
                        val arr = json.optJSONArray("active_symbols") ?: return
                        val byName = mutableMapOf<String, String>()
                        val validCodes = mutableSetOf<String>()
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            val c = item.optString("symbol")
                            val d = item.optString("display_name").lowercase(Locale.US)
                            if (c.isNotEmpty()) {
                                validCodes.add(c)
                                byName[d] = c
                            }
                        }
                        for (sym in activeSymbols) {
                            if (!validCodes.contains(sym.code)) {
                                val targetName = sym.displayName.lowercase(Locale.US)
                                val matched = byName[targetName]
                                    ?: byName.entries.firstOrNull {
                                        it.key.contains(targetName.replace(" index", ""))
                                    }?.value
                                if (matched != null && !aliasToRowKey.containsKey(matched)) {
                                    aliasToRowKey[matched] = sym.code
                                    sendTickSubscription(ws, matched)
                                }
                            }
                        }
                        return
                    }

                    if (json.has("error")) {
                        val err = json.getJSONObject("error")
                        val code = err.optString("code")
                        val echo = json.optJSONObject("echo_req")
                        val reqSym = echo?.optString("ticks_history") ?: ""
                        val rowKey = aliasToRowKey[reqSym] ?: reqSym
                        if (code == "MarketIsClosed") {
                            mainHandler.post {
                                priceViews[rowKey]?.text = "Closed"
                                priceViews[rowKey]?.setTextColor(Color.parseColor("#94A3B8"))
                            }
                        } else if (code == "InvalidSymbol" && reqSym.isNotEmpty()) {
                            val alt = if (reqSym.endsWith("N")) reqSym.dropLast(1) else "${reqSym}N"
                            if (!aliasToRowKey.containsKey(alt)) {
                                aliasToRowKey[alt] = rowKey
                                sendTickSubscription(ws, alt)
                            }
                        }
                        return
                    }

                    if (msgType == "history") {
                        val echo = json.optJSONObject("echo_req")
                        val reqSym = echo?.optString("ticks_history") ?: return
                        val rowKey = aliasToRowKey[reqSym] ?: reqSym
                        val prices = json.optJSONObject("history")?.optJSONArray("prices") ?: return
                        if (prices.length() > 0) {
                            val latestStr = prices.optString(prices.length() - 1)
                            val latestVal = latestStr.toDoubleOrNull()
                            if (latestVal != null) {
                                lastPrices[rowKey] = latestVal
                                mainHandler.post {
                                    val tv = priceViews[rowKey] ?: return@post
                                    tv.text = latestStr
                                    tv.setTextColor(Color.parseColor("#38BDF8"))
                                }
                            }
                        }
                        return
                    }

                    val tick = json.optJSONObject("tick") ?: return
                    val rawSymbol = tick.optString("symbol")
                    val rowKey = aliasToRowKey[rawSymbol] ?: rawSymbol
                    val quote = tick.optDouble("quote", Double.NaN)
                    if (quote.isNaN()) return
                    val pipSize = tick.optInt("pip_size", -1)
                    val quoteStr = if (pipSize >= 0) {
                        String.format(Locale.US, "%.${pipSize}f", quote)
                    } else {
                        tick.optString("quote")
                    }

                    val prev = lastPrices[rowKey]
                    lastPrices[rowKey] = quote

                    mainHandler.post {
                        val tv = priceViews[rowKey] ?: return@post
                        val diff = if (prev != null) quote - prev else 0.0
                        val arrow = when {
                            diff > 0 -> " ▲"
                            diff < 0 -> " ▼"
                            else -> ""
                        }
                        val isSpike = prev != null && abs(diff) > (prev * 0.0018) &&
                                (rawSymbol.startsWith("CRASH") || rawSymbol.startsWith("BOOM"))
                        val spikeTag = if (isSpike) " ⚡" else ""

                        tv.text = "$quoteStr$arrow$spikeTag"
                        when {
                            diff > 0 -> tv.setTextColor(Color.parseColor("#00E676"))
                            diff < 0 -> tv.setTextColor(Color.parseColor("#FF5252"))
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
    isSocketOpen = false

    val detail = response?.let {
        "HTTP ${it.code}: ${it.message}"
    } ?: "${t.javaClass.simpleName}: ${t.message ?: "Unknown error"}"

    mainHandler.post {
        headerTv?.text = "● ERROR: $detail"
        headerTv?.setTextColor(Color.RED)

        Toast.makeText(
            this@FloatingMonitorService,
            "Deriv Connection Error: $detail",
            Toast.LENGTH_LONG
        ).show()
    }

    endpointIdx = (endpointIdx + 1) % endpoints.size

    mainHandler.postDelayed({
        connectDerivWebSocket()
    }, 5000)
}
        })
    }

    private fun vibrateShort() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(45)
        }
    }

    private fun startForegroundNotification() {
        val channelId = "deriv_live_overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Deriv Live Price Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Deriv Live Monitor চালু আছে")
            .setContentText("ট্যাপ করে পেয়ার পরিবর্তন বা মনিটর সামনে আনুন")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .build()

        startForeground(101, notification)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.cancel()
        rootContainer?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_REFRESH = "com.zichan.derivmonitor.ACTION_REFRESH"
        const val ACTION_STOP = "com.zichan.derivmonitor.ACTION_STOP"
    }
}
