package com.kimi.casttv

import android.os.Bundle
import android.view.KeyEvent
import androidx.media3.ui.PlayerView
import com.kimi.casttv.dlna.CastState
import com.kimi.casttv.dlna.GenaManager

/**
 * 投屏播放界面：全屏显示 CastPlayer 的播放器实例。
 */
class PlaybackActivity : android.app.Activity() {

    private var playerView: PlayerView? = null
    private var errorText: android.widget.TextView? = null
    private var urlText: android.widget.TextView? = null
    private var seekText: android.widget.TextView? = null
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val statusUpdater = object : Runnable {
        override fun run() {
            val err = CastState.lastError
            if (err.isNotEmpty() && System.currentTimeMillis() - CastState.lastErrorAt < 60_000) {
                errorText?.visibility = android.view.View.VISIBLE
                errorText?.text = "播放出错：$err"
            } else {
                errorText?.visibility = android.view.View.GONE
            }
            val uri = CastState.currentUri
            if (uri.isNotEmpty() && CastState.transportState != "PLAYING") {
                urlText?.visibility = android.view.View.VISIBLE
                urlText?.text = "等待播放/缓冲中：$uri"
            } else {
                urlText?.visibility = android.view.View.GONE
            }
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById<PlayerView>(R.id.playerView)?.apply {
            useController = true
            // 老版本 media3 无 isControllerVisible 查询，用监听器自行跟踪控制条可见性
            setControllerVisibilityListener(androidx.media3.ui.PlayerView.ControllerVisibilityListener { visibility ->
                controllerVisible = visibility == android.view.View.VISIBLE
            })
        }
        errorText = findViewById(R.id.errorText)
        urlText = findViewById(R.id.urlText)
        seekText = findViewById(R.id.seekText)
    }

    override fun onStart() {
        super.onStart()
        playerView?.player = CastState.player
        applyVolume()
        uiHandler.post(statusUpdater)
    }

    override fun onStop() {
        uiHandler.removeCallbacks(statusUpdater)
        playerView?.player = null
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 用户要求关闭后台播放：返回键退出播放界面时停止播放
        stopPlayback()
        super.onBackPressed()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 按主页键/切换应用离开播放界面：停止播放，不留在后台响
        stopPlayback()
    }

    private fun stopPlayback() {
        CastState.player?.let { p ->
            runCatching { p.stop() }
            runCatching { p.clearMediaItems() }
        }
        CastState.currentUri = ""
        CastState.durationMs = -1L
        CastState.transportState = "STOPPED"
        CastState.notifyChanged()
        GenaManager.notifyAvTransport()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-10_000); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(10_000); return true }
                KeyEvent.KEYCODE_BACK -> { onBackRequested(); return true }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    CastState.volume = (CastState.volume + 5).coerceIn(0, 100)
                    CastState.player?.volume = if (CastState.muted) 0f else CastState.volume / 100f
                    GenaManager.notifyRenderingControl()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    CastState.volume = (CastState.volume - 5).coerceIn(0, 100)
                    CastState.player?.volume = if (CastState.muted) 0f else CastState.volume / 100f
                    GenaManager.notifyRenderingControl()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun applyVolume() {
        CastState.player?.volume = if (CastState.muted) 0f else CastState.volume / 100f
    }

    // ---------------- 返回键分级处理 ----------------

    private var exitDialog: android.app.AlertDialog? = null

    /** 播放控制条当前是否可见（由 ControllerVisibilityListener 维护） */
    @Volatile
    private var controllerVisible = false

    /**
     * 返回键分级：
     * 1. 屏显信息（快进提示 / 播放控制条）可见时 → 先关掉屏显，不退出
     * 2. 无屏显时 → 弹"是否退出投屏"确认框，确认才真正退出
     */
    private fun onBackRequested() {
        exitDialog?.let { if (it.isShowing) { it.dismiss(); return } }
        if (seekText?.visibility == android.view.View.VISIBLE) {
            hideSeekOverlay()
            return
        }
        if (controllerVisible) {
            playerView?.hideController()
            return
        }
        showExitDialog()
    }

    private fun showExitDialog() {
        exitDialog = android.app.AlertDialog.Builder(this)
            .setTitle("退出投屏")
            .setMessage("是否退出当前投屏？")
            .setPositiveButton("退出投屏") { _, _ -> doExit() }
            .setNegativeButton("取消", null)
            .setOnCancelListener { exitDialog = null }
            .create()
            .also { d ->
                // 电视上按钮默认无焦点，弹窗布局完成后聚焦"退出投屏"，遥控器 OK 键直接确认
                d.setOnShowListener {
                    d.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.requestFocus()
                }
                d.show()
            }
    }

    /** 退出投屏：停止播放并回到主界面 */
    private fun doExit() {
        CastState.player?.let { p ->
            p.stop()
            p.clearMediaItems()
        }
        CastState.currentUri = ""
        CastState.title = ""
        CastState.transportState = "STOPPED"
        CastState.durationMs = -1L
        CastState.notifyChanged()
        com.kimi.casttv.dlna.GenaManager.notifyAvTransport()
        finish()
    }

    // ---------------- 遥控器快进快退 ----------------

    private var seekHideRunnable: Runnable? = null

    /** 相对跳转 deltaMs 毫秒，并在大屏闪现位置提示。长按方向键时系统会派发重复按下事件，天然支持连续快进 */
    private fun seekBy(deltaMs: Long) {
        val p = CastState.player ?: return
        val duration = p.duration.takeIf { it > 0 }
        var target = p.currentPosition + deltaMs
        target = target.coerceAtLeast(0)
        if (duration != null) target = target.coerceAtMost(duration)
        p.seekTo(target)
        CastState.positionMs = target
        GenaManager.notifyAvTransport()

        val total = duration ?: CastState.durationMs.takeIf { it > 0 }
        val label = if (deltaMs >= 0) "快进 10 秒" else "快退 10 秒"
        seekText?.text = "$label\n${formatTime(target)}${if (total != null) " / " + formatTime(total) else ""}"
        seekText?.visibility = android.view.View.VISIBLE
        seekHideRunnable?.let { uiHandler.removeCallbacks(it) }
        seekHideRunnable = Runnable { hideSeekOverlay() }.also { uiHandler.postDelayed(it, 2000) }
    }

    private fun hideSeekOverlay() {
        seekHideRunnable?.let { uiHandler.removeCallbacks(it) }
        seekHideRunnable = null
        seekText?.visibility = android.view.View.GONE
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
