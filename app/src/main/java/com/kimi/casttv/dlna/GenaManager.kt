package com.kimi.casttv.dlna

import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * GENA 事件订阅管理：处理 SUBSCRIBE / UNSUBSCRIBE，并向订阅者推送状态变化事件。
 */
object GenaManager {

    const val SVC_AV = "avtransport"
    const val SVC_RC = "renderingcontrol"
    const val SVC_CM = "connectionmanager"

    class Subscriber(
        val sid: String,
        val callbacks: List<String>,
        @Volatile var seq: Int = 0
    )

    private val subscribers = ConcurrentHashMap<String, CopyOnWriteArrayList<Subscriber>>()

    /** SUBSCRIBE：callbackHeader 形如 <http://x/y>；返回分配的 SID */
    fun subscribe(service: String, callbackHeader: String): String {
        val callbacks = Regex("<([^>]+)>").findAll(callbackHeader).map { it.groupValues[1] }.toList()
        val sid = "uuid:" + UUID.randomUUID().toString()
        val sub = Subscriber(sid, callbacks)
        subscribers.getOrPut(service) { CopyOnWriteArrayList() }.add(sub)
        // 初始事件（SEQ 必须为 0）
        thread(name = "gena-initial") {
            sendTo(sub, service, buildEvent(service), initial = true)
        }
        return sid
    }

    /** 续订（带 SID 的 SUBSCRIBE） */
    fun renew(service: String, sid: String): Boolean {
        return subscribers[service]?.any { it.sid == sid } == true
    }

    fun unsubscribe(service: String, sid: String) {
        subscribers[service]?.removeIf { it.sid == sid }
    }

    fun notifyAvTransport() = notify(SVC_AV, buildEvent(SVC_AV))

    fun notifyRenderingControl() = notify(SVC_RC, buildEvent(SVC_RC))

    fun notifyConnectionManager() = notify(SVC_CM, buildEvent(SVC_CM))

    private fun notify(service: String, body: String) {
        val list = subscribers[service] ?: return
        thread(name = "gena-notify") {
            for (sub in list) sendTo(sub, service, body, initial = false)
        }
    }

    private fun sendTo(sub: Subscriber, service: String, body: String, initial: Boolean) {
        val seq = if (initial) 0 else sub.seq
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        for (callback in sub.callbacks) {
            runCatching {
                val uri = URI(callback)
                val port = if (uri.port > 0) uri.port else 80
                // 用原始 Socket 发送：HttpURLConnection 不允许 NOTIFY 方法
                java.net.Socket(uri.host, port).use { sock ->
                    sock.soTimeout = 5000
                    val path = uri.rawPath?.ifEmpty { "/" } ?: "/"
                    val req = buildString {
                        append("NOTIFY $path HTTP/1.1\r\n")
                        append("HOST: ${uri.host}:$port\r\n")
                        append("CONTENT-TYPE: text/xml; charset=utf-8\r\n")
                        append("NT: upnp:event\r\n")
                        append("NTS: upnp:propchange\r\n")
                        append("SID: ${sub.sid}\r\n")
                        append("SEQ: $seq\r\n")
                        append("CONTENT-LENGTH: ${bodyBytes.size}\r\n")
                        append("CONNECTION: close\r\n")
                        append("\r\n")
                    }
                    val out = sock.getOutputStream()
                    out.write(req.toByteArray(Charsets.ISO_8859_1))
                    out.write(bodyBytes)
                    out.flush()
                    // 读取响应，避免对端 RST 导致发送失败
                    runCatching { sock.getInputStream().read() }
                }
            }
        }
        if (!initial) sub.seq = (sub.seq + 1).coerceAtMost(Int.MAX_VALUE - 1)
    }

    /** 按规范：事件通知需包含该服务全部 evented 状态变量 */
    private fun buildEvent(service: String): String {
        val cs = CastState
        return when (service) {
            SVC_AV -> {
                val hasMedia = cs.currentUri.isNotEmpty()
                val dur = cs.durationMs
                UpnpXml.eventPropertySet(
                    listOf(
                        "TransportState" to cs.transportState,
                        "TransportStatus" to "OK",
                        "PlaybackStorageMedium" to if (hasMedia) "NETWORK" else "NONE",
                        "RecordStorageMedium" to "NOT_IMPLEMENTED",
                        "CurrentPlayMode" to "NORMAL",
                        "TransportPlaySpeed" to "1",
                        "RecordMediumWriteStatus" to "NOT_IMPLEMENTED",
                        "CurrentRecordQualityMode" to "NOT_IMPLEMENTED",
                        "NumberOfTracks" to if (hasMedia) "1" else "0",
                        "CurrentTrack" to if (hasMedia) "1" else "0",
                        "CurrentTrackDuration" to UpnpXml.formatTime(dur),
                        "CurrentMediaDuration" to UpnpXml.formatTime(dur),
                        "CurrentTrackMetaData" to cs.currentMeta,
                        "CurrentTrackURI" to cs.currentUri,
                        "AVTransportURI" to cs.currentUri,
                        "AVTransportURIMetaData" to cs.currentMeta,
                        "NextAVTransportURI" to cs.nextUri,
                        "NextAVTransportURIMetaData" to "",
                        "RelativeTimePosition" to UpnpXml.formatTime(cs.positionMs),
                        "AbsoluteTimePosition" to UpnpXml.formatTime(cs.positionMs),
                        "RelativeCounterPosition" to "2147483647",
                        "AbsoluteCounterPosition" to "2147483647",
                        "CurrentTransportActions" to "Play,Stop,Pause,Seek,GetPositionInfo,GetMediaInfo,GetTransportInfo"
                    )
                )
            }
            SVC_RC -> {
                val db = (cs.volume - 80).coerceIn(-80, 0)
                UpnpXml.eventPropertySet(
                    listOf(
                        "Mute" to if (cs.muted) "1" else "0",
                        "Volume" to cs.volume.toString(),
                        "VolumeDB" to db.toString()
                    )
                )
            }
            else -> {
                UpnpXml.eventPropertySet(
                    listOf(
                        "SourceProtocolInfo" to "",
                        "SinkProtocolInfo" to "",
                        "CurrentConnectionIDs" to if (cs.currentUri.isEmpty()) "" else "0"
                    )
                )
            }
        }
    }

    fun clear() {
        subscribers.clear()
    }
}
