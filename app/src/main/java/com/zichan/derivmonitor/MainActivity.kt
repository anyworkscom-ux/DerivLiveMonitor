package com.zichan.derivmonitor

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import okhttp3.*
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(25, TimeUnit.SECONDS).build()
    private var catalogue = DefaultSymbols.list.toMutableList()
    private var catalogueSocket: WebSocket? = null
    private var catalogueLoaded = false
    private var selectedCategory = "All"
    private lateinit var selectedLayout: LinearLayout
    private lateinit var resultsLayout: LinearLayout
    private lateinit var categoryLayout: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var statusLabel: TextView
    private var isLight = false

    private val endpoint = "wss://api.derivws.com/trading/v1/options/ws/public"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isLight = PrefsManager.getTheme(this) == "light"
        requestNotificationPermission()
        if (PrefsManager.isActivated(this)) showDashboard() else showActivationGate()
    }

    override fun onResume() {
        super.onResume()
        if (PrefsManager.isActivated(this)) {
            val savedLight = PrefsManager.getTheme(this) == "light"
            if (savedLight != isLight && ::selectedLayout.isInitialized) {
                isLight = savedLight
                showDashboard()
                return
            }
            if (Settings.canDrawOverlays(this)) startOverlay()
        }
    }

    private fun showActivationGate() {
        val root = pageRoot()
        root.gravity = Gravity.CENTER_VERTICAL
        root.setPadding(dp(26), dp(30), dp(26), dp(30))
        val logo = logoBadge(82)
        root.addView(logo, centeredParams())
        root.addView(label("ZICHAN MARKET MONITOR", 12f, accent(), true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(22), 0, dp(8))
        })
        root.addView(label("Your markets.\nYour edge.", 31f, primary(), true).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
        })
        root.addView(label("Activate your personal market workspace to continue.", 14f, secondary(), false).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(12), dp(12), dp(24))
        })
        val card = card().apply { orientation = LinearLayout.VERTICAL }
        card.addView(label("LIFETIME DEVICE ACTIVATION", 11f, accent(), true))
        card.addView(label("Enter your activation code", 14f, primary(), true).apply { setPadding(0, dp(12), 0, dp(8)) })
        val pass = EditText(this).apply {
            hint = "Activation code"
            inputType = 2
            setSingleLine(true)
            setTextColor(Color.parseColor(primary())); setHintTextColor(Color.parseColor(secondary()))
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = panel(if (isLight) "#FFFFFF" else "#0B1424", accent())
        }
        card.addView(pass)
        card.addView(space(12))
        card.addView(action("ACTIVATE APP", "#06101E", "#70D8FF", true) {
            if (pass.text.toString().trim() == "1998") {
                PrefsManager.setActivated(this)
                AlertDialog.Builder(this)
                    .setTitle("Congratulations!")
                    .setMessage("Your Zichan Market Monitor is now active. Welcome to your live market workspace.")
                    .setPositiveButton("CONTINUE") { _, _ -> showDashboard() }
                    .setCancelable(false)
                    .show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Activation required")
                    .setMessage("Please contact the app developer / owner to activate your copy.\n\nZichan Ahmed\nWhatsApp: 01755948988")
                    .setPositiveButton("Contact on WhatsApp") { _, _ -> openWhatsApp() }
                    .setNegativeButton("Cancel", null).show()
            }
        })
        root.addView(card)
        root.addView(label("To activate this app, contact the developer", 12f, secondary(), false).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(22), 0, dp(5))
        })
        root.addView(action("CONTACT ZICHAN AHMED  ·  WHATSAPP", "#FFFFFF", "#173B5C", false) { openWhatsApp() })
        root.addView(label("One-time activation • Remains active until uninstall", 10f, secondary(), false).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0)
        })
        setContentView(root)
    }

    private fun showDashboard() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bgColor()); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(30))
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(logoBadge(46))
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(11) }
            addView(label("ZICHAN", 11f, accent(), true))
            addView(label("MARKET MONITOR", 15f, primary(), true))
        }
        top.addView(brand)
        top.addView(iconAction("◐") { toggleTheme() })
        top.addView(spaceWidth(7))
        top.addView(iconAction("⚙") { openSettings() })
        root.addView(top)
        root.addView(space(22))
        root.addView(label("MARKET INTELLIGENCE  /  LIVE", 10f, accent(), true))
        root.addView(label("Market Monitor", 30f, primary(), true).apply { setPadding(0, dp(5), 0, dp(4)) })
        root.addView(label("Real-time prices. Clear charts. Smarter alerts.", 13f, secondary(), false))
        root.addView(space(18))

        val hero = card().apply {
            orientation = LinearLayout.VERTICAL
            background = panel(if (isLight) "#EDF6FF" else "#101F36", "#3269D8")
            addView(label("YOUR MARKETS, AT A GLANCE", 11f, accent(), true))
            addView(label("Focus on the price that matters.", 20f, primary(), true).apply { setPadding(0, dp(8), 0, dp(5)) })
            addView(label("Explore Deriv instruments, open a chart and create price alerts from one workspace.", 12f, secondary(), false))
            addView(space(12))
            statusLabel = label("●  CONNECTING TO DERIV MARKET DATA", 10f, "#FBBF24", true)
            addView(statusLabel)
        }
        root.addView(hero)
        root.addView(space(14))

        val overlay = action("●  FLOATING MONITOR  ·  TAP TO REFRESH", "#B6F5D7", "#123D35", false) {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                startOverlay()
                Toast.makeText(this, "Floating monitor refreshed", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(overlay)
        root.addView(space(22))

        root.addView(section("YOUR WATCHLIST"))
        root.addView(label("Selected markets appear in your floating monitor.", 12f, secondary(), false).apply {
            setPadding(0, 0, 0, dp(9))
        })
        selectedLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(selectedLayout)
        root.addView(space(15))

        root.addView(section("EXPLORE MARKETS"))
        root.addView(label("Browse by market type or search a symbol.", 12f, secondary(), false).apply {
            setPadding(0, 0, 0, dp(10))
        })
        searchInput = EditText(this).apply {
            hint = "Search Gold, Crash 50, S&P 500, EUR/USD, BTC..."
            setHintTextColor(Color.parseColor(secondary())); setTextColor(Color.parseColor(primary())); textSize = 14f
            isSingleLine = true; setPadding(dp(14), dp(13), dp(14), dp(13))
            background = panel(if (isLight) "#FFFFFF" else "#15243A", accent())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderMarkets(s.toString()) }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        root.addView(searchInput)
        categoryLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(7))
        }
        val categoryScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(categoryLayout)
        }
        root.addView(categoryScroll)
        resultsLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(resultsLayout)

        root.addView(space(22))
        root.addView(label("UPDATES AHEAD", 10f, accent(), true))
        root.addView(label("Signals, price analysis and more market tools are planned for future updates.", 12f, secondary(), false).apply {
            setPadding(0, dp(6), 0, 0)
        })
        root.addView(space(16))
        root.addView(label("ZICHAN MARKET MONITOR  •  VERSION 1.1", 9f, secondary(), false).apply { gravity = Gravity.CENTER })
        scroll.addView(root)
        setContentView(scroll)
        renderCategories()
        renderSelected()
        renderMarkets("")
        loadCatalogue()
        refreshOverlayStatus()
        if (Settings.canDrawOverlays(this)) startOverlay()
    }

    private fun renderCategories() {
        if (!::categoryLayout.isInitialized) return
        categoryLayout.removeAllViews()
        val names = listOf("All", "Derived", "Forex", "Commodities", "Crypto", "Stock Indices", "Stocks")
        names.forEach { name ->
            val chip = TextView(this).apply {
                text = name; textSize = 10.5f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (selectedCategory == name) Color.WHITE else Color.parseColor(secondary()))
                setPadding(dp(11), dp(9), dp(11), dp(9))
                background = panel(if (selectedCategory == name) "#1464A5" else if (isLight) "#EDF1F6" else "#111D30",
                    if (selectedCategory == name) "#4AB8F4" else borderColor())
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(7) }
                setOnClickListener { selectedCategory = name; renderCategories(); renderMarkets(searchInput.text.toString()) }
            }
            categoryLayout.addView(chip)
        }
    }

    private fun renderSelected() {
        if (!::selectedLayout.isInitialized) return
        selectedLayout.removeAllViews()
        val chosen = PrefsManager.getSelectedSymbols(this)
        if (chosen.isEmpty()) {
            selectedLayout.addView(label("Your watchlist is empty. Add a market below.", 12f, secondary(), false))
            return
        }
        chosen.forEach { symbol ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(13), dp(10), dp(13))
                background = panel(if (isLight) "#FFFFFF" else "#142238", borderColor())
                layoutParams = margin(0, 8)
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            info.addView(label(symbol.displayName, 14f, primary(), true))
            info.addView(label(symbol.category.uppercase(Locale.US) + "  ·  " + symbol.code, 10f, secondary(), false))
            row.addView(info)
            row.addView(action("VIEW", "#8BDFFF", "#123B5B", false) { openChart(symbol) })
            row.addView(spaceWidth(6))
            row.addView(action("−", "#FCA5A5", "#402331", false) {
                PrefsManager.saveSelectedSymbols(this, PrefsManager.getSelectedSymbols(this).filterNot { it.code == symbol.code })
                renderSelected(); renderMarkets(searchInput.text.toString()); startOverlay()
            })
            selectedLayout.addView(row)
        }
    }

    private fun renderMarkets(query: String) {
        if (!::resultsLayout.isInitialized) return
        resultsLayout.removeAllViews()
        val selected = PrefsManager.getSelectedSymbols(this).map { it.code }.toSet()
        val q = query.trim().lowercase(Locale.US)
        val matches = catalogue.filter { it.code !in selected }
            .filter { selectedCategory == "All" || it.category.equals(selectedCategory, true) }
            .filter { q.isEmpty() || it.displayName.lowercase(Locale.US).contains(q) ||
                it.code.lowercase(Locale.US).contains(q) || it.category.lowercase(Locale.US).contains(q) }
            .sortedWith(compareBy<MarketSymbol> {
                val n = it.displayName.lowercase(Locale.US)
                val index = DefaultSymbols.popularDisplayNames.indexOfFirst { p -> n.contains(p.lowercase(Locale.US)) }
                if (index < 0) 999 else index
            }.thenBy { it.displayName }).take(40)

        if (!catalogueLoaded && matches.isEmpty()) {
            resultsLayout.addView(label("Connecting to Deriv market catalogue…", 12f, secondary(), false)); return
        }
        if (matches.isEmpty()) {
            resultsLayout.addView(label("No matching active market found.", 12f, secondary(), false)); return
        }
        matches.forEachIndexed { index, symbol ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(9), dp(12))
                background = panel(if (isLight) "#FFFFFF" else "#101B2D", borderColor())
                layoutParams = margin(0, 7); alpha = 0f; translationY = dp(5).toFloat()
                animate().alpha(1f).translationY(0f).setStartDelay((index.coerceAtMost(8) * 15).toLong())
                    .setDuration(170).setInterpolator(DecelerateInterpolator()).start()
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
            info.addView(label(symbol.displayName, 13f, primary(), true))
            info.addView(label(symbol.category + "  ·  " + symbol.code, 10f, secondary(), false))
            row.addView(info)
            row.addView(action("VIEW", "#8BDFFF", "#123B5B", false) { openChart(symbol) })
            row.addView(spaceWidth(5))
            row.addView(action("+ ADD", "#A7F3D0", "#124236", false) {
                val current = PrefsManager.getSelectedSymbols(this)
                if (current.none { it.code == symbol.code }) current.add(symbol)
                PrefsManager.saveSelectedSymbols(this, current)
                renderSelected(); renderMarkets(searchInput.text.toString()); startOverlay()
            })
            resultsLayout.addView(row)
        }
    }

    private fun loadCatalogue() {
        catalogueSocket?.cancel()
        catalogueLoaded = false
        val req = Request.Builder().url(endpoint).build()
        catalogueSocket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("active_symbols", "brief").toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.has("error")) {
                        runOnUiThread {
                            catalogueLoaded = true
                            if (::statusLabel.isInitialized) statusLabel.text = "●  MARKET CATALOGUE TEMPORARILY UNAVAILABLE"
                            renderMarkets(searchInput.text.toString())
                        }
                        webSocket.close(1000, "Catalogue error"); return
                    }
                    if (json.optString("msg_type") != "active_symbols") return
                    val array = json.optJSONArray("active_symbols") ?: return
                    val live = mutableListOf<MarketSymbol>()
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val code = item.optString("underlying_symbol").ifBlank { item.optString("symbol") }
                        val name = item.optString("underlying_symbol_name").ifBlank { item.optString("display_name") }
                        val rawCategory = item.optString("market_display_name").ifBlank { item.optString("market", "Other") }
                        if (code.isNotBlank() && name.isNotBlank()) live.add(MarketSymbol(code, name, normalizeCategory(rawCategory, name)))
                    }
                    runOnUiThread {
                        if (live.isNotEmpty()) catalogue = live.distinctBy { it.code }.toMutableList()
                        catalogueLoaded = true
                        if (::statusLabel.isInitialized) statusLabel.text = "●  MARKET CATALOGUE READY  ·  " + catalogue.size + " INSTRUMENTS"
                        renderMarkets(searchInput.text.toString())
                    }
                    webSocket.close(1000, "Catalogue loaded")
                } catch (_: Exception) {
                    runOnUiThread { catalogueLoaded = true; renderMarkets(searchInput.text.toString()) }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    catalogueLoaded = true
                    if (::statusLabel.isInitialized) statusLabel.text = "●  USING AVAILABLE MARKET LIST"
                    renderMarkets(searchInput.text.toString())
                }
            }
        })
    }

    private fun normalizeCategory(raw: String, name: String): String {
        val value = raw.lowercase(Locale.US)
        return when {
            value.contains("synthetic") || value.contains("derived") || value.contains("crash") || value.contains("boom") || value.contains("volatility") -> "Derived"
            value.contains("forex") || value.contains("currency") -> "Forex"
            value.contains("commodit") || name.contains("Gold", true) || name.contains("Silver", true) -> "Commodities"
            value.contains("crypto") || name.contains("Bitcoin", true) || name.contains("Ethereum", true) -> "Crypto"
            value.contains("stock index") || value.contains("indices") || value.contains("index") -> "Stock Indices"
            value.contains("stock") || value.contains("share") -> "Stocks"
            else -> raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        }
    }

    private fun refreshOverlayStatus() {
        if (!::statusLabel.isInitialized) return
        statusLabel.text = if (Settings.canDrawOverlays(this)) "●  FLOATING MONITOR READY" else "●  ENABLE FLOATING WINDOW PERMISSION BELOW"
    }

    private fun openChart(symbol: MarketSymbol) {
        startActivity(Intent(this, ChartActivity::class.java).apply {
            putExtra(ChartActivity.EXTRA_SYMBOL, symbol.code)
            putExtra(ChartActivity.EXTRA_NAME, symbol.displayName)
            putExtra(ChartActivity.EXTRA_CATEGORY, symbol.category)
        })
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun toggleTheme() {
        isLight = !isLight
        PrefsManager.setTheme(this, if (isLight) "light" else "dark")
        showDashboard()
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val intent = Intent(this, FloatingMonitorService::class.java).apply { action = FloatingMonitorService.ACTION_REFRESH }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 500)
        }
    }

    private fun openWhatsApp() {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/8801755948988"))) }
        catch (_: Exception) { Toast.makeText(this, "WhatsApp is not available", Toast.LENGTH_SHORT).show() }
    }

    private fun logoBadge(size: Int): TextView = TextView(this).apply {
        text = "Z"; gravity = Gravity.CENTER; textSize = size * 0.58f
        typeface = Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
        setTextColor(Color.WHITE)
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(12, 207, 240), Color.rgb(20, 92, 255), Color.rgb(132, 47, 246))).apply {
            cornerRadius = dp(size / 3).toFloat()
            setStroke(dp(1), Color.parseColor("#625AFFFF"))
        }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun pageRoot() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(18), dp(22), dp(18), dp(28)); setBackgroundColor(bgColor())
    }
    private fun centeredParams() = LinearLayout.LayoutParams(dp(82), dp(82)).apply { gravity = Gravity.CENTER }
    private fun label(text: String, size: Float, color: String, bold: Boolean) = TextView(this).apply {
        this.text = text; setTextColor(Color.parseColor(color)); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun section(text: String) = label(text, 14f, accent(), true).apply { setPadding(0, dp(4), 0, dp(9)) }
    private fun action(text: String, fg: String, bg: String, filled: Boolean, callback: () -> Unit) = TextView(this).apply {
        this.text = text; setTextColor(Color.parseColor(fg)); textSize = 10.5f; typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER; setPadding(dp(12), dp(10), dp(12), dp(10))
        background = panel(bg, if (filled) bg else "#31516D")
        setOnClickListener { callback() }
    }
    private fun iconAction(text: String, callback: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.parseColor(primary()))
        background = panel(if (isLight) "#FFFFFF" else "#142238", borderColor())
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)); setOnClickListener { callback() }
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(15), dp(15), dp(14))
        background = panel(if (isLight) "#FFFFFF" else "#121F33", borderColor())
    }
    private fun panel(bg: String, stroke: String) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = dp(16).toFloat()
        setColor(Color.parseColor(bg)); setStroke(dp(1), Color.parseColor(stroke))
    }
    private fun margin(top: Int, bottom: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun spaceWidth(width: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(width), 1) }
    private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
    private fun bgColor() = Color.parseColor(if (isLight) "#F4F7FB" else "#080D19")
    private fun primary() = if (isLight) "#101828" else "#F7FAFF"
    private fun secondary() = if (isLight) "#65748B" else "#91A0B6"
    private fun accent() = if (isLight) "#087BB8" else "#67D5FF"
    private fun borderColor() = if (isLight) "#D9E2EE" else "#27364C"

    override fun onDestroy() {
        catalogueSocket?.cancel()
        http.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
