package com.jakober.energie.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.jakober.energie.core.plugs.PlugDevice
import com.jakober.energie.core.plugs.PlugKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Findet Shelly-Stecker im Heimnetz ueber mDNS (`_shelly._tcp`). Jeder Fund
 * wird aufgeloest, damit die IP-Adresse bekannt ist. Laeuft eine feste Zeit
 * und liefert dann, was bis dahin da war.
 */
class PlugDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    suspend fun discover(millis: Long): List<PlugDevice> {
        val found = ConcurrentHashMap<String, PlugDevice>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        val name = info.serviceName
                        found[name] = PlugDevice(id = name.lowercase(), name = name, host = host, kind = PlugKind.SHELLY)
                    }
                })
            }
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            delay(millis)
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        return found.values.sortedBy { it.name }
    }

    companion object {
        const val SERVICE_TYPE = "_shelly._tcp."
    }
}
