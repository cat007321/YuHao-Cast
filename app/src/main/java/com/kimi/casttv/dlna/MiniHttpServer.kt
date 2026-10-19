package com.kimi.casttv.dlna

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 极简 HTTP/1.1 服务器，足以承载 UPnP：
 * 支持 GET / POST / SUBSCRIBE / UNSUBSCRIBE 等任意方法、Keep-Alive、Content-Length 定界。
 */
class MiniHttpServer(
    preferredPort: Int,
    private val handler: (Request) -> Response
) {
    class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>, // key 已转小写
        val body: ByteArray,
        val remoteIp: String
    ) {
        fun header(name: String): String? = headers[name.lowercase(Locale.US)]
    }

    class Response(
        val status: Int,
        val reason: String,
        val contentType: String,
        val body: ByteArray,
        val headers: List<Pair<String, String>> = emptyList()
    ) {
        companion object {
            fun text(status: Int, reason: String, text: String, contentType: String = "text/plain; charset=utf-8") =
                Response(status, reason, contentType, text.toByteArray(Charsets.UTF_8))

            fun xml(text: String) = text(200, "OK", text, "text/xml; charset=utf-8")
        }
    }

    private val serverSocket = ServerSocket().apply {
        // 显式绑定 IPv4：部分虚拟网卡（如模拟器桥接）对 IPv6 双栈回环支持有缺陷
        val bindAddr = { p: Int -> java.net.InetSocketAddress(java.net.Inet4Address.getByName("0.0.0.0"), p) }
        // 优先固定端口：手机端常会缓存投屏设备地址，固定端口可避免软件重启后手机仍连旧端口
        runCatching { bind(bindAddr(preferredPort)) }
            .onFailure { bind(bindAddr(0)) }
    }
    val listeningPort: Int get() = serverSocket.localPort

    @Volatile
    private var running = true

    private val acceptThread = thread(name = "mini-http-accept") {
        while (running) {
            try {
                val socket = serverSocket.accept()
                thread(name = "mini-http-conn") { handleConnection(socket) }
            } catch (e: Exception) {
                if (running) e.printStackTrace()
            }
        }
    }

    fun close() {
        running = false
        runCatching { serverSocket.close() }
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            var keepAlive = true
            while (running && keepAlive) {
                val request = readRequest(input, socket) ?: return
                CastState.httpRequestCount++
                // 诊断：记录最近的访问路径与客户端标识，主界面显示，用于定位
                // "控制端已连通但拒绝展示设备"类问题
                CastState.lastHttpPath = "${request.method} ${request.path}"
                CastState.lastHttpAgent = request.header("user-agent")?.take(80) ?: ""
                CastState.lastHttpAt = System.currentTimeMillis()
                CastState.recentHttpPaths.add(0, buildString {
                    append(request.path.substringBefore('?').take(28))
                    // SOAP 控制请求附带动作名，如 #GetProtocolInfo——排查"连上了却不显示"的关键线索
                    request.header("soapaction")?.substringAfterLast('#')?.take(20)?.let { append("#$it") }
                    // 访问者 IP：确认请求来自哪台设备
                    append(" ←")
                    append(request.remoteIp)
                })
                while (CastState.recentHttpPaths.size > 5) CastState.recentHttpPaths.removeAt(CastState.recentHttpPaths.size - 1)
                android.util.Log.d("MiniHttp", "request: ${request.method} ${request.path} body=${request.body.size}")
                keepAlive = shouldKeepAlive(request)
                val response = try {
                    handler(request)
                } catch (e: Exception) {
                    android.util.Log.e("MiniHttp", "handler error", e)
                    Response.text(500, "Internal Server Error", "Internal Server Error")
                }
                android.util.Log.d("MiniHttp", "response: ${response.status} bytes=${response.body.size}")
                writeResponse(output, response, keepAlive)
            }
        } catch (e: Exception) {
            android.util.Log.e("MiniHttp", "conn error: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun shouldKeepAlive(request: Request): Boolean {
        val conn = request.header("connection")?.lowercase(Locale.US)
        if (conn == "close") return false
        if (conn == "keep-alive") return true
        return request.header("x-http-version") != "HTTP/1.0"
    }

    private fun readRequest(input: BufferedInputStream, socket: Socket): Request? {
        // 读取请求行 + 头部，直到空行
        val headerBytes = ByteArrayOutputStream()
        var prev = -1
        var cur: Int
        var matched = false
        var byteCount = 0
        while (true) {
            cur = input.read()
            if (cur == -1) {
                android.util.Log.d("MiniHttp", "read EOF, headerBytes=$byteCount")
                return null
            }
            if (byteCount == 0) android.util.Log.d("MiniHttp", "first byte arrived")
            headerBytes.write(cur)
            byteCount++
            if (byteCount > 64 * 1024) return null
            if (prev == '\r'.code && cur == '\n'.code) {
                // 检查是否以 \r\n\r\n 结尾（即出现两个连续 CRLF）
                val data = headerBytes.toByteArray()
                if (data.size >= 4 && data[data.size - 2] == '\r'.code.toByte() && data[data.size - 3] == '\n'.code.toByte()) {
                    matched = true
                    break
                }
            }
            prev = cur
        }
        if (!matched) return null
        val headText = String(headerBytes.toByteArray(), Charsets.ISO_8859_1).trim()
        val lines = headText.split("\r\n")
        if (lines.isEmpty()) return null
        val requestLine = lines[0].split(" ")
        if (requestLine.size < 2) return null
        val method = requestLine[0]
        val path = requestLine[1]
        val version = if (requestLine.size > 2) requestLine[2] else "HTTP/1.1"

        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase(Locale.US)] = line.substring(idx + 1).trim()
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 8 * 1024 * 1024) ?: 0
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buf, read, contentLength - read)
                if (n == -1) break
                read += n
            }
            buf.copyOf(read)
        } else ByteArray(0)

        return Request(method, path, headers + ("x-http-version" to version), body, socket.inetAddress.hostAddress ?: "")
    }

    private fun writeResponse(output: OutputStream, response: Response, keepAlive: Boolean) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(response.status).append(' ').append(response.reason).append("\r\n")
        head.append("Content-Type: ").append(response.contentType).append("\r\n")
        head.append("Content-Length: ").append(response.body.size).append("\r\n")
        head.append("Server: ").append(UpnpXml.serverString()).append("\r\n")
        for ((k, v) in response.headers) head.append(k).append(": ").append(v).append("\r\n")
        head.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        output.write(response.body)
        output.flush()
    }
}
