package com.kimi.casttv.dlna

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.kimi.casttv.MainActivity
import com.kimi.casttv.R
import com.kimi.casttv.player.CastPlayer
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * 前台服务：常驻运行 DLNA MediaRenderer（SSDP 发现 + UPnP 控制 + 播放）。
 */
class DlnaService : Service() {

    companion object {
        const val CHANNEL_ID = "cast_service"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ALERT_ID = "cast_alert"
        const val NOTIFICATION_ALERT_ID = 2
        const val PREFS = "cast_tv_prefs"
        const val KEY_UDN = "udn"
        const val KEY_UDN_VERSION = "udn_version"
        const val KEY_NAME = "friendly_name"
        const val DEFAULT_NAME = "YuHao投屏"
        const val LEGACY_NAME = "投屏电视"

        @Volatile
        var running = false
            private set

        /** 运行中的服务实例，供播放器回调"投屏已开始"时发全屏意图通知（v2.2.5） */
        @Volatile
        var instance: DlnaService? = null
    }

    private lateinit var castPlayer: CastPlayer
    private lateinit var upnpServer: UpnpHttpServer
    private lateinit var airPlayServer: AirPlayServer
    private lateinit var airPlayAnnouncer: AirPlayAnnouncer
    private val ssdp = SsdpServer()
    private var multicastLock: WifiManager.MulticastLock? = null
    private val handler = Handler(Looper.getMainLooper())

    /** 播放期间周期性上报进度事件（供手机端显示进度条） */
    private val progressReporter = object : Runnable {
        override fun run() {
            if (CastState.transportState == "PLAYING") {
                GenaManager.notifyAvTransport()
            }
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        instance = this

        // 设备身份：UDN 按版本重新生成——手机 App（如哔哩哔哩）会缓存设备 UDN，
        // 旧版本 UDN 若曾关联过不可达地址，可能被长期标记为不可用而不再展示；
        // 换 UDN 相当于一台"全新设备"，强制控制端重新评估
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ver = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        val savedVer = prefs.getString(KEY_UDN_VERSION, null)
        if (savedVer != ver) {
            CastState.udn = CastState.freshUdn()
            prefs.edit().putString(KEY_UDN, CastState.udn).putString(KEY_UDN_VERSION, ver).apply()
        } else {
            CastState.udn = prefs.getString(KEY_UDN, null) ?: CastState.freshUdn().also {
                prefs.edit().putString(KEY_UDN, it).apply()
            }
        }
        val savedName = prefs.getString(KEY_NAME, null)
        CastState.friendlyName = when {
            savedName == null -> DEFAULT_NAME
            // 旧版默认名迁移到新名称；用户自定义的名称保留
            savedName == LEGACY_NAME -> DEFAULT_NAME
            else -> savedName
        }
        prefs.edit().putString(KEY_NAME, CastState.friendlyName).apply()
        CastState.localIp = CastState.detectLocalIp()
        CastState.allIps = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { it.isUp && !it.isLoopback }
                ?.flatMap { it.inetAddresses.toList() }
                ?.filterIsInstance<Inet4Address>()
                ?.map { it.hostAddress ?: "" }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
        }.getOrDefault(emptyList())
        CastState.serviceStartedAt = System.currentTimeMillis()

        // 多播锁：部分设备不持锁收不到 SSDP 多播报文
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("casttv-ssdp").apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }

        // 播放器 + UPnP 服务器 + SSDP
        castPlayer = CastPlayer(applicationContext)
        val icon = runCatching { assets.open("icon.png").readBytes() }.getOrNull()
        upnpServer = UpnpHttpServer(castPlayer, icon)
        CastState.httpPort = upnpServer.port
        ssdp.start()

        // AirPlay（iPhone 投屏）：HTTP 控制服务 + Bonjour 宣告；deviceId 由 UDN 派生的稳定伪 MAC
        airPlayServer = AirPlayServer(castPlayer, airPlayDeviceId())
        airPlayAnnouncer = AirPlayAnnouncer(applicationContext, airPlayDeviceId())
        airPlayAnnouncer.start(airPlayServer.port)

        startForegroundWithNotification()
        handler.post(progressReporter)
        selfTestLoopback()
    }

    /** 从 UDN 派生稳定的伪 MAC 作为 AirPlay deviceid（取 UUID 前 6 字节） */
    private fun airPlayDeviceId(): String {
        val hex = CastState.udn.removePrefix("uuid:").replace("-", "").take(12).uppercase()
        val padded = (hex + "AABBCCDDEEFF").take(12)
        return (0 until 6).joinToString(":") { padded.substring(it * 2, it * 2 + 2) }
    }

    /** 自测：应用进程内回环访问自己的 UPnP 服务器，验证协议栈与回环通路 */
    private fun selfTestLoopback() {
        thread(name = "self-test") {
            Thread.sleep(2000)
            val port = CastState.httpPort
            runCatching {
                val conn = java.net.URL("http://127.0.0.1:$port/device.xml").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                val code = conn.responseCode
                val len = conn.inputStream.readBytes().size
                android.util.Log.e("SelfTest", "loopback GET /device.xml -> HTTP $code, $len bytes")
            }.onFailure {
                android.util.Log.e("SelfTest", "loopback GET failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 用户从最近任务中划掉应用：彻底退出并停止后台播放
        android.util.Log.i("DlnaService", "task removed, stopping service and playback")
        CastState.player?.let {
            runCatching { it.stop() }
            runCatching { it.clearMediaItems() }
        }
        CastState.transportState = "STOPPED"
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        instance = null
        handler.removeCallbacks(progressReporter)
        runCatching { ssdp.sendByeBye() }
        ssdp.stop()
        runCatching { airPlayAnnouncer.stop() }
        runCatching { airPlayServer.stop() }
        runCatching { upnpServer.stop() }
        runCatching { castPlayer.release() }
        GenaManager.clear()
        runCatching { multicastLock?.release() }
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.channel_desc) }
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(CastState.friendlyName)
            .setContentText(getString(R.string.waiting))
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfoForeground())
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ServiceInfoForeground(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        else 0

    /**
     * 投屏开始时的兜底拉起（v2.2.5）：Android 14（API 34）起，应用退到后台后
     * 服务直接 startActivity 会被系统拦截（Background activity launch blocked），
     * 表现为"有声音没画面、黑屏无报错"。走全屏意图通知是系统认可的后台拉起
     * 通道（前台服务的媒体播放场景 + USE_FULL_SCREEN_INTENT 权限）。
     */
    fun onCastStarted() {
        if (CastState.visibleActivityCount > 0) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT_ID, "投屏播放", NotificationManager.IMPORTANCE_HIGH)
        )
        val intent = Intent(this, com.kimi.casttv.PlaybackActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = buildCastPendingIntent(intent)
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(CastState.title.ifEmpty { getString(R.string.now_playing) })
            .setContentText("投屏已开始，点击进入播放画面")
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setTimeoutAfter(20_000)
            .build()
        runCatching { nm.notify(NOTIFICATION_ALERT_ID, notification) }
            .onFailure { android.util.Log.e("DlnaService", "cast alert notify failed", it) }
    }

    /** API 34+ 需显式允许 PendingIntent 在后台启动 Activity */
    private fun buildCastPendingIntent(intent: Intent): PendingIntent {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= 34) {
            val opts = android.app.ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                    android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            PendingIntent.getActivity(this, 1, intent, flags, opts.toBundle())
        } else {
            PendingIntent.getActivity(this, 1, intent, flags)
        }
    }
}
