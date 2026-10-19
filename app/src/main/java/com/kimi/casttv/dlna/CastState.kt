package com.kimi.casttv.dlna

import androidx.media3.exoplayer.ExoPlayer
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局共享状态：设备身份 + 当前播放状态。
 * DLNA 控制点（手机）通过 HTTP/SOAP 修改这里，UI 与播放器读取这里。
 */
object CastState {

    /** 设备 UDN，首次启动生成并持久化 */
    @Volatile
    var udn: String = ""

    @Volatile
    var friendlyName: String = "YuHao投屏"

    /** 本机局域网 IPv4 */
    @Volatile
    var localIp: String = ""

    /** 所有可用 IPv4 地址（诊断用） */
    @Volatile
    var allIps: List<String> = emptyList()

    // ---------------- 诊断计数 ----------------

    @Volatile
    var ssdpMsearchCount = 0

    @Volatile
    var ssdpResponseCount = 0

    @Volatile
    var lastMsearchAt: Long = 0L

    /** 最近一次 M-SEARCH 来源（IP:端口，诊断用） */
    @Volatile
    var lastMsearchSource: String = ""

    @Volatile
    var httpRequestCount = 0

    /** 诊断：最近访问的路径 / 客户端标识 / 时间 */
    @Volatile
    var lastHttpPath: String = ""

    @Volatile
    var lastHttpAgent: String = ""

    @Volatile
    var lastHttpAt: Long = 0L

    /** 最近几次访问的路径（新→旧，最多 5 条，诊断用） */
    val recentHttpPaths = CopyOnWriteArrayList<String>()

    @Volatile
    var serviceStartedAt: Long = 0L

    /** UPnP HTTP 服务器端口 */
    @Volatile
    var httpPort: Int = 0

    val deviceUrl: String
        get() = "http://$localIp:$httpPort/device.xml"

    // ---------------- 播放状态 ----------------

    @Volatile
    var currentUri: String = ""

    @Volatile
    var currentMeta: String = ""

    @Volatile
    var nextUri: String = ""

    @Volatile
    var title: String = ""

    /** NO_MEDIA_PRESENT / STOPPED / PLAYING / PAUSED_PLAYBACK / TRANSITIONING */
    @Volatile
    var transportState: String = "NO_MEDIA_PRESENT"

    @Volatile
    var volume: Int = 80

    @Volatile
    var muted: Boolean = false

    @Volatile
    var player: ExoPlayer? = null

    /** 播放器实际就绪的媒体时长（毫秒，未知为 -1） */
    @Volatile
    var durationMs: Long = -1L

    /** 最近一次播放位置（主线程定时刷新，后台线程只读） */
    @Volatile
    var positionMs: Long = 0L

    /** 最近一次播放/加载错误（显示在播放界面，诊断用） */
    @Volatile
    var lastError: String = ""

    @Volatile
    var lastErrorAt: Long = 0L

    /** HLS 改写失败时的回退地址（原始 rtmp 地址，只回退一次） */
    @Volatile
    var fallbackUri: String = ""

    /** 当前投屏内容是否为图片（图片走图片渲染，不走 ExoPlayer） */
    @Volatile
    var currentIsImage: Boolean = false

    /** 图片代际：图片地址变化时 +1，播放界面据此重新加载 */
    @Volatile
    var imageEpoch: Int = 0

    /** 状态变化监听（主线程通知 UI / 事件订阅） */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun notifyChanged() {
        listeners.forEach { runCatching { it() } }
    }

    fun freshUdn(): String = "uuid:" + UUID.randomUUID().toString()

    fun detectLocalIp(): String {
        // 优先用"默认路由出口网卡"的地址：UDP connect 不发包，只让内核选出上网出口。
        // 双网卡设备（如电视同时有虚拟网卡/双网口）能正确选到与手机同网段的那块网卡，
        // 避免把不可达的虚拟网卡 IP（如 192.168.232.x）写进投屏地址。
        runCatching {
            java.net.DatagramSocket().use { s ->
                s.connect(java.net.InetAddress.getByName("114.114.114.114"), 80)
                val ip = s.localAddress?.hostAddress
                if (ip != null && ip.isNotEmpty() && !ip.startsWith("127.")) {
                    android.util.Log.i("CastState", "detectLocalIp via default route: $ip")
                    return ip
                }
            }
        }
        // 回退：第一块支持多播的非回环网卡
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ""
            for (ni in interfaces.toList()) {
                if (!ni.isUp || ni.isLoopback || !ni.supportsMulticast()) continue
                for (addr in ni.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        }
        return ""
    }
}
