package com.kimi.casttv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 更新检查：从「更新服务器地址」拉取 version.json，格式：
 *   {"versionCode":21,"versionName":"2.1","url":"https://example.com/app.apk","notes":"更新说明"}
 * versionCode 大于本机即视为有新版本，弹窗引导下载（交给系统浏览器/下载器）。
 */
object UpdateChecker {

    private const val KEY_UPDATE_URL = "update_url"
    private const val KEY_LAST_CHECK = "last_update_check"

    /** 默认更新源：GitHub 仓库中的 version.json（经 jsDelivr CDN 加速，国内可直接访问） */
    const val DEFAULT_UPDATE_URL =
        "https://cdn.jsdelivr.net/gh/cat007321/YuHao-Cast@main/update/version.json"

    class Info(
        val versionCode: Long,
        val versionName: String,
        val url: String,
        val notes: String
    )

    sealed class Result {
        data class UpdateAvailable(val info: Info) : Result()
        object Latest : Result()
        data class Error(val message: String) : Result()
    }

    fun currentVersionCode(activity: Activity): Long = runCatching {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)

    fun currentVersionName(activity: Activity): String = runCatching {
        @Suppress("DEPRECATION")
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    /** 读取更新服务器地址；用户清空保存后返回空串（表示停用自动检查） */
    fun updateUrl(activity: Activity): String =
        activity.getSharedPreferences(DlnaPrefs.NAME, android.content.Context.MODE_PRIVATE)
            .getString(KEY_UPDATE_URL, DEFAULT_UPDATE_URL) ?: DEFAULT_UPDATE_URL

    fun saveUpdateUrl(activity: Activity, url: String) {
        activity.getSharedPreferences(DlnaPrefs.NAME, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_UPDATE_URL, url.trim()).apply()
    }

    /** 每次启动应用自动检查（10 分钟内重复启动不重复检查，避免 Activity 重建时重复请求）。
     *  仅在已配置更新服务器时执行。发现新版本弹窗提示，已是最新则静默。 */
    fun maybeAutoCheck(activity: Activity) {
        val url = updateUrl(activity)
        if (url.isEmpty()) return
        val prefs = activity.getSharedPreferences(DlnaPrefs.NAME, android.content.Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < 10 * 60 * 1000) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        checkInBackground(activity, url, showLatestDialog = false)
    }

    /** 后台执行检查，回调到主线程。showLatestDialog=false 时「已是最新」不打扰用户。 */
    fun checkInBackground(activity: Activity, url: String, showLatestDialog: Boolean = true) {
        thread(name = "update-check") {
            val result = check(url, currentVersionCode(activity))
            android.util.Log.i("UpdateChecker", "check result: $result")
            Handler(Looper.getMainLooper()).post {
                android.util.Log.i("UpdateChecker", "posting result, finishing=${activity.isFinishing} destroyed=${activity.isDestroyed}")
                if (activity.isFinishing || activity.isDestroyed) return@post
                when (result) {
                    is Result.UpdateAvailable -> showUpdateDialog(activity, result.info)
                    Result.Latest ->
                        if (showLatestDialog) infoDialog(activity, activity.getString(R.string.settings_update_latest))
                    is Result.Error ->
                        if (showLatestDialog) infoDialog(
                            activity,
                            activity.getString(R.string.settings_update_failed) + result.message
                        )
                }
            }
        }
    }

    /** 同步检查（须在后台线程调用）。 */
    fun check(url: String, currentCode: Long): Result {
        if (url.isEmpty()) return Result.Error("未配置更新服务器地址")
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != 200) return Result.Error("HTTP $code")
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val remoteCode = json.optLong("versionCode", 0)
            val info = Info(
                versionCode = remoteCode,
                versionName = json.optString("versionName", ""),
                url = json.optString("url", ""),
                notes = json.optString("notes", "")
            )
            conn.disconnect()
            if (remoteCode > currentCode) Result.UpdateAvailable(info) else Result.Latest
        }.getOrElse { Result.Error(it.message ?: it.javaClass.simpleName) }
    }

    private fun showUpdateDialog(activity: Activity, info: Info) {
        val message = buildString {
            append("新版本 v").append(info.versionName).append("（当前 v")
                .append(currentVersionName(activity)).append("）")
            if (info.notes.isNotEmpty()) {
                append("\n\n").append(info.notes)
            }
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.settings_update_found))
            .setMessage(message)
            .setPositiveButton(activity.getString(R.string.settings_update_download)) { _, _ ->
                startDownload(activity, info)
            }
            .setNegativeButton(activity.getString(R.string.settings_update_cancel), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
        }
        dialog.show()
        android.util.Log.i("UpdateChecker", "update dialog shown")
    }

    /** 应用内下载更新包：带进度条弹窗，下载完成自动调起系统安装器。
     *  GitHub Release 资产地址会 302 跳转，instanceFollowRedirects 自动跟随。 */
    private fun startDownload(activity: Activity, info: Info) {
        // 进度条布局（代码构建，TV 遥控器友好）
        val content = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (activity.resources.displayMetrics.density * 24).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val percentText = android.widget.TextView(activity).apply {
            text = "准备下载…"
            textSize = 16f
        }
        val progressBar = android.widget.ProgressBar(
            activity, null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            isIndeterminate = true
        }
        content.addView(percentText)
        content.addView(progressBar)

        val progressDialog = AlertDialog.Builder(activity)
            .setTitle("正在下载 v" + info.versionName)
            .setView(content)
            .setNegativeButton("取消", null)
            .create()
        progressDialog.setOnShowListener {
            progressDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
        }
        progressDialog.show()

        val mainHandler = Handler(Looper.getMainLooper())
        thread(name = "apk-download") {
            var outFile: java.io.File? = null
            var errMsg = ""
            try {
                val conn = URL(info.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                conn.instanceFollowRedirects = true
                if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                val dir = java.io.File(activity.cacheDir, "updates")
                dir.mkdirs()
                // 清掉历史更新包，避免缓存目录堆积
                dir.listFiles()?.forEach { it.delete() }
                outFile = java.io.File(dir, "update-v${info.versionName}.apk")
                var downloaded = 0L
                var lastPost = 0L
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(outFile).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            val now = System.currentTimeMillis()
                            // 每 200ms 刷新一次进度，避免频繁刷 UI
                            if (now - lastPost > 200 && total > 0) {
                                lastPost = now
                                val pct = (downloaded * 100 / total).toInt()
                                val mbDone = downloaded / 1048576.0
                                val mbTotal = total / 1048576.0
                                mainHandler.post {
                                    if (activity.isFinishing || activity.isDestroyed) return@post
                                    progressBar.isIndeterminate = false
                                    progressBar.progress = pct
                                    percentText.text = String.format(
                                        "已下载 %.1f / %.1f MB（%d%%）", mbDone, mbTotal, pct
                                    )
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
                if (total > 0 && downloaded < total) throw java.io.IOException("下载不完整（$downloaded/$total）")
            } catch (t: Throwable) {
                errMsg = t.message ?: t.javaClass.simpleName
                outFile?.delete()
                outFile = null
            }
            val file = outFile
            mainHandler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                if (progressDialog.isShowing) progressDialog.dismiss()
                if (file != null) {
                    installApk(activity, file)
                } else {
                    infoDialog(activity, "下载失败：$errMsg")
                }
            }
        }
    }

    /** 下载完成自动调起系统安装器（系统弹安装确认框，用户点「安装」即可） */
    private fun installApk(activity: Activity, file: java.io.File) {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                activity, activity.packageName + ".fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        }.onFailure {
            infoDialog(activity, "无法打开安装器：${it.message}")
        }
    }

    private fun infoDialog(activity: Activity, message: String) {
        val dialog = AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton("确定", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
        }
        dialog.show()
    }
}

/** 全局共享偏好文件名（与服务层一致） */
object DlnaPrefs {
    const val NAME = "cast_tv_prefs"
}
