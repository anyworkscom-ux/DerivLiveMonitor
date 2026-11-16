package com.zichan.derivmonitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object PrefsManager {
    private const val PREF_NAME = "deriv_monitor_prefs"
    private const val KEY_SELECTED = "selected_symbols"
    private const val KEY_OPACITY = "overlay_opacity"
    private const val KEY_TEXT_SIZE = "overlay_text_size"

    fun getSelectedSymbols(context: Context): MutableList<MarketSymbol> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SELECTED, null)
        val result = mutableListOf<MarketSymbol>()
        if (raw.isNullOrEmpty()) {
            // Default starter pairs for Zichan: Crash 50 & Gold
            result.add(MarketSymbol("CRASH50", "Crash 50 Index", "Crash/Boom"))
            result.add(MarketSymbol("frxXAUUSD", "Gold / USD (XAU/USD)", "Commodities"))
            saveSelectedSymbols(context, result)
            return result
        }
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(
                    MarketSymbol(
                        code = obj.getString("code"),
                        displayName = obj.getString("displayName"),
                        category = obj.optString("category", "Deriv")
                    )
                )
            }
        } catch (_: Exception) {}
        return result
    }

    fun saveSelectedSymbols(context: Context, list: List<MarketSymbol>) {
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject()
            obj.put("code", item.code)
            obj.put("displayName", item.displayName)
            obj.put("category", item.category)
            arr.put(obj)
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED, arr.toString())
            .apply()
    }

    fun getOpacity(context: Context): Int {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_OPACITY, 82)
    }

    fun setOpacity(context: Context, value: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_OPACITY, value.coerceIn(25, 100))
            .apply()
    }

    fun getTextSizeSp(context: Context): Float {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_TEXT_SIZE, 13f)
    }

    fun setTextSizeSp(context: Context, value: Float) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_TEXT_SIZE, value.coerceIn(10f, 20f))
            .apply()
    }
}
