package com.kimi.casttv.dlna

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.MembershipKey
import kotlin.concurrent.thread

/**
 * SSDP 发现服务：
 * 1. 监听 239.255.255.250:1900 多播，响应 M-SEARCH
 * 2. 周期性发送 NOTIFY ssdp:alive 宣告本设备
 *
 * 使用 DatagramChannel + AF_INET（IPv4 套接字）：
 * 部分虚拟网卡/电视盒内核对 IPv6 双栈 UDP 的 v4-mapped 接收支持有缺陷，
 * IPv4 套接字与 toybox 等原生工具行为一致，兼容性最好。
 */
class SsdpServer {

    companion object {
        private const val SSDP_HOST = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val MAX_AGE = 1800
        // 10 秒一播：部分手机 App（如哔哩哔哩）主要依赖被动监听 NOTIFY 宣告发现设备，
        // 高频宣告可让它们在最短时间内看到本设备
        private const val ALIVE_INTERVAL_MS = 10_000L
    }

    @Volatile
    private var running = false
    private var channel: DatagramChannel? = null
    private var loopThread: Thread? = null
    private var aliveThread: Thread? = null
    private val memberships = mutableListOf<MembershipKey>()

    /** 最近一次收到任意 SSDP 报文的时间（看门狗诊断用） */
    @Volatile
    private var lastPacketAt: Long = 0L

    /** 最近一次从外部设备学到"手机所在网段"的时间；超过 120s 未刷新则过期，
     *  恢复为全网卡宣告，避免学到错误网卡后永久锁定、手机再也搜不到（v2.2.3） */
    @Volatile
    private var lastLearnedAt: Long = 0L

    private val notifyTypes: List<Pair<String, String>>
        get() {
            val udn = CastState.udn
            return listOf(
                "upnp:rootdevice" to "$udn::upnp:rootdevice",
                udn to udn,
                "urn:schemas-upnp-org:device:MediaRenderer:1" to "$udn::urn:schemas-upnp-org:device:MediaRenderer:1",
                "urn:schemas-upnp-org:service:AVTransport:1" to "$udn::urn:schemas-upnp-org:service:AVTransport:1",
                "urn:schemas-upnp-org:service:RenderingControl:1" to "$udn::urn:schemas-upnp-org:service:RenderingControl:1",
                "urn:schemas-upnp-org:service:ConnectionManager:1" to "$udn::urn:schemas-upnp-org:service:ConnectionManager:1"
            )
        }

    fun start() {
        if (running) return
        running = true
        startLoopThread()
        aliveThread = thread(name = "ssdp-alive") {
            Thread.sleep(3000) // 等待 runLoop 完成绑定与加组
            // 启动爆发：连发 3 轮宣告，覆盖"控制端刚启动、监听还没就绪"的竞态窗口
            sendAlive()
            thread(name = "ssdp-boot-burst", isDaemon = true) {
                Thread.sleep(600)
                runCatching { sendAlive() }
                Thread.sleep(600)
                runCatching { sendAlive() }
            }
            while (running) {
                // 看门狗1：接收线程若意外死亡（历史上的"必须重启软件才能被搜到"根因），自动拉起
                if (loopThread?.isAlive != true) {
                    android.util.Log.e("Ssdp", "SSDP loop thread died, restarting")
                    runCatching { channel?.close() }
                    startLoopThread()
                }
                // 看门狗2：线程仍活着但长期收不到任何报文（socket 僵死），强制重绑重加组
                val idleSec = (System.currentTimeMillis() - lastPacketAt) / 1000
                if (idleSec > 150) {
                    android.util.Log.e("Ssdp", "no SSDP packet for ${idleSec}s, rebinding")
                    runCatching { channel?.close() }
                    startLoopThread()
                }
                android.util.Log.d("Ssdp", "alive heartbeat, no packet for ${idleSec}s")
                sendAlive()
                var slept = 0L
                while (running && slept < ALIVE_INTERVAL_MS) {
                    Thread.sleep(1000)
                    slept += 1000
                }
            }
        }
    }

    private fun startLoopThread() {
        lastPacketAt = System.currentTimeMillis()
        loopThread = thread(name = "ssdp") {
            runCatching { runLoop() }.onFailure { android.util.Log.e("Ssdp", "runLoop error", it) }
        }
    }

    fun stop() {
        running = false
        runCatching { channel?.close() }
        runCatching { aliveThread?.interrupt() }
    }

