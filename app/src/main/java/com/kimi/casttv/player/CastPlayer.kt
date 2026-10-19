package com.kimi.casttv.player

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.kimi.casttv.PlaybackActivity
import com.kimi.casttv.dlna.CastState
import com.kimi.casttv.dlna.GenaManager

/**
 * 投屏播放器：由 DlnaService 持有，DLNA 控制动作最终都汇聚到这里。
 * 必须在主线程创建（Service onCreate 即主线程）。
 */
class CastPlayer(private val appContext: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val loadControl = DefaultLoadControl.Builder()
        // minBuffer 30s / maxBuffer 60s：网络抖动时不频繁触发卡顿重缓冲
        .setBufferDurationsMs(30_000, 60_000, 2_500, 5_000)
        // 保留 30s 已播放缓冲：快进回退秒开，不用重新下载
        .setBackBuffer(30_000, false)
        .build()

    private val analyticsListener = object : AnalyticsListener {
        private var loadErrCount = 0
        override fun onIsLoadingChanged(eventTime: AnalyticsListener.EventTime, isLoading: Boolean) {
            android.util.Log.d("CastPlayer", "isLoading=$isLoading buffered=${eventTime.totalBufferedDurationMs}ms")
        }
        override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo, mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData, error: java.io.IOException, wasCanceled: Boolean) {
            loadErrCount++
            android.util.Log.w("CastPlayer", "loadError#$loadErrCount ${error.javaClass.simpleName}: ${error.message}")
            CastState.lastError = "加载失败#${loadErrCount}: ${error.javaClass.simpleName}: ${error.message?.take(200)}"
            CastState.lastErrorAt = System.currentTimeMillis()
        }
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            android.util.Log.w("CastPlayer", "droppedFrames=$droppedFrames in ${elapsedMs}ms")
        }
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            android.util.Log.d("CastPlayer", "videoDecoder=$decoderName init=${initializationDurationMs}ms")
        }
    }

    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setLoadControl(loadControl)
        .build()
        .also {
        it.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        it.addAnalyticsListener(analyticsListener)
        it.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    CastState.transportState = "PLAYING"
                    CastState.durationMs = if (it.duration > 0) it.duration else CastState.durationMs
                    CastState.notifyChanged()
                    GenaManager.notifyAvTransport()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                android.util.Log.d("CastPlayer", "playbackState=$state transportState=${CastState.transportState}")
                when (state) {
                    Player.STATE_BUFFERING -> {
                        CastState.transportState = "TRANSITIONING"
                        GenaManager.notifyAvTransport()
                    }
                    Player.STATE_ENDED -> {
                        android.util.Log.d("CastPlayer", "STATE_ENDED, nextUri='${CastState.nextUri}'")
                        if (CastState.nextUri.isNotEmpty()) {
                            val next = CastState.nextUri
                            CastState.nextUri = ""
                            play(next, CastState.title)
                        } else if (CastState.currentUri.isNotEmpty()) {
                            CastState.transportState = "STOPPED"
                            CastState.notifyChanged()
                            GenaManager.notifyAvTransport()
                        }
                    }
                    Player.STATE_IDLE -> Unit
                    Player.STATE_READY -> {
                        CastState.durationMs = if (it.duration > 0) it.duration else -1L
                        if (!it.playWhenReady && CastState.transportState == "PLAYING") {
                            CastState.transportState = "PAUSED_PLAYBACK"
                            CastState.notifyChanged()
                            GenaManager.notifyAvTransport()
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("CastPlayer", "onPlayerError ${error.errorCodeName}: ${error.message}")
                // HLS 改写地址失败时，回退到原始地址（仅一次）
                val fb = CastState.fallbackUri
                if (fb.isNotEmpty()) {
                    CastState.fallbackUri = ""
                    CastState.lastError = "${error.errorCodeName}（HLS 失败，回退原地址重试）"
                    CastState.lastErrorAt = System.currentTimeMillis()
                    playInternal(fb, CastState.title, null)
                    return
                }
                CastState.lastError = friendlyError(error)
                CastState.lastErrorAt = System.currentTimeMillis()
                CastState.transportState = "STOPPED"
                CastState.notifyChanged()
                GenaManager.notifyAvTransport()
            }
        })
    }

    /** 把 ExoPlayer 错误码翻译成用户看得懂的原因 */
    private fun friendlyError(error: PlaybackException): String {
        val uri = CastState.currentUri
        val raw = "${error.errorCodeName}: ${error.message?.take(200)}"
        // 优酷对受版权/DRM 保护的视频会返回"空播放列表"（无分片的合法 m3u8），
        // ExoPlayer 报 PARSING_CONTAINER_UNSUPPORTED
        if ((error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED) &&
            uri.contains("youku.com")
        ) {
            return "优酷未提供可播放的投屏地址（该视频可能受版权/DRM 限制，或投屏链接已过期），请重新投屏或换个普通视频试试"
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED &&
            uri.contains(".m3u8")
        ) {
            return "播放列表无效或已过期，请在手机上重新投屏一次"
        }
        return raw
    }

    init {
        CastState.player = player
        applyVolume()
        startPositionTicker()
    }

    /** 主线程定时采样播放位置（ExoPlayer 禁止跨线程访问） */
    private var tickCount = 0
    private fun startPositionTicker() {
        mainHandler.post(object : Runnable {
            override fun run() {
                tickCount++
                runCatching {
                    if (!CastState.currentIsImage) {
                        CastState.positionMs = player.currentPosition
                        if (player.duration > 0) CastState.durationMs = player.duration
                    }
                }
                if (CastState.transportState == "PLAYING") {
                    android.util.Log.d(
                        "CastPlayer",
                        "buf total=${player.totalBufferedDuration}ms pos=${CastState.positionMs}"
                    )
                }
                if (tickCount % 20 == 0) {
                    android.util.Log.d("CastPlayer", "ticker alive pos=${CastState.positionMs} state=${CastState.transportState}")
                }
                mainHandler.postDelayed(this, 500)
            }
        })
    }

    /** 收到 SetAVTransportURI + Play */
    fun play(uri: String, title: String) {
        android.util.Log.e("CastUrl", "play uri=$uri")
        // 图片投屏：手机相册类 App 会把图片 URL 直接 SetAVTransportURI+Play，
        // 交给 ExoPlayer 会按视频解析直接 OOM，这里分流到图片渲染
        if (isImageUri(uri)) {
            playImage(uri, title)
            return
        }
        // 腾讯云直播类的 rtmp:// 地址通常同时提供 HLS(.m3u8)；
        // 其 RTMP 流多为 HEVC+FLV(codec id 12)，ExoPlayer 的 FLV 解封装不支持，
        // 改写为 HLS 后由 TS 解封装处理（HEVC 解码依赖设备解码器）。
        val hlsUri = rtmpToHls(uri)
        if (hlsUri != null) android.util.Log.e("CastUrl", "rewritten to HLS: $hlsUri")
        val (primary, fallback) = if (hlsUri != null) hlsUri to uri else uri to null
        playInternal(primary, title, fallback)
    }

    /** 依据 DLNA 元数据（imageItem）或扩展名判断是否为图片地址 */
    private fun isImageUri(uri: String): Boolean {
        if (CastState.currentMeta.contains("imageItem")) return true
        val path = uri.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
            path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".bmp")
    }

    /** 图片投屏：停掉视频播放，切到图片渲染（PlaybackActivity 监听 imageEpoch 加载） */
    private fun playImage(uri: String, title: String) {
        mainHandler.post {
            android.util.Log.d("CastPlayer", "playImage() $uri")
            runCatching { player.stop() }
            runCatching { player.clearMediaItems() }
            CastState.currentUri = uri
            CastState.title = title.ifEmpty { uri.substringAfterLast('/').substringBefore('?') }
            CastState.currentIsImage = true
            CastState.imageEpoch++
            CastState.lastError = ""
            CastState.fallbackUri = ""
            CastState.transportState = "PLAYING"
            CastState.durationMs = 0L
            CastState.positionMs = 0L
            CastState.notifyChanged()
            GenaManager.notifyAvTransport()
            openPlaybackScreen()
        }
    }

    private fun playInternal(uri: String, title: String, fallbackUri: String?) {
        mainHandler.post {
            android.util.Log.d("CastPlayer", "play() executing on main thread")
            CastState.currentUri = uri
            CastState.title = title.ifEmpty { uri.substringAfterLast('/').substringBefore('?') }
            CastState.currentIsImage = false
            CastState.imageEpoch++
            CastState.lastError = ""
            CastState.fallbackUri = fallbackUri ?: ""
            CastState.transportState = "TRANSITIONING"
            CastState.durationMs = -1L
            CastState.notifyChanged()
            GenaManager.notifyAvTransport()

            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.playWhenReady = true
            applyVolume()
            openPlaybackScreen()
        }
    }

    /** rtmp://host/app/stream?query -> http://host/app/stream.m3u8?query；无法改写返回 null */
    private fun rtmpToHls(uri: String): String? {
        if (!uri.startsWith("rtmp://")) return null
        val withoutScheme = uri.removePrefix("rtmp://")
        val queryIdx = withoutScheme.indexOf('?')
        val path = if (queryIdx >= 0) withoutScheme.substring(0, queryIdx) else withoutScheme
        val query = if (queryIdx >= 0) withoutScheme.substring(queryIdx) else ""
        // path 已含 app/stream；在最后一段后追加 .m3u8
        val lastSlash = path.lastIndexOf('/')
        if (lastSlash < 0 || lastSlash == path.length - 1) return null
        return "http://${path.substring(0, lastSlash + 1)}${path.substring(lastSlash + 1)}.m3u8$query"
    }

    fun pause() {
        mainHandler.post {
            if (CastState.currentIsImage) {
                // 图片无"播放"动作，暂停即冻结展示
                CastState.transportState = "PAUSED_PLAYBACK"
            } else {
                player.pause()
                CastState.transportState = "PAUSED_PLAYBACK"
            }
            CastState.notifyChanged()
            GenaManager.notifyAvTransport()
        }
    }

    fun stop() {
        android.util.Log.d("CastPlayer", "stop() called")
        mainHandler.post {
            player.stop()
            player.clearMediaItems()
            CastState.currentIsImage = false
            CastState.imageEpoch++
            CastState.transportState = "STOPPED"
            CastState.durationMs = -1L
            CastState.notifyChanged()
            GenaManager.notifyAvTransport()
        }
    }

    fun resume() {
        mainHandler.post {
            if (CastState.currentIsImage) {
                CastState.transportState = "PLAYING"
                CastState.notifyChanged()
                GenaManager.notifyAvTransport()
                openPlaybackScreen()
                return@post
            }
            if (player.mediaItemCount == 0 && CastState.currentUri.isNotEmpty()) {
                player.setMediaItem(MediaItem.fromUri(CastState.currentUri))
                player.prepare()
            }
            player.playWhenReady = true
            applyVolume()
            openPlaybackScreen()
        }
    }

    fun seekTo(ms: Long) {
        if (CastState.currentIsImage) return // 图片不支持快进快退
        mainHandler.post {
            player.seekTo(ms)
            GenaManager.notifyAvTransport()
        }
    }

    fun setVolume(volume: Int) {
        CastState.volume = volume.coerceIn(0, 100)
        applyVolume()
    }

    fun setMute(muted: Boolean) {
        CastState.muted = muted
        applyVolume()
    }

    private fun applyVolume() {
        mainHandler.post {
            player.volume = if (CastState.muted) 0f else CastState.volume / 100f
        }
    }

    fun currentPositionMs(): Long = CastState.positionMs

    fun durationMs(): Long = CastState.durationMs

    private fun openPlaybackScreen() {
        runCatching {
            val intent = Intent(appContext, PlaybackActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        }
    }

    fun release() {
        mainHandler.post {
            CastState.player = null
            player.release()
        }
    }
}
