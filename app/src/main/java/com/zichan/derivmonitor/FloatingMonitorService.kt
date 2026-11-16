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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class FloatingMonitorService : Service() {

    private lateinit var windowManager: WindowManager
    private var rootContainer: LinearLayout? = null
    private var rowsContainer: LinearLayout? = null
    private var minimizedBadge: TextView? = null
    private var isMinimized = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val priceViews = mutableMapOf<String, TextView>()
    private val lastPrices = mutableMapOf<String, Double>()
    private var activeSymbols = listOf<MarketSymbol>()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        createFloatingView()
        reloadPairsAndConnect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REFRESH -> {
                showOverlayIfHidden()
                updateVisualStyling()
                reloadPairsAndConnect()
            }
            ACTION_STOP -> {
                stopSelf()
            }
            else -> {
                showOverlayIfHidden()
                updateVisualStyling()
                reloadPairsAndConnect()
            }
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
            text = "● DERIV LIVE"
            setTextColor(Color.parseColor("#80CBC4"))
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

        setupTouchListeners(container, params, header)
        windowManager.addView(container, params)
    }

    private fun updateVisualStyling() {
        val opacityPercent = PrefsManager.getOpacity(this)
        val alphaInt = ((opacityPercent / 100.0) * 255).toInt().coerceIn(60, 255)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(Color.argb(alphaInt, 15, 23, 42)) // Deep slate transparent
            setStroke(dp(1), Color.argb(130, 56, 189, 248))
        }
        rootContainer?.background = bg

        val textSize = PrefsManager.getTextSizeSp(this)
        for ((_, tv) in priceViews) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
        }
    }

    private fun setupTouchListeners(
        view: View,
        params: WindowManager.LayoutParams,
        headerView: TextView
    ) {
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
                            headerView.visibility = if (isMinimized) View.GONE else View.VISIBLE
                        } else if (isMinimized) {
                            setMinimizedState(false)
                            headerView.visibility = View.VISIBLE
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
        rowsContainer?.visibility = if (minimize) View.GONE else View.VISIBLE
        minimizedBadge?.visibility = if (minimize) View.VISIBLE else View.GONE
    }

    private fun reloadPairsAndConnect() {
        activeSymbols = PrefsManager.getSelectedSymbols(this)
        rowsContainer?.removeAllViews()
        priceViews.clear()

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
        }

        connectDerivWebSocket()
    }

    private fun connectDerivWebSocket() {
        webSocket?.cancel()
        if (activeSymbols.isEmpty()) return

        val request = Request.Builder()
            .url("wss://ws.derivws.com/websockets/v3?app_id=1089")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                for (sym in activeSymbols) {
                    val subJson = JSONObject().apply {
                        put("ticks", sym.code)
                        put("subscribe", 1)
                    }
                    webSocket.send(subJson.toString())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val tick = json.optJSONObject("tick") ?: return
                    val symbol = tick.optString("symbol")
                    val quoteStr = tick.optString("quote")
                    val quote = quoteStr.toDoubleOrNull() ?: return

                    val prev = lastPrices[symbol]
                    lastPrices[symbol] = quote

                    mainHandler.post {
                        val tv = priceViews[symbol] ?: return@post
                        val diff = if (prev != null) quote - prev else 0.0
                        val arrow = when {
                            diff > 0 -> " ▲"
                            diff < 0 -> " ▼"
                            else -> ""
                        }
                        val isSpike = prev != null && abs(diff) > (prev * 0.0018) &&
                                (symbol.startsWith("CRASH") || symbol.startsWith("BOOM"))
                        val spikeTag = if (isSpike) " ⚡" else ""

                        tv.text = "$quoteStr$arrow$spikeTag"
                        when {
                            diff > 0 -> tv.setTextColor(Color.parseColor("#00E676"))
                            diff < 0 -> tv.setTextColor(Color.parseColor("#FF5252"))
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                mainHandler.postDelayed({
                    connectDerivWebSocket()
                }, 4000)
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
