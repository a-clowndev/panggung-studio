package io.panggung.obs.capture

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.File

data class NdiDiscoveredSource(val name: String, val host: String, val port: Int)

/**
 * NDI LAN mDNS Discovery (NsdManager) & BYOR libndi.so SAF Importer (T-D03).
 */
class NdiManager(private val context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    val discoveredSources = mutableListOf<NdiDiscoveredSource>()

    fun importByorNdiLibrary(uri: Uri): Boolean {
        return try {
            val dst = File(context.filesDir, "byor/libndi.so").apply { parentFile?.mkdirs() }
            context.contentResolver.openInputStream(uri)?.use { inp ->
                dst.outputStream().use { out -> inp.copyTo(out) }
            } ?: return false
            System.load(dst.absolutePath)
            Log.i("NdiManager", "Loaded BYOR libndi.so from ${dst.absolutePath}")
            true
        } catch (e: Throwable) {
            Log.e("NdiManager", "BYOR libndi.so load failed", e)
            false
        }
    }

    fun startDiscovery(onUpdated: (List<NdiDiscoveredSource>) -> Unit) {
        stopDiscovery()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                discoveredSources.removeAll { it.name == service.serviceName }
                discoveredSources += NdiDiscoveredSource(service.serviceName, "mdns-local", 5961)
                onUpdated(discoveredSources.toList())
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                discoveredSources.removeAll { it.name == service.serviceName }
                onUpdated(discoveredSources.toList())
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { stopDiscovery() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { stopDiscovery() }
        }
        discoveryListener = listener
        try {
            nsd?.discoverServices("_ndi._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w("NdiManager", "NsdManager discovery error", e)
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try { nsd?.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        discoveryListener = null
    }
}
