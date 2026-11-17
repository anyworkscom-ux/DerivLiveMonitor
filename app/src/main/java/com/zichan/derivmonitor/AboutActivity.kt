package com.zichan.derivmonitor

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {
    private val light by lazy { PrefsManager.getTheme(this) == "light" }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(22),dp(24),dp(22),dp(28))
            setBackgroundColor(Color.parseColor(if(light)"#F4F7FB" else "#080D19"))
        }
        val back = TextView(this).apply {
            text="‹   ABOUT"; textSize=18f; typeface=Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(if(light)"#101828" else "#F7FAFF"))
            setOnClickListener { finish() }
        }
        root.addView(back)
        root.addView(space(25))
        val logo = TextView(this).apply {
            text="Z"; gravity=Gravity.CENTER; textSize=58f; typeface=Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(0xFF0CCFF0.toInt(),0xFF145CFF.toInt(),0xFF842FF6.toInt())).apply{cornerRadius=dp(28).toFloat()}
            layoutParams=LinearLayout.LayoutParams(dp(94),dp(94)).apply{gravity=Gravity.CENTER}
        }
        root.addView(logo)
        root.addView(TextView(this).apply {
            text="ZICHAN MARKET MONITOR"; gravity=Gravity.CENTER; textSize=17f; typeface=Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(if(light)"#101828" else "#F7FAFF")); setPadding(0,dp(15),0,dp(5))
        })
        root.addView(TextView(this).apply {
            text="Your markets. Your edge."; gravity=Gravity.CENTER; textSize=13f
            setTextColor(Color.parseColor(if(light)"#65748B" else "#91A0B6"))
        })
        root.addView(space(25))
        val body = ScrollView(this)
        val text = TextView(this).apply {
            text = """WHAT THIS APP DOES

Zichan Market Monitor is a market-watching companion designed to help you follow selected Deriv instruments from one clean workspace.

LIVE MARKET WATCH
Monitor selected market prices through Deriv's public market-data connection. Keep a compact floating price panel visible while using other apps.

MARKET EXPLORER
Browse available instruments by market category, search by name or symbol, and build a personal watchlist.

CHART WORKSPACE
View historical candlestick or line charts, switch timeframes, and use simple chart drawing tools.

PRICE ALERTS
Set a target price on a chart. When the selected market reaches or crosses that level, the app can notify you with sound and a visual alert, subject to Android notification and background-service permissions.

DESIGNED FOR YOUR WORKFLOW
Choose a dark or light appearance, customize your floating monitor and keep your key markets close at hand.

WHAT'S COMING
Future updates are planned to introduce market signals, market analysis and more trading utilities. These features will be announced when available.

IMPORTANT
This app is a market-monitoring utility, not a broker or trading platform. Market data availability may depend on Deriv services, network conditions and instrument trading hours. Nothing in this app is financial or investment advice. Trading involves risk.

CREATED BY
Zichan Ahmed
App Developer / Owner
WhatsApp: 01755948988

Version 1.1
17 November 2026"""
            setTextColor(Color.parseColor(if(light)"#344054" else "#D7E1F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP,13f)
            setLineSpacing(dp(5).toFloat(),1f)
        }
        body.addView(text)
        root.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
    }
    private fun space(h:Int)=View(this).apply{layoutParams=LinearLayout.LayoutParams(1,dp(h))}
    private fun dp(v:Int)=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,v.toFloat(),resources.displayMetrics).toInt()
}