    private fun runLoop() {
        val group = InetAddress.getByName(SSDP_HOST)
        val ch = DatagramChannel.open(StandardProtocolFamily.INET).apply {
            setOption(StandardSocketOptions.SO_REUSEADDR, true)
            setOption(StandardSocketOptions.IP_MULTICAST_TTL, 4)
            bind(InetSocketAddress("0.0.0.0", SSDP_PORT))
        }
        // 在所有支持多播的非回环接口上加入组
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
        for (ni in interfaces) {
            if (!ni.isUp || ni.isLoopback || !ni.supportsMulticast()) continue
            runCatching {
                val key = ch.join(group, ni)
                synchronized(memberships) { memberships.add(key) }
            }
        }
        channel = ch
        android.util.Log.d("Ssdp", "SSDP listening on 0.0.0.0:$SSDP_PORT, joined ${memberships.size} interfaces")
        sendAlive()

        val buf = ByteBuffer.allocate(2048)
        while (running) {
            buf.clear()
            val source = runCatching { ch.receive(buf) }.getOrNull() ?: continue
            if (source !is InetSocketAddress) continue
            lastPacketAt = System.currentTimeMillis()
            buf.flip()
            val text = Charsets.ISO_8859_1.decode(buf).toString()
            if (text.startsWith("M-SEARCH", ignoreCase = true)) {
                // 单包异常不能杀死整个接收线程
                runCatching { handleSearch(text, source) }
                    .onFailure { android.util.Log.e("Ssdp", "handleSearch error", it) }
            }
        }
    }

    private fun handleSearch(text: String, source: InetSocketAddress) {
        val st = Regex("^ST\\s*:\\s*(.+)$", RegexOption.MULTILINE)
            .find(text)?.groupValues?.get(1)?.trim()?.lowercase() ?: return
        if (!stMatches(st)) return
        CastState.ssdpMsearchCount++
        CastState.lastMsearchAt = System.currentTimeMillis()
        CastState.lastMsearchSource = "${source.address.hostAddress}:${source.port}"
        android.util.Log.d("Ssdp", "M-SEARCH from ${source.address.hostAddress}:${source.port} ST=$st")
        respond(source, st)
        // 同步补发一轮多播 alive 宣告：针对"控制端启动时搜索竞态丢包、弹窗不重搜"类 App，
        // 让它们通过被动发现通道立刻看到本设备
        sendAlive()
    }

    private fun stMatches(st: String): Boolean {
        val udn = CastState.udn.lowercase()
        return st == "ssdp:all" ||
            st == "upnp:rootdevice" ||
            st == udn ||
            st == "urn:schemas-upnp-org:device:mediarenderer:1" ||
            st.startsWith("urn:schemas-upnp-org:service:")
    }

