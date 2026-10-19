package com.kimi.casttv.dlna

import com.kimi.casttv.player.CastPlayer

/**
 * AirPlay 视频接收端（AirPlay 1 的 HTTP 控制子集）：
 * iPhone/iPad 通过 Bonjour 发现本服务后，把视频地址 POST 给 /play，
 * 进度查询 /scrub、播放暂停 /rate、停止 /stop。
 *
 * 不支持的子协议：屏幕镜像（RTSP+加密，另议）、照片流（FairPlay 加密）、RAOP 音频。
 */
class AirPlayServer(
    private val castPlayer: CastPlayer,
    private val deviceId: String
) {
    // AirPlay 约定端口 7000；被占用时退回随机端口（Bonjour 会宣告实际端口）
    val server = MiniHttpServer(7000) { request -> route(request) }
    val port: Int get() = server.listeningPort

    private fun route(request: MiniHttpServer.Request): MiniHttpServer.Response {
        val path = request.path.substringBefore('?')
        val query = request.path.substringAfter('?', "")
        android.util.Log.d("AirPlay", "request: ${request.method} ${request.path}")
        return when {
            path == "/server-info" || path == "/version" -> plist(serverInfo())
            path == "/play" && request.method.equals("POST", true) -> handlePlay(request.body)
            path == "/stop" -> { castPlayer.stop(); empty(200) }
            path == "/pause" -> { castPlayer.pause(); empty(200) }
            path == "/rate" -> handleRate(query)
            path == "/scrub" && request.method.equals("POST", true) -> handleSeek(query)
            path == "/scrub" -> handleGetScrub()
            path == "/playback-info" -> plist(playbackInfo())
            path == "/slideshow-features" || path == "/authorize-info" ->
                MiniHttpServer.Response.text(501, "Not Implemented", "Not Implemented")
            else -> MiniHttpServer.Response.text(404, "Not Found", "Not Found")
        }
    }

    // ---------------- /play ----------------

    private fun handlePlay(body: ByteArray): MiniHttpServer.Response {
        val parsed = Bplist.parse(body) as? Map<*, *> ?: return empty(400)
        val uri = parsed["Content-Location"] as? String ?: return empty(400)
        val startSec = (parsed["Start-Position-Seconds"] as? Number)?.toDouble() ?: 0.0
        android.util.Log.e("CastUrl", "AirPlay /play uri=$uri start=$startSec")
        castPlayer.play(uri, "")
        if (startSec > 0.5) castPlayer.seekTo((startSec * 1000).toLong())
        return empty(200)
    }

    // ---------------- 控制 ----------------

    private fun handleRate(query: String): MiniHttpServer.Response {
        val value = queryParam(query, "value")?.toDoubleOrNull() ?: return empty(400)
        if (value == 0.0) castPlayer.pause() else castPlayer.resume()
        return empty(200)
    }

    private fun handleSeek(query: String): MiniHttpServer.Response {
        val pos = queryParam(query, "position")?.toDoubleOrNull() ?: return empty(400)
        castPlayer.seekTo((pos * 1000).toLong())
        return empty(200)
    }

    private fun handleGetScrub(): MiniHttpServer.Response {
        val duration = CastState.durationMs.coerceAtLeast(0L) / 1000.0
        val position = CastState.positionMs.coerceAtLeast(0L) / 1000.0
        return MiniHttpServer.Response.text(200, "OK", "duration: %.3f\r\nposition: %.3f\r\n".format(duration, position))
    }

    // ---------------- plist 响应 ----------------

    private fun serverInfo(): String = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>deviceid</key><string>$deviceId</string>
<key>features</key><integer>14839</integer>
<key>model</key><string>AppleTV3,2</string>
<key>protovers</key><string>1.0</string>
<key>srcvers</key><string>220.68</string>
</dict></plist>"""

    private fun playbackInfo(): String {
        val cs = CastState
        val duration = cs.durationMs.coerceAtLeast(0L) / 1000.0
        val position = cs.positionMs.coerceAtLeast(0L) / 1000.0
        val rate = if (cs.transportState == "PLAYING") 1 else 0
        val ready = cs.transportState == "PLAYING" || cs.transportState == "PAUSED_PLAYBACK"
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>duration</key><real>$duration</real>
<key>loadedTimeRanges</key><array/>
<key>playbackBufferEmpty</key><true/>
<key>playbackBufferFull</key><false/>
<key>playbackLoadable</key><true/>
<key>position</key><real>$position</real>
<key>rate</key><integer>$rate</integer>
<key>readyToPlay</key><${if (ready) "true" else "false"}/>
<key>seekable</key><true/>
</dict></plist>"""
    }

    // ---------------- 工具 ----------------

    private fun queryParam(query: String, name: String): String? =
        query.split('&').firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")

    private fun plist(xml: String) =
        MiniHttpServer.Response(200, "OK", "text/x-apple-plist; charset=utf-8", xml.toByteArray(Charsets.UTF_8))

    private fun empty(status: Int) = MiniHttpServer.Response(status, if (status == 200) "OK" else "Error", "text/plain", ByteArray(0))

    fun stop() = server.close()
}
