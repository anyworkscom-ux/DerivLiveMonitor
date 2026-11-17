package com.zichan.derivmonitor

import android.content.Intent
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
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var catalogue = DefaultSymbols.list.toMutableList()
    private var catalogueSocket: WebSocket? = null
    private var catalogueLoaded = false
    private lateinit var selectedLayout: LinearLayout
    private lateinit var resultsLayout: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var permissionCard: TextView
    private lateinit var countLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildDashboard()
        loadCatalogue()
    }

    override fun onResume() {
        super.onResume()
        refreshPermission()
        if (Settings.canDrawOverlays(this)) startOverlay()
    }

    private fun buildDashboard() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(8, 13, 27)); isFillViewport = true }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(24), dp(18), dp(30)) }

        root.addView(label("DERIV MARKET TOOLS", 11f, "#38BDF8", true))
        root.addView(label("Live Price Monitor", 27f, "#F8FAFC", true).apply { setPadding(0, dp(5), 0, 0) })
        root.addView(label("Real-time watchlist  •  Floating prices  •  Clear market data", 12f, "#94A3B8", false).apply { setPadding(0, dp(5), 0, dp(18)) })

        val hero = card("#111E33", "#1D4ED8").apply {
            orientation = LinearLayout.VERTICAL
            addView(label("YOUR MARKETS, AT A GLANCE", 11f, "#7DD3FC", true))
            addView(label("Track the prices that matter.", 19f, "#FFFFFF", true).apply { setPadding(0, dp(7), 0, dp(5)) })
            addView(label("Choose instruments below. Only markets returned by Deriv's live catalogue can be monitored.", 12f, "#AFC0D6", false))
        }
        root.addView(hero)
        root.addView(space(12))

        permissionCard = label("", 13f, "#BBF7D0", true).apply {
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
                } else {
                    startOverlay()
                    Toast.makeText(this@MainActivity, "Floating monitor refreshed", Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(permissionCard)
        root.addView(space(12))

        val settings = card("#121E31", "#26364E").apply { orientation = LinearLayout.VERTICAL }
        val opacityLabel = label("Overlay opacity  •  " + PrefsManager.getOpacity(this) + "%", 12f, "#DCE7F5", true)
        val opacity = SeekBar(this).apply {
            max = 100
            progress = PrefsManager.getOpacity(this@MainActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    val v = value.coerceAtLeast(25)
                    opacityLabel.text = "Overlay opacity  •  " + v + "%"
                    PrefsManager.setOpacity(this@MainActivity, v)
                    if (fromUser && Settings.canDrawOverlays(this@MainActivity)) startOverlay()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        val sizeLabel = label("Price text size  •  " + PrefsManager.getTextSizeSp(this).toInt() + "sp", 12f, "#DCE7F5", true)
        val size = SeekBar(this).apply {
            max = 10
            progress = (PrefsManager.getTextSizeSp(this@MainActivity).toInt() - 10).coerceIn(0, 10)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    val v = value + 10
                    sizeLabel.text = "Price text size  •  " + v + "sp"
                    PrefsManager.setTextSizeSp(this@MainActivity, v.toFloat())
                    if (fromUser && Settings.canDrawOverlays(this@MainActivity)) startOverlay()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        settings.addView(opacityLabel); settings.addView(opacity); settings.addView(space(8)); settings.addView(sizeLabel); settings.addView(size)
        root.addView(settings)

        root.addView(section("YOUR WATCHLIST"))
        countLabel = label("", 11f, "#94A3B8", false).apply { setPadding(0, 0, 0, dp(8)) }
        root.addView(countLabel)
        selectedLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(selectedLayout)

        root.addView(section("EXPLORE MARKETS"))
        root.addView(label("Search live Deriv instruments by name, symbol or market.", 12f, "#94A3B8", false).apply { setPadding(0, 0, 0, dp(10)) })
        searchInput = EditText(this).apply {
            hint = "Search Gold, Crash 50, S&P 500, EUR/USD, BTC..."
            setHintTextColor(Color.parseColor("#718096")); setTextColor(Color.WHITE); textSize = 14f; isSingleLine = true
            setPadding(dp(14), dp(12), dp(14), dp(12)); background = panel("#17243A", "#2563EB")
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderSearch(s.toString()) }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        root.addView(searchInput)
        resultsLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, 0) }
        root.addView(resultsLayout)
        scroll.addView(root)
        setContentView(scroll)
        renderSelected()
        renderSearch("")
        refreshPermission()
    }

    private fun refreshPermission() {
        if (!::permissionCard.isInitialized) return
        if (Settings.canDrawOverlays(this)) {
            permissionCard.text = "●  FLOATING WINDOW ENABLED   •   Tap to refresh"
            permissionCard.setTextColor(Color.parseColor("#BBF7D0"))
            permissionCard.background = panel("#123A32", "#168A67")
        } else {
            permissionCard.text = "ENABLE DISPLAY OVER OTHER APPS  →"
            permissionCard.setTextColor(Color.parseColor("#FDE68A"))
            permissionCard.background = panel("#473516", "#B7791F")
        }
    }

    private fun renderSelected() {
        selectedLayout.removeAllViews()
        val chosen = PrefsManager.getSelectedSymbols(this)
        countLabel.text = chosen.size.toString() + " selected  •  Prices appear in your floating monitor"
        if (chosen.isEmpty()) {
            selectedLayout.addView(label("Your watchlist is empty. Add a market below.", 13f, "#94A3B8", false))
            return
        }
        chosen.forEach { symbol ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(13), dp(10), dp(13)); background = panel("#142238", "#263A55")
                layoutParams = margin(0, 7)
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
            info.addView(label(symbol.displayName, 14f, "#F8FAFC", true))
            info.addView(label(symbol.category + "  •  " + symbol.code, 11f, "#8FA4BE", false))
            val remove = action("REMOVE", "#FCA5A5", "#3B1F2A") {
                val next = PrefsManager.getSelectedSymbols(this@MainActivity).filterNot { it.code == symbol.code }
                PrefsManager.saveSelectedSymbols(this@MainActivity, next)
                renderSelected(); renderSearch(searchInput.text.toString())
                if (Settings.canDrawOverlays(this@MainActivity)) startOverlay()
            }
            row.addView(info); row.addView(remove); selectedLayout.addView(row)
        }
    }

    private fun renderSearch(query: String) {
        if (!::resultsLayout.isInitialized) return
        resultsLayout.removeAllViews()
        val selected = PrefsManager.getSelectedSymbols(this).map { it.code }.toSet()
        val q = query.trim().lowercase()
        val matches = catalogue.filter { it.code !in selected }.filter {
            q.isEmpty() || it.displayName.lowercase().contains(q) || it.code.lowercase().contains(q) || it.category.lowercase().contains(q)
        }.sortedWith(compareBy<MarketSymbol> {
            val name = it.displayName.lowercase()
            val pos = DefaultSymbols.popularDisplayNames.indexOfFirst { p -> name.contains(p.lowercase()) }
            if (pos < 0) 999 else pos
        }.thenBy { it.displayName }).take(35)

        if (!catalogueLoaded && matches.isEmpty()) {
            resultsLayout.addView(label("Loading Deriv's live market catalogue…", 13f, "#94A3B8", false)); return
        }
        if (matches.isEmpty()) {
            resultsLayout.addView(label("No matching active market found.", 13f, "#94A3B8", false)); return
        }
        matches.forEachIndexed { index, symbol ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(12), dp(11), dp(12)); background = panel("#101B2D", "#202F45")
                layoutParams = margin(0, 7); alpha = 0f; translationY = dp(5).toFloat()
                animate().alpha(1f).translationY(0f).setStartDelay((index.coerceAtMost(8) * 18).toLong())
                    .setDuration(180).setInterpolator(DecelerateInterpolator()).start()
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
            info.addView(label(symbol.displayName, 14f, "#F1F5F9", true))
            info.addView(label(symbol.category + "  •  " + symbol.code, 11f, "#8296B1", false))
            val add = action("+ ADD", "#7DD3FC", "#103B5C") {
                val current = PrefsManager.getSelectedSymbols(this@MainActivity)
                if (current.none { it.code == symbol.code }) current.add(symbol)
                PrefsManager.saveSelectedSymbols(this@MainActivity, current)
                renderSelected(); renderSearch(searchInput.text.toString())
                if (Settings.canDrawOverlays(this@MainActivity)) startOverlay()
            }
            row.addView(info); row.addView(add); resultsLayout.addView(row)
        }
    }

    private fun loadCatalogue() {
        catalogueSocket?.cancel()
        val req = Request.Builder().url("wss://ws.derivws.com/websockets/v3?app_id=1089").build()
        catalogueSocket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("active_symbols", "brief").toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.has("error")) {
                        runOnUiThread {
                            catalogueLoaded = true; renderSearch(searchInput.text.toString())
                            Toast.makeText(this@MainActivity, "Catalogue error: " + json.getJSONObject("error").optString("message"), Toast.LENGTH_LONG).show()
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
                        val category = item.optString("market").ifBlank { item.optString("market_display_name", "Other") }
                        if (code.isNotBlank() && name.isNotBlank()) live.add(MarketSymbol(code, name, category))
                    }
                    runOnUiThread {
                        if (live.isNotEmpty()) catalogue = live.distinctBy { it.code }.toMutableList()
                        catalogueLoaded = true
                        renderSearch(searchInput.text.toString())
                    }
                    webSocket.close(1000, "Catalogue loaded")
                } catch (_: Exception) {
                    runOnUiThread { catalogueLoaded = true; renderSearch(searchInput.text.toString()) }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread { catalogueLoaded = true; renderSearch(searchInput.text.toString()) }
            }
        })
    }

    private fun startOverlay() {
        val intent = Intent(this, FloatingMonitorService::class.java).apply { action = FloatingMonitorService.ACTION_REFRESH }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun label(text: String, size: Float, color: String, bold: Boolean) = TextView(this).apply {
        this.text = text; setTextColor(Color.parseColor(color)); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun section(text: String) = label(text, 15f, "#67D5FF", true).apply { setPadding(0, dp(22), 0, dp(8)) }
    private fun action(text: String, fg: String, bg: String, callback: () -> Unit) = TextView(this).apply {
        this.text = text; setTextColor(Color.parseColor(fg)); textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER; setPadding(dp(12), dp(9), dp(12), dp(9)); background = panel(bg, "#31516D")
        setOnClickListener { callback() }
    }
    private fun card(bg: String, stroke: String) = LinearLayout(this).apply {
        setPadding(dp(15), dp(14), dp(15), dp(12)); background = panel(bg, stroke)
    }
    private fun panel(bg: String, stroke: String) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = dp(14).toFloat()
        setColor(Color.parseColor(bg)); setStroke(dp(1), Color.parseColor(stroke))
    }
    private fun margin(top: Int, bottom: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    override fun onDestroy() {
        catalogueSocket?.close(1000, "Activity destroyed")
        http.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
