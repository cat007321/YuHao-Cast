package com.kimi.casttv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.kimi.casttv.dlna.CastState
import com.kimi.casttv.dlna.DlnaService

/**
 * TV 主界面（正式版）：应用名 / 投屏状态 / 设备名 / 局域网地址。
 * 无设置入口：设备名与更新服务器按默认值运行，更新检查全自动。
 */
class MainActivity : android.app.Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var deviceNameText: TextView
    private lateinit var ipText: TextView
    private lateinit var statusText: TextView

    private val uiUpdater = object : Runnable {
        override fun run() {
            deviceNameText.text = CastState.friendlyName
            ipText.text = if (CastState.localIp.isEmpty()) "未连接网络"
            else "IP：${CastState.localIp}:${CastState.httpPort}"
            statusText.text = when (CastState.transportState) {
                "PLAYING" -> "正在播放：${CastState.title}"
                "PAUSED_PLAYBACK" -> "已暂停：${CastState.title}"
                "TRANSITIONING" -> "正在缓冲…"
                "STOPPED" -> if (CastState.currentUri.isNotEmpty()) "已停止" else getString(R.string.waiting)
                else -> getString(R.string.waiting)
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        deviceNameText = findViewById(R.id.deviceNameText)
        ipText = findViewById(R.id.ipText)
        statusText = findViewById(R.id.statusText)

        // 启动投屏服务
        ContextCompat.startForegroundService(this, Intent(this, DlnaService::class.java))

        // 自动检查更新（每天最多一次，仅在已配置更新服务器时）
        UpdateChecker.maybeAutoCheck(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(uiUpdater)
    }

    override fun onPause() {
        handler.removeCallbacks(uiUpdater)
        super.onPause()
    }
}
