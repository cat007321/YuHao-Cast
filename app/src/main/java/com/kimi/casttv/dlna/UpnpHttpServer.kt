package com.kimi.casttv.dlna

import com.kimi.casttv.player.CastPlayer

/**
 * UPnP HTTP 端点：设备描述 / SCPD / SOAP 控制 / GENA 事件，全都在这里路由。
 *
 * @param iconBytes 设备图标（assets/icon.png），可为空
 */
class UpnpHttpServer(
    private val castPlayer: CastPlayer,
    private val iconBytes: ByteArray?
) {
    // 固定优先端口 47121：手机端可能缓存投屏设备地址，端口/设备身份变化可绕过
    // 控制端按"IP:端口"记录的负缓存（曾判定不可投屏后不再重新评估）
    val server = MiniHttpServer(47121) { request -> route(request) }

    val port: Int get() = server.listeningPort

    fun start() = Unit // server 构造时已启动 accept 线程

    fun stop() = server.close()

    // ---------------- 路由 ----------------

    private fun route(request: MiniHttpServer.Request): MiniHttpServer.Response {
        val path = request.path.substringBefore('?')
        return when {
            path == "/" -> htmlStatus()
            path == "/device.xml" -> MiniHttpServer.Response.xml(UpnpXml.deviceDescription(CastState))
            path == "/icon.png" && iconBytes != null ->
                MiniHttpServer.Response(200, "OK", "image/png", iconBytes)
            path == "/scpd/avtransport.xml" -> MiniHttpServer.Response.xml(UpnpXml.scpdAvTransport)
            path == "/scpd/renderingcontrol.xml" -> MiniHttpServer.Response.xml(UpnpXml.scpdRenderingControl)
            path == "/scpd/connectionmanager.xml" -> MiniHttpServer.Response.xml(UpnpXml.scpdConnectionManager)
            path == "/control/avtransport" -> dispatchControl(request, GenaManager.SVC_AV)
            path == "/control/renderingcontrol" -> dispatchControl(request, GenaManager.SVC_RC)
            path == "/control/connectionmanager" -> dispatchControl(request, GenaManager.SVC_CM)
            path.startsWith("/events/") -> dispatchEvent(request, path.removePrefix("/events/"))
            else -> MiniHttpServer.Response.text(404, "Not Found", "Not Found")
        }
    }

    private fun htmlStatus(): MiniHttpServer.Response {
        val cs = CastState
        val html = """<!DOCTYPE html><html><head><meta charset="utf-8"><title>${UpnpXml.esc(cs.friendlyName)}</title></head>
<body style="font-family:sans-serif;background:#0E1526;color:#fff;padding:40px">
<h1>${UpnpXml.esc(cs.friendlyName)}</h1>
<p>状态：${cs.transportState}</p>
<p>当前地址：${UpnpXml.esc(cs.deviceUrl)}</p>
<p>正在播放：${UpnpXml.esc(cs.title)}</p>
</body></html>"""
        return MiniHttpServer.Response.text(200, "OK", html, "text/html; charset=utf-8")
    }

    // ---------------- SOAP 控制 ----------------

    private fun dispatchControl(request: MiniHttpServer.Request, service: String): MiniHttpServer.Response {
        val actionHeader = request.header("soapaction") ?: return soapFault(401, "Invalid Action")
        val raw = actionHeader.trim().removeSurrounding("\"").removeSurrounding("'")
        val action = raw.substringAfterLast('#')
        if (action.isEmpty()) return soapFault(401, "Invalid Action")
        val body = String(request.body, Charsets.UTF_8)

        val (code, xml) = when (service) {
            GenaManager.SVC_AV -> AvActions.dispatch(action, body, castPlayer)
            GenaManager.SVC_RC -> RcActions.dispatch(action, body, castPlayer)
            else -> CmActions.dispatch(action, body)
        }
        return if (code == 200) {
            MiniHttpServer.Response.xml(xml)
        } else {
            MiniHttpServer.Response(500, "Internal Server Error", "text/xml; charset=utf-8", xml.toByteArray(Charsets.UTF_8))
        }
    }

    // ---------------- GENA ----------------

    private fun dispatchEvent(request: MiniHttpServer.Request, service: String): MiniHttpServer.Response {
        val svc = when (service) {
            "avtransport" -> GenaManager.SVC_AV
            "renderingcontrol" -> GenaManager.SVC_RC
            "connectionmanager" -> GenaManager.SVC_CM
            else -> return MiniHttpServer.Response.text(404, "Not Found", "Not Found")
        }
        return when (request.method.uppercase()) {
            "SUBSCRIBE" -> {
                val sid = request.header("sid")
                if (sid != null) {
                    // 续订
                    if (GenaManager.renew(svc, sid)) {
                        MiniHttpServer.Response(200, "OK", "text/plain", ByteArray(0),
                            listOf("SID" to sid, "TIMEOUT" to "Second-300"))
                    } else {
                        MiniHttpServer.Response.text(412, "Precondition Failed", "Invalid SID")
                    }
                } else {
                    val callback = request.header("callback")
                        ?: return MiniHttpServer.Response.text(412, "Precondition Failed", "Missing CALLBACK")
                    val newSid = GenaManager.subscribe(svc, callback)
                    MiniHttpServer.Response(200, "OK", "text/plain", ByteArray(0),
                        listOf("SID" to newSid, "TIMEOUT" to "Second-300"))
                }
            }
            "UNSUBSCRIBE" -> {
                val sid = request.header("sid")
                    ?: return MiniHttpServer.Response.text(412, "Precondition Failed", "Missing SID")
                GenaManager.unsubscribe(svc, sid)
                MiniHttpServer.Response(200, "OK", "text/plain", ByteArray(0))
            }
            else -> MiniHttpServer.Response.text(405, "Method Not Allowed", "Method Not Allowed")
        }
    }

    private fun soapFault(code: Int, desc: String) =
        MiniHttpServer.Response(500, "Internal Server Error", "text/xml; charset=utf-8",
            UpnpXml.soapFault(code, desc).toByteArray(Charsets.UTF_8))

    // ---------------- AVTransport 动作实现 ----------------

    private object AvActions {

        fun dispatch(action: String, body: String, player: CastPlayer): Pair<Int, String> {
            val cs = CastState
            return when (action) {
                "GetTransportInfo" -> ok("GetTransportInfo", UpnpXml.NS_AV, """
    <CurrentTransportState>${cs.transportState}</CurrentTransportState>
    <CurrentTransportStatus>OK</CurrentTransportStatus>
    <CurrentSpeed>1</CurrentSpeed>""")

                "GetMediaInfo", "GetMediaInfoExt" -> {
                    val hasMedia = cs.currentUri.isNotEmpty()
                    ok("GetMediaInfo", UpnpXml.NS_AV, """
    <NrTracks>${if (hasMedia) 1 else 0}</NrTracks>
    <MediaDuration>${UpnpXml.formatTime(cs.durationMs)}</MediaDuration>
    <CurrentURI>${UpnpXml.esc(cs.currentUri)}</CurrentURI>
    <CurrentURIMetaData>${UpnpXml.esc(cs.currentMeta)}</CurrentURIMetaData>
    <NextURI>${UpnpXml.esc(cs.nextUri)}</NextURI>
    <NextURIMetaData></NextURIMetaData>
    <PlayMedium>${if (hasMedia) "NETWORK" else "NONE"}</PlayMedium>
    <RecordMedium>NOT_IMPLEMENTED</RecordMedium>
    <WriteStatus>NOT_IMPLEMENTED</WriteStatus>""")
                }

                "GetPositionInfo" -> {
                    val hasMedia = cs.currentUri.isNotEmpty()
                    val pos = player.currentPositionMs()
                    ok("GetPositionInfo", UpnpXml.NS_AV, """
    <Track>${if (hasMedia) 1 else 0}</Track>
    <TrackDuration>${UpnpXml.formatTime(player.durationMs())}</TrackDuration>
    <TrackMetaData>${UpnpXml.esc(cs.currentMeta)}</TrackMetaData>
    <TrackURI>${UpnpXml.esc(cs.currentUri)}</TrackURI>
    <RelTime>${UpnpXml.formatTime(pos)}</RelTime>
    <AbsTime>${UpnpXml.formatTime(pos)}</AbsTime>
    <RelCount>2147483647</RelCount>
    <AbsCount>2147483647</AbsCount>""")
                }

                "GetDeviceCapabilities" -> ok("GetDeviceCapabilities", UpnpXml.NS_AV, """
    <PlayMedia>NONE,NETWORK</PlayMedia>
    <RecMedia>NOT_IMPLEMENTED</RecMedia>
    <RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>""")

                "GetTransportSettings" -> ok("GetTransportSettings", UpnpXml.NS_AV, """
    <PlayMode>NORMAL</PlayMode>
    <RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>""")

                "SetAVTransportURI" -> {
                    val uri = arg(body, "CurrentURI")
                    val meta = arg(body, "CurrentURIMetaData")
                    android.util.Log.e("CastUrl", "SetAVTransportURI uri=$uri meta=${meta.take(300)}")
                    if (uri.isEmpty()) {
                        fault(714, "Illegal MIME-type")
                    } else {
                        player.stopIfDifferent(uri)
                        cs.currentUri = uri
                        cs.metaFromPhone = meta.isNotEmpty()
                        cs.currentMeta = meta
                        cs.title = extractTitle(meta).ifEmpty { uri.substringAfterLast('/').substringBefore('?') }
                        cs.currentMeta = if (meta.isNotEmpty()) meta else UpnpXml.didl(cs.title, uri)
                        if (cs.transportState == "NO_MEDIA_PRESENT") cs.transportState = "STOPPED"
                        cs.notifyChanged()
                        GenaManager.notifyAvTransport()
                        ok("SetAVTransportURI", UpnpXml.NS_AV, "")
                    }
                }

                "SetNextAVTransportURI" -> {
                    cs.nextUri = arg(body, "NextURI")
                    cs.notifyChanged()
                    GenaManager.notifyAvTransport()
                    ok("SetNextAVTransportURI", UpnpXml.NS_AV, "")
                }

                "Play" -> {
                    if (cs.currentUri.isEmpty()) {
                        fault(711, "No such item")
                    } else {
                        player.play(cs.currentUri, cs.title)
                        ok("Play", UpnpXml.NS_AV, "")
                    }
                }

                "Pause" -> {
                    if (cs.currentUri.isEmpty()) fault(701, "Invalid transition")
                    else {
                        player.pause()
                        ok("Pause", UpnpXml.NS_AV, "")
                    }
                }

                "Stop" -> {
                    player.stop()
                    ok("Stop", UpnpXml.NS_AV, "")
                }

                "Seek" -> {
                    val unit = arg(body, "Unit").uppercase()
                    val target = arg(body, "Target")
                    if (unit == "REL_TIME" || unit == "ABS_TIME") {
                        val ms = UpnpXml.parseTime(target)
                        if (ms == null) fault(711, "Illegal seek target")
                        else {
                            player.seekTo(ms)
                            ok("Seek", UpnpXml.NS_AV, "")
                        }
                    } else {
                        fault(710, "Seek mode not supported")
                    }
                }

                "Next", "Previous" -> fault(711, "No such item")

                "SetPlayMode" -> {
                    if (arg(body, "NewPlayMode") == "NORMAL") ok("SetPlayMode", UpnpXml.NS_AV, "")
                    else fault(712, "Play mode not supported")
                }

                "GetCurrentTransportActions" -> ok("GetCurrentTransportActions", UpnpXml.NS_AV,
                    "<Actions>Play,Stop,Pause,Seek</Actions>")

                else -> fault(401, "Invalid action")
            }
        }

        private fun CastPlayer.stopIfDifferent(uri: String) {
            if (CastState.currentUri != uri) stop()
        }

        private fun ok(action: String, ns: String, inner: String) =
            200 to UpnpXml.soapResponse(action, ns, inner)

        private fun fault(code: Int, desc: String) = 500 to UpnpXml.soapFault(code, desc)

        private fun arg(body: String, tag: String): String {
            val m = Regex("<$tag[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(body)
            return m?.groupValues?.get(1)?.let { UpnpXml.unesc(it.trim()) } ?: ""
        }

        private fun extractTitle(meta: String): String {
            if (meta.isEmpty()) return ""
            val m = Regex("<dc:title[^>]*>(.*?)</dc:title>", RegexOption.DOT_MATCHES_ALL).find(meta)
            return m?.groupValues?.get(1)?.let { UpnpXml.unesc(it.trim()) } ?: ""
        }
    }

    // ---------------- RenderingControl 动作实现 ----------------

    private object RcActions {
        fun dispatch(action: String, body: String, player: CastPlayer): Pair<Int, String> {
            val cs = CastState
            fun ok(a: String, inner: String) = 200 to UpnpXml.soapResponse(a, UpnpXml.NS_RC, inner)
            fun fault(code: Int, desc: String) = 500 to UpnpXml.soapFault(code, desc)
            return when (action) {
                "GetVolume" -> ok("GetVolume", "<CurrentVolume>${cs.volume}</CurrentVolume>")
                "SetVolume" -> {
                    val v = arg(body, "DesiredVolume").toIntOrNull() ?: return fault(401, "Invalid arg")
                    player.setVolume(v)
                    GenaManager.notifyRenderingControl()
                    ok("SetVolume", "")
                }
                "GetMute" -> ok("GetMute", "<CurrentMute>${if (cs.muted) 1 else 0}</CurrentMute>")
                "SetMute" -> {
                    player.setMute(arg(body, "DesiredMute") == "1" || arg(body, "DesiredMute").equals("true", true))
                    GenaManager.notifyRenderingControl()
                    ok("SetMute", "")
                }
                "ListPresets" -> ok("ListPresets", "<CurrentPresetNameList>FactoryDefaults</CurrentPresetNameList>")
                "SelectPreset" -> ok("SelectPreset", "")
                "GetVolumeDB" -> ok("GetVolumeDB", "<CurrentVolumeDB>${(cs.volume - 80).coerceIn(-80, 0)}</CurrentVolumeDB>")
                "GetVolumeDBRange" -> ok("GetVolumeDBRange", "<MinValue>-80</MinValue><MaxValue>0</MaxValue>")
                else -> fault(401, "Invalid action")
            }
        }

        private fun arg(body: String, tag: String): String {
            val m = Regex("<$tag[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(body)
            return m?.groupValues?.get(1)?.let { UpnpXml.unesc(it.trim()) } ?: ""
        }
    }

    // ---------------- ConnectionManager 动作实现 ----------------

    private object CmActions {
        private const val PROTOCOLS =
            "http-get:*:video/mp4:*," +
                "http-get:*:video/x-matroska:*," +
                "http-get:*:video/x-msvideo:*," +
                "http-get:*:video/x-ms-wmv:*," +
                "http-get:*:video/x-flv:*," +
                "http-get:*:video/quicktime:*," +
                "http-get:*:video/webm:*," +
                "http-get:*:video/MP2T:*," +
                "http-get:*:application/x-mpegURL:*," +
                "http-get:*:application/vnd.apple.mpegurl:*," +
                "http-get:*:application/dash+xml:*," +
                "http-get:*:audio/mpeg:*," +
                "http-get:*:audio/aac:*," +
                "http-get:*:audio/mp4:*," +
                "http-get:*:audio/flac:*"

        fun dispatch(action: String, body: String): Pair<Int, String> {
            fun ok(a: String, inner: String) = 200 to UpnpXml.soapResponse(a, UpnpXml.NS_CM, inner)
            fun fault(code: Int, desc: String) = 500 to UpnpXml.soapFault(code, desc)
            return when (action) {
                "GetProtocolInfo" -> ok("GetProtocolInfo",
                    // Sink 必须非空：哔哩哔哩发现设备后调用此动作校验渲染能力，
                    // Sink 为空会被静默过滤（爱奇艺不做该校验，故表现正常）
                    "<Source></Source><Sink>$PROTOCOLS</Sink>")
                "GetCurrentConnectionIDs" -> ok("GetCurrentConnectionIDs", "<ConnectionIDs>0</ConnectionIDs>")
                "GetCurrentConnectionInfo" -> ok("GetCurrentConnectionInfo", """
    <RcsID>0</RcsID>
    <AVTransportID>0</AVTransportID>
    <ProtocolInfo></ProtocolInfo>
    <PeerConnectionManager></PeerConnectionManager>
    <PeerConnectionID>-1</PeerConnectionID>
    <Direction>Input</Direction>
    <Status>OK</Status>""")
                else -> fault(401, "Invalid action")
            }
        }
    }
}
