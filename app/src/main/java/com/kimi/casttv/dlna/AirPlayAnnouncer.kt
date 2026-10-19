package com.kimi.casttv.dlna

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * AirPlay Bonjour 服务宣告：注册 _airplay._tcp，iPhone 据此发现本设备。
 */
class AirPlayAnnouncer(
    private val context: Context,
    private val deviceId: String
) {
    private val nsd get() = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun start(port: Int) {
        stop()
        val info = NsdServiceInfo().apply {
            serviceName = CastState.friendlyName.ifEmpty { "YuHao投屏" }
            serviceType = "_airplay._tcp."
            setPort(port)
            setAttribute("deviceid", deviceId)
            setAttribute("features", "14839")
            setAttribute("model", "AppleTV3,2")
            setAttribute("protovers", "1.0")
            setAttribute("srcvers", "220.68")
            setAttribute("vv", "2")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                android.util.Log.i("AirPlay", "Bonjour registered: ${serviceInfo.serviceName} port=${serviceInfo.port}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                android.util.Log.e("AirPlay", "Bonjour registration failed: $errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        listener = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { android.util.Log.e("AirPlay", "registerService error", it) }
    }

    fun stop() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }
}
