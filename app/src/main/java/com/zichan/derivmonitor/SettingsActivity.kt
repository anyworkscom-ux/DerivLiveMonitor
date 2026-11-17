package com.zichan.derivmonitor

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class SettingsActivity : AppCompatActivity() {
    private val isLight by lazy { PrefsManager.getTheme(this) == "light" }
    private val derivLink = "https://t.deriv.link?t=BV73UMYHRLFL"
    private val exnessLink = "https://one.toexness.com/a/f390hkcg2s?method=qrcode"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(bg()) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(22), dp(18), dp(30)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(action("‹", "#FFFFFF", "#18263A") { finish() })
        top.addView(label("SETTINGS", 18f, primary(), true).apply { setPadding(dp(12), 0, 0, 0) })
        root.addView(top)
        root.addView(space(18))

        root.addView(section("APP PREFERENCES"))
        val themeCard = card()
        themeCard.addView(label("Appearance", 15f, primary(), true))
        themeCard.addView(label("Choose the look that feels right for your workspace.", 12f, secondary(), false).apply { setPadding(0, dp(5), 0, dp(12)) })
        val themes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        themes.addView(action("●  DARK MODE", "#FFFFFF", "#17243A") { PrefsManager.setTheme(this@SettingsActivity, "dark"); recreate() }, LinearLayout.LayoutParams(0, -2, 1f))
        themes.addView(spaceWidth(8))
        themes.addView(action("○  LIGHT MODE", "#102033", "#DCEAF5") { PrefsManager.setTheme(this@SettingsActivity, "light"); recreate() }, LinearLayout.LayoutParams(0, -2, 1f))
        themeCard.addView(themes)
        root.addView(themeCard)
        root.addView(space(12))

        val activation = card()
        activation.addView(label("DEVICE ACTIVATION", 10f, accent(), true))
        activation.addView(label(if (PrefsManager.isActivated(this)) "Lifetime activation is active on this device." else "This device is not activated.", 14f, primary(), true).apply { setPadding(0, dp(8), 0, dp(5)) })
        activation.addView(label("Activation is retained until the app is uninstalled. For secure commercial licensing, online verification will be added in a future release.", 11f, secondary(), false))
        root.addView(activation)
        root.addView(space(20))

        root.addView(section("PARTNER PROGRAMMES"))
        root.addView(label("Open an account using your partner link. Scan the QR code or visit the link below.", 12f, secondary(), false).apply {
            setPadding(0, 0, 0, dp(12))
        })
        root.addView(partnerCard("DERIV", "Create or access your Deriv account", derivLink))
        root.addView(space(12))
        root.addView(partnerCard("EXNESS", "Explore Exness through my partner link", exnessLink))
        root.addView(space(9))
        root.addView(label("FREE ACCESS FOR PARTNER REFERRALS", 10f, accent(), true))
        root.addView(label("Users who open a Deriv or Exness account through my partner links can contact me to request free app activation. Partner registration and activation eligibility may be verified.", 11f, secondary(), false).apply { setPadding(0, dp(6), 0, dp(18)) })
        root.addView(space(2))
        root.addView(space(20))

        root.addView(section("DEVELOPER"))
        val developer = card()
        developer.addView(label("Zichan Ahmed", 17f, primary(), true))
        developer.addView(label("App Developer / Owner", 12f, secondary(), false).apply { setPadding(0, dp(3), 0, dp(12)) })
        developer.addView(action("CONTACT ON WHATSAPP  ·  01755948988", "#FFFFFF", "#126B50") { open("https://wa.me/8801755948988") })
        root.addView(developer)
        root.addView(space(12))
        root.addView(action("ABOUT ZICHAN MARKET MONITOR  →", "#8BDFFF", "#123B5B") {
            startActivity(Intent(this, AboutActivity::class.java))
        })
        root.addView(space(18))
        root.addView(label("This app is updated over time. Future releases are planned to include market signals, price analysis and additional tools. Trading involves risk; this app does not provide investment advice.", 10.5f, secondary(), false))
        root.addView(space(12))
        root.addView(label("ZICHAN MARKET MONITOR  ·  17 NOVEMBER 2026", 9f, secondary(), false).apply { gravity = Gravity.CENTER })
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun partnerCard(title: String, subtitle: String, url: String): LinearLayout {
        val box = card()
        box.addView(label(title, 15f, primary(), true))
        box.addView(label(subtitle, 11f, secondary(), false).apply { setPadding(0, dp(4), 0, dp(12)) })
        val qr = ImageView(this).apply {
            setImageBitmap(makeQr(url, 540))
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(14).toFloat() }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(210), dp(210)).apply { gravity = Gravity.CENTER }
        }
        box.addView(qr)
        box.addView(space(10))
        box.addView(label(url, 10f, accent(), false).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER })
        box.addView(space(10))
        box.addView(action("OPEN $title LINK", "#FFFFFF", "#164B73") { open(url) })
        return box
    }

    private fun makeQr(value: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) Color.rgb(5, 12, 24) else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun open(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { Toast.makeText(this, "Unable to open link", Toast.LENGTH_SHORT).show() }
    }
    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(15), dp(15), dp(15)); background = panel(if (isLight) "#FFFFFF" else "#121F33", border()) }
    private fun section(t: String) = label(t, 11f, accent(), true).apply { setPadding(0, dp(2), 0, dp(9)) }
    private fun label(t: String, s: Float, c: String, b: Boolean) = TextView(this).apply { text=t; setTextColor(Color.parseColor(c)); setTextSize(TypedValue.COMPLEX_UNIT_SP,s); if(b) typeface=Typeface.DEFAULT_BOLD }
    private fun action(t: String, fg: String, bg: String, click: () -> Unit) = TextView(this).apply { text=t; textSize=11f; typeface=Typeface.DEFAULT_BOLD; gravity=Gravity.CENTER; setTextColor(Color.parseColor(fg)); setPadding(dp(13),dp(12),dp(13),dp(12)); background=panel(bg,bg); setOnClickListener{click()} }
    private fun action(t: String, fg: String, bg: String, lp: LinearLayout.LayoutParams, click: () -> Unit): TextView = action(t,fg,bg,click).apply { layoutParams=lp }
    private fun panel(bg: String, stroke: String) = GradientDrawable().apply { cornerRadius=dp(16).toFloat(); setColor(Color.parseColor(bg)); setStroke(dp(1),Color.parseColor(stroke)) }
    private fun space(h: Int) = View(this).apply { layoutParams=LinearLayout.LayoutParams(1,dp(h)) }
    private fun spaceWidth(w: Int) = View(this).apply { layoutParams=LinearLayout.LayoutParams(dp(w),1) }
    private fun dp(v: Int)=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,v.toFloat(),resources.displayMetrics).toInt()
    private fun bg()=Color.parseColor(if(isLight)"#F4F7FB" else "#080D19")
    private fun primary()=if(isLight)"#101828" else "#F7FAFF"
    private fun secondary()=if(isLight)"#65748B" else "#91A0B6"
    private fun accent()=if(isLight)"#087BB8" else "#67D5FF"
    private fun border()=if(isLight)"#D9E2EE" else "#27364C"
}
