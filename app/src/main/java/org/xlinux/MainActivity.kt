package org.xlinux

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var rt: LinuxRuntime

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        rt = LinuxRuntime(this)
        if (!Integrity.ok(this)) {   // cornerstone modified: stop, show lock screen, run nothing
            setContentView(android.widget.TextView(this).apply {
                text = "X Linux\n\nIntegrity check failed.\nThe cornerstone file was modified,\nso the application has been stopped."
                setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#7f1d1d")); textSize = 18f; gravity = android.view.Gravity.CENTER
            })
            return
        }
        web = WebView(this)
        setContentView(web)
        val bg = Color.parseColor("#efe6fb")
        window.statusBarColor = bg; window.navigationBarColor = bg
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or 0x10
        web.setBackgroundColor(bg)
        web.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; allowFileAccess = true; allowContentAccess = false }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = true   // never leave the app UI
        }
        web.addJavascriptInterface(Bridge(this, rt, web), "XL")
        web.loadUrl("file:///android_asset/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (!::web.isInitialized) { finish(); return }; web.evaluateJavascript("xlBack()") { if (it != "true") finish() } }
    override fun onDestroy() { if (::rt.isInitialized) rt.stop(); super.onDestroy() }
}
