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
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private var allAvailableSymbols = DefaultSymbols.list.toMutableList()
    private lateinit var selectedListLayout: LinearLayout
    private lateinit var searchResultsLayout: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var statusBanner: TextView

    private val httpClient = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        fetchAllDerivSymbolsInBackground()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionBanner()
        if (Settings.canDrawOverlays(this)) {
            startOrRefreshOverlayService()
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B1120"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }

        val title = TextView(this).apply {
            text = "Deriv Live Price Monitor"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = Typeface.DEFAULT_BOLD
        }

        val subtitle = TextView(this).apply {
            text = "অ্যাপ থেকে বের হয়ে গেলেও ফোনের স্ক্রিনে লাইভ প্রাইস ভাসবে। ফ্লোটিং বক্সে লং-প্রেস করলে হাইড হবে, আর অ্যাপে ঢুকলে আবার চালু হবে।"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(4), 0, dp(12))
        }

        statusBanner = TextView(this).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    startOrRefreshOverlayService()
                    Toast.makeText(this@MainActivity, "ফ্লোটিং মনিটর সামনে আনা হয়েছে!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Sliders card (Opacity & Text size)
        val controlsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox("#1E293B", "#334155")
        }

        val opacityLabel = TextView(this).apply {
            val current = PrefsManager.getOpacity(this@MainActivity)
            text = "ফ্লোটিং বক্সের স্বচ্ছতা (Opacity): $current%"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }

        val opacitySeek = SeekBar(this).apply {
            max = 100
            progress = PrefsManager.getOpacity(this@MainActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val p = progress.coerceAtLeast(25)
                    opacityLabel.text = "ফ্লোটিং বক্সের স্বচ্ছতা (Opacity): $p%"
                    PrefsManager.setOpacity(this@MainActivity, p)
                    if (Settings.canDrawOverlays(this@MainActivity)) startOrRefreshOverlayService()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        val sizeLabel = TextView(this).apply {
            val current = PrefsManager.getTextSizeSp(this@MainActivity).toInt()
            text = "লেখার সাইজ (Text Size): ${current}sp"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(6), 0, 0)
        }

        val sizeSeek = SeekBar(this).apply {
            max = 20
            progress = PrefsManager.getTextSizeSp(this@MainActivity).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val s = progress.coerceAtLeast(10)
                    sizeLabel.text = "লেখার সাইজ (Text Size): ${s}sp"
                    PrefsManager.setTextSizeSp(this@MainActivity, s.toFloat())
                    if (Settings.canDrawOverlays(this@MainActivity)) startOrRefreshOverlayService()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        controlsCard.addView(opacityLabel)
        controlsCard.addView(opacitySeek)
        controlsCard.addView(sizeLabel)
        controlsCard.addView(sizeSeek)

        // Active Pairs section
        val activeHeader = TextView(this).apply {
            text = "চলমান পেয়ারসমূহ (Active Pairs)"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(16), 0, dp(6))
        }

        selectedListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Search & Add Pairs section
        val searchHeader = TextView(this).apply {
            text = "নতুন পেয়ার সার্চ ও অ্যাড করুন (Search Deriv Pairs)"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(16), 0, dp(6))
        }

        searchInput = EditText(this).apply {
            hint = "সার্চ করুন: Crash 50, Boom 1000, Gold, BTC, EUR/USD..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox("#1E293B", "#38BDF8")
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    renderSearchResults(s?.toString() ?: "")
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        searchResultsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(statusBanner)

        val spacer = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(10)) }
        root.addView(spacer)
        root.addView(controlsCard)
        root.addView(activeHeader)
        root.addView(selectedListLayout)
        root.addView(searchHeader)
        root.addView(searchInput)
        root.addView(searchResultsLayout)

        scroll.addView(root)
        setContentView(scroll)

        renderSelectedSymbols()
        renderSearchResults("")
    }

    private fun updatePermissionBanner() {
        if (!Settings.canDrawOverlays(this)) {
            statusBanner.text = "⚠️ ফ্লোটিং উইন্ডো চালু করতে এখানে ট্যাপ করে 'Appear on top / Display over other apps' পারমিশন অন করুন"
            statusBanner.setTextColor(Color.parseColor("#FEF08A"))
            statusBanner.background = roundedBox("#713F12", "#EAB308")
        } else {
            statusBanner.text = "✅ ফ্লোটিং লাইভ প্রাইস মনিটর চালু আছে (হাইড হয়ে থাকলে এখানে ট্যাপ করুন)"
            statusBanner.setTextColor(Color.parseColor("#BBF7D0"))
            statusBanner.background = roundedBox("#14532D", "#22C55E")
        }
    }

    private fun renderSelectedSymbols() {
        selectedListLayout.removeAllViews()
        val selected = PrefsManager.getSelectedSymbols(this)

        if (selected.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "কোনো পেয়ার অ্যাড করা নেই। নিচ থেকে পেয়ার অ্যাড করুন।"
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, dp(6), 0, dp(6))
            }
            selectedListLayout.addView(emptyTv)
            return
        }

        for (sym in selected) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBox("#1E293B", "#334155")
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(6)
                layoutParams = lp
            }

            val nameTv = TextView(this).apply {
                text = "${sym.displayName}  (${sym.code})"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val removeBtn = TextView(this).apply {
                text = "✕ বাদ দিন"
                setTextColor(Color.parseColor("#F87171"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(10), dp(4), dp(6), dp(4))
                setOnClickListener {
                    val updated = PrefsManager.getSelectedSymbols(this@MainActivity)
                        .filterNot { it.code == sym.code }
                    PrefsManager.saveSelectedSymbols(this@MainActivity, updated)
                    renderSelectedSymbols()
                    renderSearchResults(searchInput.text?.toString() ?: "")
                    if (Settings.canDrawOverlays(this@MainActivity)) {
                        startOrRefreshOverlayService()
                    }
                }
            }

            row.addView(nameTv)
            row.addView(removeBtn)
            selectedListLayout.addView(row)
        }
    }

    private fun renderSearchResults(query: String) {
        searchResultsLayout.removeAllViews()
        val selectedCodes = PrefsManager.getSelectedSymbols(this).map { it.code }.toSet()
        val q = query.trim().lowercase()

        val matches = allAvailableSymbols.filter { sym ->
            !selectedCodes.contains(sym.code) &&
                    (q.isEmpty() || sym.displayName.lowercase().contains(q) ||
                            sym.code.lowercase().contains(q) ||
                            sym.category.lowercase().contains(q))
        }.take(25)

        for (sym in matches) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBox("#0F172A", "#1E293B")
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(6)
                layoutParams = lp
            }

            val infoLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameTv = TextView(this).apply {
                text = sym.displayName
                setTextColor(Color.parseColor("#F1F5F9"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
            }

            val subTv = TextView(this).apply {
                text = "${sym.category} • ${sym.code}"
                setTextColor(Color.parseColor("#64748B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            }

            infoLayout.addView(nameTv)
            infoLayout.addView(subTv)

            val addBtn = TextView(this).apply {
                text = "+ অ্যাড করুন"
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = roundedBox("#0C4A6E", "#0284C7")
                setOnClickListener {
                    val current = PrefsManager.getSelectedSymbols(this@MainActivity)
                    if (current.none { it.code == sym.code }) {
                        current.add(sym)
                        PrefsManager.saveSelectedSymbols(this@MainActivity, current)
                    }
                    renderSelectedSymbols()
                    renderSearchResults(searchInput.text?.toString() ?: "")
                    if (Settings.canDrawOverlays(this@MainActivity)) {
                        startOrRefreshOverlayService()
                    }
                }
            }

            row.addView(infoLayout)
            row.addView(addBtn)
            searchResultsLayout.addView(row)
        }
    }

    private fun fetchAllDerivSymbolsInBackground() {
        val req = Request.Builder()
            .url("wss://ws.derivws.com/websockets/v3?app_id=1089")
            .build()

        httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val payload = JSONObject().apply {
                    put("active_symbols", "brief")
                    put("product_type", "basic")
                }
                webSocket.send(payload.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val arr = json.optJSONArray("active_symbols") ?: return
                    val fetched = mutableListOf<MarketSymbol>()
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val code = item.optString("symbol")
                        val display = item.optString("display_name")
                        val market = item.optString("submarket_display_name", item.optString("market_display_name", "Deriv"))
                        if (code.isNotEmpty() && display.isNotEmpty()) {
                            fetched.add(MarketSymbol(code, display, market))
                        }
                    }
                    if (fetched.isNotEmpty()) {
                        val existingCodes = DefaultSymbols.list.map { it.code }.toSet()
                        val merged = DefaultSymbols.list.toMutableList()
                        for (s in fetched) {
                            if (!existingCodes.contains(s.code)) {
                                merged.add(s)
                            }
                        }
                        runOnUiThread {
                            allAvailableSymbols = merged
                            renderSearchResults(searchInput.text?.toString() ?: "")
                        }
                    }
                    webSocket.close(1000, "Done")
                } catch (_: Exception) {}
            }
        })
    }

    private fun startOrRefreshOverlayService() {
        val intent = Intent(this, FloatingMonitorService::class.java).apply {
            action = FloatingMonitorService.ACTION_REFRESH
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun roundedBox(bgHex: String, strokeHex: String): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor(bgHex))
            setStroke(dp(1), Color.parseColor(strokeHex))
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
