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
    private lateinit var ipText: TextView
    private lateinit var statusText: TextView
    private lateinit var versionText: TextView

    private val uiUpdater = object : Runnable {
        override fun run() {
            ipText.text = if (CastState.localIp.isEmpty()) "未连接网络" else CastState.localIp
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
        ipText = findViewById(R.id.ipText)
        statusText = findViewById(R.id.statusText)
        versionText = findViewById(R.id.versionText)
        // 主界面下方显示当前版本号
        versionText.text = "v" + UpdateChecker.currentVersionName(this)

        // 启动投屏服务
        ContextCompat.startForegroundService(this, Intent(this, DlnaService::class.java))

        // 启动时自动检查更新（10 分钟内重复启动只查一次；发现新版本弹窗提示）
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
