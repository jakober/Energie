package com.jakober.energie.viewer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.ComposeUIViewController
import com.jakober.energie.ui.LocalPlatformHooks
import com.jakober.energie.ui.PlatformHooks
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController

/** NSUserDefaults als Schluessel-Wert-Speicher. */
class UserDefaultsStore : KeyValueStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    override fun get(key: String): String? = defaults.stringForKey("energie.$key")
    override fun put(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey("energie.$key") else defaults.setObject(value, "energie.$key")
    }
}

/** Karten-App oeffnen; Adressen loest die Anzeige auf iOS nicht auf, es bleiben die Koordinaten. */
private object IosHooks : PlatformHooks {
    override val canOpenMap: Boolean get() = true
    override fun openMap(lat: Double, lon: Double) {
        val url = NSURL.URLWithString("https://maps.apple.com/?ll=$lat,$lon&q=Auto") ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }
}

private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
private val defaults = UserDefaultsStore()
private val store: ViewerStore by lazy { ViewerStore(HttpClient(Darwin), defaults, appScope) }

/**
 * Ein nicht abgefangener Fehler beendet auf iOS sofort die ganze App. Der Haken schreibt
 * ihn vorher weg, damit er beim naechsten Start auf dem Bildschirm steht.
 */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
private fun installCrashHook() {
    kotlin.native.setUnhandledExceptionHook { e ->
        runCatching {
            defaults.put(ViewerStore.KEY_CRASH, (e.stackTraceToString()).take(4000))
        }
    }
}

/** Einstieg fuer Swift: der ganze Bildschirm als UIViewController. */
fun MainViewController(): UIViewController {
    installCrashHook()
    return ComposeUIViewController(configure = {
        // Die Info.plist traegt den verlangten Eintrag; die strenge Pruefung bleibt trotzdem
        // aus, denn ein fehlender Hinweis zur Bildrate darf die App nicht beenden.
        enforceStrictPlistSanityCheck = false
    }) {
        CompositionLocalProvider(LocalPlatformHooks provides IosHooks) {
            ViewerApp(store)
        }
    }
}

/** Von Swift beim Wechsel in den Vorder-/Hintergrund gerufen: Abgleich anhalten und fortsetzen. */
fun onAppActive() { store.start() }
fun onAppBackground() { store.stop() }
