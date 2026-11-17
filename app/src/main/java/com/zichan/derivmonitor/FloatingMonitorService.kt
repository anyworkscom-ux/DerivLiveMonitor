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
import android.media.Ringtone
import android.media.RingtoneManager
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class FloatingMonitorService : Service() {

    private lateinit var windowManager: WindowManager
    private var root: LinearLayout? = null
    private var header: TextView? = null
    private var rows: LinearLayout? = null
    private var mini: TextView? = null
    private var minimized = false

    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val endpoint = "wss://api.derivws.com/trading/v1/options/ws/public"
    private var socket: WebSocket? = null
    private var socketOpen = false
    private var connecting = false
    private var generation = 0
    private var retryAttempt = 0
    private var retryRunnable: Runnable? = null
    private var selected = emptyList<MarketSymbol>()
    private var resolved = linkedMapOf<String, String>() // API symbol -> saved watchlist key
    private val priceViews = mutableMapOf<String, TextView>()
    private val lastPrices = mutableMapOf<String, Double>()
    private var liveCount = 0
    private var activeAlertTone: Ringtone? = null
    private var flashTask: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(101, buildNotification())
        createOverlay()
        handler.postDelayed(pingWatchdog, 30000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        showOverlay()
        val latest = PrefsManager.getSelectedSymbols(this).toList()
        val oldCodes = selected.map { it.code }
        val newCodes = latest.map { it.code }
        if (oldCodes != newCodes) {
            selected = latest
            rebuildRows()
            restartSocket()
        } else if (!socketOpen && !connecting && retryRunnable == null) {
            selected = latest
            rebuildRows()
            connect()
        } else if (priceViews.isEmpty() && latest.isNotEmpty()) {
            selected = latest
            rebuildRows()
        }
        return START_STICKY
    }

    private val pingWatchdog = object : Runnable {
        override fun run() {
            if (socketOpen) socket?.send(JSONObject().put("ping", 1).toString())
            handler.postDelayed(this, 30000)
        }
    }

    private fun createOverlay() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded("#E8111B2B", "#6638BDF8", 16)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = "ZICHAN  /  LIVE MARKET"
            setTextColor(Color.parseColor("#A5DFFF"))
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val badge = TextView(this).apply {
            text = "● CONNECTING"
            setTextColor(Color.parseColor("#FBBF24"))
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
        }
        top.addView(title)
        top.addView(badge)
        val priceRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val collapsed = TextView(this).apply {
            text = "↗  LIVE"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            visibility = View.GONE
        }
        container.addView(top)
        container.addView(priceRows)
        container.addView(collapsed)
        root = container
        header = badge
        rows = priceRows
        mini = collapsed
        applyStyle()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(18)
            y = dp(180)
        }
        attachTouch(container, params)
        windowManager.addView(container, params)
    }

    private fun applyStyle() {
        val opacity = PrefsManager.getOpacity(this)
        val alpha = (opacity * 255 / 100).coerceIn(64, 255)
        root?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.argb(alpha, 17, 27, 43))
            setStroke(dp(1), Color.argb(190, 56, 189, 248))
        }
        val size = PrefsManager.getTextSizeSp(this)
        priceViews.values.forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_SP, size) }
    }

    private fun rebuildRows() {
        rows?.removeAllViews()
        priceViews.clear()
        lastPrices.clear()
        liveCount = 0
        selected.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val name = TextView(this).apply {
                text = shortName(item.displayName)
                setTextColor(Color.parseColor("#DCE7F5"))
                textSize = (PrefsManager.getTextSizeSp(this@FloatingMonitorService) - 1f).coerceAtLeast(9f)
                typeface = Typeface.DEFAULT_BOLD
                maxWidth = dp(150)
            }
            val price = TextView(this).apply {
                text = "Waiting…"
                setTextColor(Color.parseColor("#7DD3FC"))
                textSize = PrefsManager.getTextSizeSp(this@FloatingMonitorService)
                typeface = Typeface.MONOSPACE
                setPadding(dp(8), 0, 0, 0)
            }
            row.addView(name)
            row.addView(price)
            rows?.addView(row)
            priceViews[item.code] = price
        }
        if (selected.isEmpty()) setStatus("ADD MARKETS", "#FBBF24")
    }

    private fun shortName(value: String): String = value
        .replace(" Index", "")
        .replace(" (XAU/USD)", "")
        .replace(" (BTC)", "")
        .replace(" (ETH)", "")

    private fun attachTouch(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        var longPressed = false
        var lastTap = 0L
        val longPress = Runnable {
            longPressed = true
            vibrate()
            view.visibility = View.GONE
            Toast.makeText(this, "Monitor hidden. Open the app to show it again.", Toast.LENGTH_SHORT).show()
        }
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    longPressed = false
                    handler.postDelayed(longPress, 850)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > dp(7) || abs(dy) > dp(7)) {
                        moved = true
                        handler.removeCallbacks(longPress)
                    }
                    if (!longPressed) {
                        params.x = startX + dx
                        params.y = startY + dy
                        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    if (!moved && !longPressed) {
                        val now = System.currentTimeMillis()
                        if (now - lastTap < 320) setMinimized(!minimized)
                        else if (minimized) setMinimized(false)
                        lastTap = now
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setMinimized(value: Boolean) {
        minimized = value
        header?.visibility = if (value) View.GONE else View.VISIBLE
        rows?.visibility = if (value) View.GONE else View.VISIBLE
        mini?.visibility = if (value) View.VISIBLE else View.GONE
    }

    private fun restartSocket() {
        generation += 1
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
        connecting = false
        socketOpen = false
        val old = socket
        socket = null
        old?.cancel()
        if (selected.isNotEmpty()) connect() else setStatus("ADD MARKETS", "#FBBF24")
    }

    private fun connect() {
        if (selected.isEmpty() || connecting || socketOpen) return
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
        connecting = true
        socketOpen = false
        resolved.clear()
        val myGeneration = generation
        setStatus("CONNECTING", "#FBBF24")
        val request = Request.Builder().url(endpoint).build()
        val newSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (myGeneration != generation) { ws.cancel(); return }
                socket = ws
                connecting = false
                socketOpen = true
                retryAttempt = 0
                ws.send(JSONObject().put("active_symbols", "brief").toString())
                setStatus("SYNCING MARKETS", "#7DD3FC")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (myGeneration != generation || ws !== socket) return
                try {
                    val json = JSONObject(text)
                    if (json.has("error")) {
                        val error = json.getJSONObject("error")
                        val message = error.optString("message", "Deriv API error")
                        handler.post { setStatus("API: " + message.take(26), "#FCA5A5") }
                        return
                    }
                    when (json.optString("msg_type")) {
                        "active_symbols" -> handleActiveSymbols(ws, json)
                        "tick" -> handleTick(json)
                    }
                } catch (_: Exception) {
                    handler.post { setStatus("BAD SERVER DATA", "#FCA5A5") }
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(code, reason)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (myGeneration != generation || ws !== socket) return
                socketOpen = false
                connecting = false
                socket = null
                scheduleReconnect("CONNECTION CLOSED")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (myGeneration != generation || ws !== socket) return
                socketOpen = false
                connecting = false
                socket = null
                val detail = response?.let { "HTTP " + it.code } ?: t.javaClass.simpleName
                handler.post { setStatus("ERROR: " + detail, "#FCA5A5") }
                scheduleReconnect(detail)
            }
        })
        if (myGeneration == generation && socket == null) socket = newSocket
        else if (myGeneration != generation) newSocket.cancel()
    }

    private fun handleActiveSymbols(ws: WebSocket, json: JSONObject) {
        val array = json.optJSONArray("active_symbols") ?: run {
            setStatus("NO SYMBOL DATA", "#FCA5A5")
            return
        }
        val available = mutableListOf<MarketSymbol>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val code = item.optString("underlying_symbol").ifBlank { item.optString("symbol") }
            val name = item.optString("underlying_symbol_name").ifBlank { item.optString("display_name") }
            val category = item.optString("market").ifBlank { "Other" }
            if (code.isNotBlank() && name.isNotBlank()) available.add(MarketSymbol(code, name, category))
        }

        val used = mutableSetOf<String>()
        selected.forEach { wanted ->
            val match = available.firstOrNull { it.code == wanted.code && it.code !in used }
                ?: available.firstOrNull { candidate ->
                    candidate.code !in used && namesMatch(wanted, candidate)
                }
            if (match != null) {
                used.add(match.code)
                resolved[match.code] = wanted.code
                val request = JSONObject()
                    .put("ticks", match.code)
                    .put("subscribe", 1)
                ws.send(request.toString())
            } else {
                handler.post {
                    priceViews[wanted.code]?.text = "Unavailable"
                    priceViews[wanted.code]?.setTextColor(Color.parseColor("#FCA5A5"))
                }
            }
        }
        if (resolved.isEmpty()) setStatus("NO WATCHLIST SYMBOLS", "#FCA5A5")
        else setStatus("SUBSCRIBING " + resolved.size, "#7DD3FC")
    }

    private fun namesMatch(wanted: MarketSymbol, candidate: MarketSymbol): Boolean {
        val target = normalize(wanted.displayName)
        val actual = normalize(candidate.displayName)
        if (target == actual || target.removeSuffix("index") == actual.removeSuffix("index")) return true
        val code = wanted.code.uppercase(Locale.US)
        val aliases = when {
            code.contains("XAU") || target.contains("gold") -> listOf("gold")
            code.contains("XAG") || target.contains("silver") -> listOf("silver")
            code.contains("BTC") || target.contains("bitcoin") -> listOf("bitcoin")
            code.contains("ETH") || target.contains("ethereum") -> listOf("ethereum")
            code.contains("CRASH50") || target.contains("crash50") -> listOf("crash50")
            else -> emptyList()
        }
        return aliases.any { actual.contains(it) }
    }

    private fun normalize(value: String) = value.lowercase(Locale.US).filter { it.isLetterOrDigit() }

    private fun handleTick(json: JSONObject) {
        val tick = json.optJSONObject("tick") ?: return
        val symbol = tick.optString("symbol")
        val rowKey = resolved[symbol] ?: return
        val quote = tick.optDouble("quote", Double.NaN)
        if (quote.isNaN()) return
        val pip = tick.optInt("pip_size", 2).coerceIn(0, 10)
        val value = String.format(Locale.US, "%." + pip + "f", quote)
        val previous = lastPrices[rowKey]
        lastPrices[rowKey] = quote
        if (previous != null) checkPriceAlert(rowKey, previous, quote)
        handler.post {
            val view = priceViews[rowKey] ?: return@post
            val difference = if (previous == null) 0.0 else quote - previous
            val arrow = when {
                difference > 0 -> "  ▲"
                difference < 0 -> "  ▼"
                else -> ""
            }
            view.text = value + arrow
            view.setTextColor(when {
                difference > 0 -> Color.parseColor("#4ADE80")
                difference < 0 -> Color.parseColor("#FB7185")
                else -> Color.parseColor("#E2E8F0")
            })
            if (previous == null) liveCount += 1
            setStatus("LIVE  •  " + liveCount + "/" + selected.size, "#4ADE80")
        }
    }

    private fun checkPriceAlert(rowKey: String, previous: Double, current: Double) {
        val alert = PrefsManager.getAlerts(this).firstOrNull {
            it.symbol == rowKey && !it.triggered &&
                ((previous < it.targetPrice && current >= it.targetPrice) ||
                 (previous > it.targetPrice && current <= it.targetPrice))
        } ?: return
        PrefsManager.markAlertTriggered(this, rowKey)
        triggerPriceAlert(alert, current)
    }

    private fun triggerPriceAlert(alert: PriceAlert, current: Double) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val open = PendingIntent.getActivity(
            this, alert.symbol.hashCode(), Intent(this, ChartActivity::class.java).apply {
                putExtra(ChartActivity.EXTRA_SYMBOL, alert.symbol)
                putExtra(ChartActivity.EXTRA_NAME, alert.displayName)
                putExtra(ChartActivity.EXTRA_CATEGORY, "Market")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Zichan Market Monitor")
            .setContentText("Sir, your set price level has been touched or crossed.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                alert.displayName + " touched or crossed " +
                String.format(Locale.US, "%.5f", alert.targetPrice) +
                ". Latest price: " + String.format(Locale.US, "%.5f", current)
            ))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setVibrate(longArrayOf(0, 220, 140, 220))
            .build()
        manager.notify(3100 + (alert.symbol.hashCode() and 0x3ff), notification)
        flashOverlay(alert.displayName)
        try {
            activeAlertTone?.stop()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            activeAlertTone = RingtoneManager.getRingtone(applicationContext, uri)
            activeAlertTone?.play()
            handler.postDelayed({ try { activeAlertTone?.stop() } catch (_: Exception) {} }, 1800)
        } catch (_: Exception) { }
    }

    private fun flashOverlay(marketName: String) {
        flashTask?.let { handler.removeCallbacks(it) }
        root?.visibility = View.VISIBLE
        if (minimized) setMinimized(false)
        val original = rounded("#E8111B2B", "#6638BDF8", 16)
        var elapsed = 0
        val task = object : Runnable {
            override fun run() {
                val container = root ?: return
                if (elapsed >= 1800) {
                    container.background = original
                    header?.text = "● LIVE"
                    header?.setTextColor(Color.parseColor("#4ADE80"))
                    flashTask = null
                    return
                }
                val red = elapsed % 600 < 300
                container.background = if (red) rounded("#D9B91C31", "#FFFF5268", 16) else original
                header?.text = if (red) "● PRICE ALERT" else "● " + marketName.take(16)
                header?.setTextColor(if (red) Color.WHITE else Color.parseColor("#4ADE80"))
                elapsed += 300
                handler.postDelayed(this, 300)
            }
        }
        flashTask = task
        handler.post(task)
    }

    private fun scheduleReconnect(reason: String) {
        if (selected.isEmpty()) return
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryAttempt += 1
        val delay = (2000L * (1L shl retryAttempt.coerceAtMost(4))).coerceAtMost(30000L)
        val myGeneration = generation
        setStatus("RETRY IN " + (delay / 1000) + "s", "#FBBF24")
        val task = Runnable {
            if (myGeneration != generation || socketOpen || connecting) return@Runnable
            retryRunnable = null
            connect()
        }
        retryRunnable = task
        handler.postDelayed(task, delay)
    }

    private fun setStatus(text: String, color: String) {
        handler.post {
            header?.text = "● " + text
            header?.setTextColor(Color.parseColor(color))
        }
    }

    private fun showOverlay() {
        root?.visibility = View.VISIBLE
        if (minimized) setMinimized(false)
        applyStyle()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Zichan Market Monitor")
            .setContentText("Floating market watchlist is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL, "Live Price Monitor", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
            val alertChannel = NotificationChannel(ALERT_CHANNEL, "Price Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Sound and visual notifications when a watched price reaches its alert level"
                enableVibration(true)
                // The app plays the alarm explicitly and stops it after 1.8 seconds.
                // Keep the notification channel silent to avoid a long duplicate ringtone.
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun rounded(bg: String, stroke: String, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        setColor(Color.parseColor(bg))
        setStroke(dp(1), Color.parseColor(stroke))
    }

    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(45)
        }
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onDestroy() {
        generation += 1
        retryRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
        socket?.cancel()
        socket = null
        try { activeAlertTone?.stop() } catch (_: Exception) {}
        activeAlertTone = null
        client.dispatcher.executorService.shutdown()
        root?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_REFRESH = "com.zichan.derivmonitor.ACTION_REFRESH"
        const val ACTION_STOP = "com.zichan.derivmonitor.ACTION_STOP"
        private const val CHANNEL = "deriv_live_overlay_channel"
        private const val ALERT_CHANNEL = "zichan_price_alerts"
    }
}
