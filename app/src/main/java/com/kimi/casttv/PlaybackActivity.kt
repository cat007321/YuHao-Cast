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
    private var imageView: android.widget.ImageView? = null
    private var errorText: android.widget.TextView? = null
    private var urlText: android.widget.TextView? = null
    private var seekText: android.widget.TextView? = null
    private var resolutionScroll: android.widget.HorizontalScrollView? = null
    private var resolutionRow: android.widget.LinearLayout? = null
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 已加载到 ImageView 的图片代际（-1 = 未加载） */
    @Volatile
    private var loadedImageEpoch = -1
    private var imageLoading = false

    private val statusUpdater = object : Runnable {
        override fun run() {
            updateImageLayer()
            refreshResolutionRow()
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
        imageView = findViewById(R.id.imageView)
        errorText = findViewById(R.id.errorText)
        urlText = findViewById(R.id.urlText)
        seekText = findViewById(R.id.seekText)
        // 分辨率选择行在自定义控制条布局里，从 playerView 内部查找
        playerView?.let { pv ->
            resolutionScroll = pv.findViewById(R.id.resolution_scroll)
            resolutionRow = pv.findViewById(R.id.resolution_row)
        }
    }

    override fun onStart() {
        super.onStart()
        playerView?.player = if (CastState.currentIsImage) null else CastState.player
        applyVolume()
        uiHandler.post(statusUpdater)
    }

    override fun onStop() {
        uiHandler.removeCallbacks(statusUpdater)
        playerView?.player = null
        super.onStop()
    }

    // ---------------- 图片投屏渲染 ----------------

    /** 按 CastState 切换 视频层/图片层，并在图片地址变化（epoch 变化）时重新加载 */
    private fun updateImageLayer() {
        if (CastState.currentIsImage) {
            playerView?.visibility = android.view.View.GONE
            imageView?.visibility = android.view.View.VISIBLE
            val epoch = CastState.imageEpoch
            if (epoch != loadedImageEpoch && !imageLoading) {
                loadedImageEpoch = epoch
                loadImage(CastState.currentUri)
            }
        } else {
            if (imageView?.visibility == android.view.View.VISIBLE) {
                imageView?.setImageDrawable(null)
                imageView?.visibility = android.view.View.GONE
                loadedImageEpoch = -1
            }
            playerView?.visibility = android.view.View.VISIBLE
            playerView?.player = CastState.player
        }
    }

    /** 后台线程下载图片并按屏幕尺寸降采样解码（原图可能高达几千万像素，直接解码必 OOM） */
    private fun loadImage(uri: String) {
        if (uri.isEmpty()) return
        imageLoading = true
        val maxW = 1920
        val maxH = 1080
        Thread {
            var bitmap: android.graphics.Bitmap? = null
            var errMsg = ""
            try {
                val conn = java.net.URL(uri).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                conn.instanceFollowRedirects = true
                conn.inputStream.use { input ->
                    // 第一遍只读尺寸
                    val bytes = input.readBytes()
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                        errMsg = "无法识别的图片格式"
                    } else {
                        // 第二遍按 1920x1080 上限降采样
                        var sample = 1
                        while (bounds.outWidth / (sample * 2) >= maxW || bounds.outHeight / (sample * 2) >= maxH) {
                            sample *= 2
                        }
                        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                        bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        if (bitmap == null) errMsg = "图片解码失败"
                    }
                }
            } catch (e: OutOfMemoryError) {
                errMsg = "图片过大，内存不足"
            } catch (e: Exception) {
                errMsg = e.javaClass.simpleName + ": " + (e.message ?: "")
            }
            runOnUiThread {
                imageLoading = false
                // 加载期间又切换了内容：丢弃过期结果
                if (!CastState.currentIsImage || CastState.imageEpoch != loadedImageEpoch) return@runOnUiThread
                if (bitmap != null) {
                    imageView?.setImageBitmap(bitmap)
                    CastState.lastError = ""
                } else {
                    imageView?.setImageDrawable(null)
                    CastState.lastError = "图片加载失败：$errMsg"
                    CastState.lastErrorAt = System.currentTimeMillis()
                }
            }
        }.apply { isDaemon = true }.start()
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
        CastState.currentIsImage = false
        CastState.imageEpoch++
        CastState.durationMs = -1L
        CastState.transportState = "STOPPED"
        CastState.notifyChanged()
        GenaManager.notifyAvTransport()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            // 焦点在分辨率选择按钮上时，方向键/确定键交给按钮自身处理（移动焦点/选择）
            val focusInResolution = resolutionRow?.findFocus() != null
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (!focusInResolution) { seekBy(-10_000); return true } }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (!focusInResolution) { seekBy(10_000); return true } }
                // 确定键：暂停/继续（遥控器无独立暂停键）
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (!focusInResolution) { togglePlayPause(); return true }
                }
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

    /** 确定键：暂停/继续切换，并同步 DLNA 状态给手机端 */
    private fun togglePlayPause() {
        if (CastState.currentIsImage) return
        val p = CastState.player ?: return
        if (p.isPlaying) {
            p.pause()
            CastState.transportState = "PAUSED_PLAYBACK"
        } else {
            p.play()
            CastState.transportState = "PLAYING"
        }
        CastState.notifyChanged()
        GenaManager.notifyAvTransport()
    }

    // ---------------- 分辨率选择 ----------------

    private var resolutionEpoch = -1   // 已渲染的轨道代际
    private var resolutionHeights = listOf<Int>()
    private var selectedHeight = -1

    /** 从 ExoPlayer 当前轨道收集可用视频分辨率（高→低去重），控制条可见时刷新按钮行 */
    private fun refreshResolutionRow() {
        val row = resolutionRow ?: return
        val scroll = resolutionScroll ?: return
        if (CastState.currentIsImage || !controllerVisible) {
            scroll.visibility = android.view.View.GONE
            return
        }
        val p = CastState.player ?: return
        val groups = p.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
        // 高→低去重收集高度
        val heights = sortedSetOf<Int>(compareByDescending { it })
        groups.forEach { g ->
            for (i in 0 until g.length) {
                g.getTrackFormat(i).height.takeIf { it > 0 }?.let { heights.add(it) }
            }
        }
        val epoch = heights.hashCode() * 31 + groups.size
        if (heights.size <= 1) {
            // 单一路清（多数投屏流）：无选择必要，整行隐藏
            scroll.visibility = android.view.View.GONE
            resolutionEpoch = epoch
            resolutionHeights = heights.toList()
            return
        }
        if (epoch == resolutionEpoch) {
            scroll.visibility = android.view.View.VISIBLE
            return
        }
        resolutionEpoch = epoch
        resolutionHeights = heights.toList()
        row.removeAllViews()
        heights.forEach { h ->
            val btn = android.widget.Button(this).apply {
                text = "${h}P"
                textSize = 14f
                setPadding(24, 8, 24, 8)
                setOnClickListener { selectResolution(h) }
            }
            row.addView(btn, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 16 })
        }
        // 默认：ExoPlayer 默认轨道策略即选最高清晰度，高亮它
        selectedHeight = heights.first()
        updateResolutionHighlight()
        scroll.visibility = android.view.View.VISIBLE
        android.util.Log.i("PlaybackActivity", "resolution row: ${heights.toList()} default=${selectedHeight}P")
    }

    private fun updateResolutionHighlight() {
        val row = resolutionRow ?: return
        for (i in 0 until row.childCount) {
            val btn = row.getChildAt(i) as? android.widget.Button ?: continue
            val h = btn.text.toString().removeSuffix("P").toIntOrNull() ?: continue
            if (h == selectedHeight) {
                btn.setTextColor(0xFF000000.toInt())
                btn.setBackgroundColor(0xFFFFFFFF.toInt())
            } else {
                btn.setTextColor(0xFFFFFFFF.toInt())
                btn.setBackgroundColor(0x66000000)
            }
        }
    }

    /** 选中某分辨率：对该轨道组下该高度的最高码率轨道做 override */
    private fun selectResolution(height: Int) {
        val p = CastState.player ?: return
        val selector = p.trackSelector as? androidx.media3.exoplayer.trackselection.DefaultTrackSelector ?: return
        val groups = p.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
        var bestGroup: androidx.media3.common.Tracks.Group? = null
        var bestIndex = -1
        var bestBitrate = -1
        groups.forEach { g ->
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                if (f.height == height && f.bitrate > bestBitrate) {
                    bestBitrate = f.bitrate
                    bestGroup = g
                    bestIndex = i
                }
            }
        }
        val g = bestGroup ?: return
        selector.setParameters(
            selector.parameters.buildUpon()
                .setOverrideForType(
                    androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, listOf(bestIndex))
                )
        )
        selectedHeight = height
        updateResolutionHighlight()
        android.util.Log.i("PlaybackActivity", "select resolution ${height}P track#$bestIndex bitrate=$bestBitrate")
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
        CastState.currentIsImage = false
        CastState.imageEpoch++
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
        if (CastState.currentIsImage) return // 图片不支持快进快退
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
