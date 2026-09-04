package com.jakober.energie.ui.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

/**
 * Zeigt Fords Anmeldeseite in einem eingebauten Browser und faengt die
 * Rueckkehr `fordapp://userauthorized?code=...` ab, bevor Android sie an die
 * echte FordPass-App weiterreichen wuerde. Die komplette Adresse geht als
 * Ergebnis zurueck an die Einstellungen.
 */
class FordLoginActivity : ComponentActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }

        // Frische Sitzung: Ford soll das Zweitkonto abfragen, nicht ein altes Cookie.
        CookieManager.getInstance().removeAllCookies(null)

        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = web.settings.userAgentString.replace("; wv", "")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                if (target.startsWith("fordapp://") || target.startsWith("lincolnapp://")) {
                    setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_URL, target))
                    finish()
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (url.startsWith("fordapp://")) {
                    setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_URL, url))
                    finish()
                }
            }
        }
        setContentView(web)
        web.loadUrl(url)
    }

    companion object {
        const val EXTRA_URL = "url"
        const val RESULT_URL = "result_url"
    }
}
