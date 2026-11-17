package com.zichan.derivmonitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object PrefsManager {
    private const val PREF_NAME = "zichan_market_monitor"
    private const val KEY_SELECTED = "selected_symbols"
    private const val KEY_OPACITY = "overlay_opacity"
    private const val KEY_TEXT_SIZE = "overlay_text_size"
    private const val KEY_OVERLAY_SCALE = "overlay_scale"
    private const val KEY_OVERLAY_COLOR = "overlay_color"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_ACTIVATED = "lifetime_activated"
    private const val KEY_ALERTS = "price_alerts"
    private const val KEY_EMA_ENABLED = "ema_enabled"
    private const val KEY_EMA_PERIOD = "ema_period"
    private const val KEY_EMA_SHIFT = "ema_shift"

    fun getSelectedSymbols(context: Context): MutableList<MarketSymbol> {
        val raw = prefs(context).getString(KEY_SELECTED, null)
        val result = mutableListOf<MarketSymbol>()
        if (raw.isNullOrEmpty()) {
            result.addAll(DefaultSymbols.list.take(2))
            saveSelectedSymbols(context, result)
            return result
        }
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(MarketSymbol(obj.getString("code"), obj.getString("displayName"), obj.optString("category", "Market")))
            }
        } catch (_: Exception) { }
        return result
    }

    fun saveSelectedSymbols(context: Context, list: List<MarketSymbol>) {
        val arr = JSONArray()
        list.distinctBy { it.code }.forEach {
            arr.put(JSONObject().put("code", it.code).put("displayName", it.displayName).put("category", it.category))
        }
        prefs(context).edit().putString(KEY_SELECTED, arr.toString()).apply()
    }

    fun getOpacity(context: Context) = prefs(context).getInt(KEY_OPACITY, 84)
    fun setOpacity(context: Context, value: Int) = prefs(context).edit().putInt(KEY_OPACITY, value.coerceIn(25, 100)).apply()
    fun getTextSizeSp(context: Context) = prefs(context).getFloat(KEY_TEXT_SIZE, 13f)
    fun setTextSizeSp(context: Context, value: Float) = prefs(context).edit().putFloat(KEY_TEXT_SIZE, value.coerceIn(10f, 20f)).apply()

    fun getOverlayScale(context: Context) = prefs(context).getInt(KEY_OVERLAY_SCALE, 100)
    fun setOverlayScale(context: Context, value: Int) = prefs(context).edit().putInt(KEY_OVERLAY_SCALE, value.coerceIn(50, 160)).apply()
    fun getOverlayColor(context: Context) = prefs(context).getInt(KEY_OVERLAY_COLOR, 0)
    fun setOverlayColor(context: Context, value: Int) = prefs(context).edit().putInt(KEY_OVERLAY_COLOR, value.coerceIn(0, 3)).apply()

    fun getEmaEnabled(context: Context) = prefs(context).getBoolean(KEY_EMA_ENABLED, true)
    fun setEmaEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_EMA_ENABLED, value).apply()
    fun getEmaPeriod(context: Context) = prefs(context).getInt(KEY_EMA_PERIOD, 9).coerceIn(1, 200)
    fun setEmaPeriod(context: Context, value: Int) = prefs(context).edit().putInt(KEY_EMA_PERIOD, value.coerceIn(1, 200)).apply()
    fun getEmaShift(context: Context) = prefs(context).getInt(KEY_EMA_SHIFT, 0).coerceIn(-100, 100)
    fun setEmaShift(context: Context, value: Int) = prefs(context).edit().putInt(KEY_EMA_SHIFT, value.coerceIn(-100, 100)).apply()

    fun getTheme(context: Context) = prefs(context).getString(KEY_THEME, "dark") ?: "dark"
    fun setTheme(context: Context, value: String) = prefs(context).edit().putString(KEY_THEME, if (value == "light") "light" else "dark").apply()

    fun isActivated(context: Context) = prefs(context).getBoolean(KEY_ACTIVATED, false)
    fun setActivated(context: Context) = prefs(context).edit().putBoolean(KEY_ACTIVATED, true).apply()

    fun getAlerts(context: Context): MutableList<PriceAlert> {
        val result = mutableListOf<PriceAlert>()
        val raw = prefs(context).getString(KEY_ALERTS, null) ?: return result
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                result.add(PriceAlert(item.getString("symbol"), item.getString("displayName"), item.getDouble("targetPrice"),
                    item.optLong("createdAt", System.currentTimeMillis()), item.optBoolean("triggered", false)))
            }
        } catch (_: Exception) { }
        return result
    }

    fun saveAlert(context: Context, alert: PriceAlert) {
        val alerts = getAlerts(context).filterNot { it.symbol == alert.symbol }.toMutableList()
        alerts.add(alert); saveAlerts(context, alerts)
    }

    fun removeAlert(context: Context, symbol: String) {
        saveAlerts(context, getAlerts(context).filterNot { it.symbol == symbol })
    }

    fun markAlertTriggered(context: Context, symbol: String) {
        saveAlerts(context, getAlerts(context).map { if (it.symbol == symbol) it.copy(triggered = true) else it })
    }

    private fun saveAlerts(context: Context, alerts: List<PriceAlert>) {
        val array = JSONArray()
        alerts.forEach {
            array.put(JSONObject().put("symbol", it.symbol).put("displayName", it.displayName)
                .put("targetPrice", it.targetPrice).put("createdAt", it.createdAt).put("triggered", it.triggered))
        }
        prefs(context).edit().putString(KEY_ALERTS, array.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