    private fun respond(target: InetSocketAddress, st: String) {
        val ch = channel ?: return
        val udn = CastState.udn
        val actualSt = if (st == "ssdp:all") "upnp:rootdevice" else st
        val usn = if (actualSt == udn.lowercase()) udn else "$udn::$actualSt"
        // 按"到这台手机的路由"选本机 IP：多网卡电视（如海信虚拟网卡）也能保证
        // 回复的 LOCATION 与搜索方同网段、真实可达
        val lanIp = localIpFor(target.address)
        // 只有"外部设备"（手机/电脑）的搜索才更新本机代表 IP 与宣告网卡；
        // 电视自身系统服务（如聚好看）可能从虚拟网卡发搜索，
        // 若由它决定宣告网卡，上线 NOTIFY 会全部发到虚拟网卡网段，手机端永远收不到
        val isSelf = CastState.allIps.contains(target.address.hostAddress)
        if (!isSelf) {
            CastState.localIp = lanIp
            lastLearnedAt = System.currentTimeMillis()
        }
        val msg = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("CACHE-CONTROL: max-age=$MAX_AGE\r\n")
            append("EXT: \r\n")
            append("LOCATION: http://$lanIp:${CastState.httpPort}/device.xml\r\n")
            append("SERVER: ${UpnpXml.serverString()}\r\n")
            append("ST: $actualSt\r\n")
            append("USN: $usn\r\n")
            append("BOOTID.UPNP.ORG: 1\r\n")
            append("CONFIGID.UPNP.ORG: 1\r\n")
            append("\r\n")
        }
        val bytes = msg.toByteArray(Charsets.ISO_8859_1)
        runCatching { ch.send(ByteBuffer.wrap(bytes), target) }
        CastState.ssdpResponseCount++
        // 200ms 后重发一份：Wi-Fi 下偶发的 UDP 丢包会导致手机端搜索列表为空
        thread(name = "ssdp-retry", isDaemon = true) {
            Thread.sleep(200)
            runCatching { ch.send(ByteBuffer.wrap(bytes), target) }
        }
    }

    /** 计算与 remote 通信时本端应使用的 IP：UDP connect 不发包，仅让内核按路由表选出口 */
    private fun localIpFor(remote: InetAddress): String {
        runCatching {
            java.net.DatagramSocket().use { s ->
                s.connect(remote, SSDP_PORT)
                val ip = s.localAddress?.hostAddress
                if (ip != null && ip.isNotEmpty() && !ip.startsWith("127.")) return ip
            }
        }
        return CastState.localIp.ifEmpty { CastState.detectLocalIp() }
    }

    private fun multicastInterfaces(): List<NetworkInterface> =
        NetworkInterface.getNetworkInterfaces()?.toList()?.filter {
            it.isUp && !it.isLoopback && it.supportsMulticast()
        } ?: emptyList()

    private fun ipsOn(ni: NetworkInterface): List<java.net.Inet4Address> =
        ni.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }

    /**
     * 多网卡设备（如海信电视的虚拟网卡）上，NOTIFY 必须逐网卡发送：
     * 每块网卡以自己网段的 IP 作为 LOCATION，并把多播出口切到该网卡。
     * 否则默认路由走虚拟网卡时，宣告里的 LOCATION 是不可达地址，
     * 依赖主动宣告发现设备的 App（如哔哩哔哩）会永远搜不到设备，
     * 而只认 M-SEARCH 响应的 App（如爱奇艺）却正常——症状极其隐蔽。
     *
     * 但同一 USN 的 NOTIFY 不许带两个不同 LOCATION：手机端按 USN 去重时
     * 后到的宣告会覆盖先到的，坏 IP 的宣告会把好 IP 的响应覆盖掉（v1.13 前的
     * "搜索数增加但永远搜不到"根因）。因此一旦从 M-SEARCH 学到了手机所在网段
     * （CastState.localIp 由 respond() 按搜索方逐请求更新），就只从该网卡宣告。
     */
    private fun sendAlive() {
        val ch = channel ?: return
        val groupAddr = InetSocketAddress(InetAddress.getByName(SSDP_HOST), SSDP_PORT)
        val httpPort = CastState.httpPort
        val serverStr = UpnpXml.serverString()
        val learnedIp =
            if (CastState.ssdpMsearchCount > 0 &&
                System.currentTimeMillis() - lastLearnedAt < 120_000
            ) CastState.localIp else ""
        for (ni in multicastInterfaces()) {
            val ips = ipsOn(ni)
            if (ips.isEmpty()) continue
            // 学到手机网段后，只保留与手机同网卡的那个 IP
            val useIps = if (learnedIp.isNotEmpty()) ips.filter { it.hostAddress == learnedIp } else ips
            if (useIps.isEmpty()) continue
            runCatching { ch.setOption(StandardSocketOptions.IP_MULTICAST_IF, ni) }
            for (ip in useIps) {
                val location = "http://${ip.hostAddress}:$httpPort/device.xml"
                for ((nt, usn) in notifyTypes) {
                    val msg = buildString {
                        append("NOTIFY * HTTP/1.1\r\n")
                        append("HOST: $SSDP_HOST:$SSDP_PORT\r\n")
                        append("CACHE-CONTROL: max-age=$MAX_AGE\r\n")
                        append("LOCATION: $location\r\n")
                        append("NT: $nt\r\n")
                        append("NTS: ssdp:alive\r\n")
                        append("SERVER: $serverStr\r\n")
                        append("USN: $usn\r\n")
                        append("BOOTID.UPNP.ORG: 1\r\n")
                        append("\r\n")
                    }
                    runCatching { ch.send(ByteBuffer.wrap(msg.toByteArray(Charsets.ISO_8859_1)), groupAddr) }
                }
            }
        }
    }

    fun sendByeBye() {
        val ch = channel ?: return
        val groupAddr = InetSocketAddress(InetAddress.getByName(SSDP_HOST), SSDP_PORT)
        for ((nt, usn) in notifyTypes) {
            val msg = buildString {
                append("NOTIFY * HTTP/1.1\r\n")
                append("HOST: $SSDP_HOST:$SSDP_PORT\r\n")
                append("NT: $nt\r\n")
                append("NTS: ssdp:byebye\r\n")
                append("USN: $usn\r\n")
                append("\r\n")
            }
            runCatching { ch.send(ByteBuffer.wrap(msg.toByteArray(Charsets.ISO_8859_1)), groupAddr) }
        }
    }
}
