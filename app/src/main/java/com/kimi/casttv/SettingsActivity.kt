package com.kimi.casttv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.kimi.casttv.dlna.CastState
import com.kimi.casttv.dlna.DlnaService

/**
 * 设置页：设备名称、更新服务器与检查更新、版本信息、诊断信息。
 */
class SettingsActivity : android.app.Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var diagText: TextView
    private lateinit var versionText: TextView

    private fun formatElapsed(): String {
        val s = (System.currentTimeMillis() - CastState.serviceStartedAt) / 1000
        val h = s / 3600; val m = (s % 3600) / 60
        return if (h > 0) "${h}时${m}分" else "${m}分${s % 60}秒"
    }

    private val diagUpdater = object : Runnable {
        override fun run() {
            val lastSearch = if (CastState.lastMsearchAt > 0) {
                val ago = (System.currentTimeMillis() - CastState.lastMsearchAt) / 1000
                "最近搜索：${CastState.lastMsearchSource}（${ago}秒前）"
            } else "尚未收到任何手机搜索"
            val lastHttp = if (CastState.lastHttpAt > 0) {
                val ago = (System.currentTimeMillis() - CastState.lastHttpAt) / 1000
                "最近访问：${CastState.lastHttpPath}（${ago}秒前）\n" +
                    "访问记录：${CastState.recentHttpPaths.joinToString(" · ")}\n" +
                    "客户端：${CastState.lastHttpAgent.ifEmpty { "未知" }}"
            } else "尚无手机访问"
            diagText.text = "运行 ${formatElapsed()}\n" +
                "已收到手机搜索 ${CastState.ssdpMsearchCount} 次，$lastSearch\n" +
                "手机已访问本机 ${CastState.httpRequestCount} 次\n" +
                "$lastHttp\n" +
                "本机地址 ${CastState.allIps.joinToString("、").ifEmpty { "无" }}"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        diagText = findViewById(R.id.diagText)
        versionText = findViewById(R.id.versionText)

        versionText.text = "${getString(R.string.app_name)} v${UpdateChecker.currentVersionName(this)}" +
            "（build ${UpdateChecker.currentVersionCode(this)}）"

        // 设备名称
        val nameEdit = findViewById<EditText>(R.id.nameEdit)
        nameEdit.setText(CastState.friendlyName)
        findViewById<Button>(R.id.saveNameButton).setOnClickListener {
            val name = nameEdit.text.toString().trim().ifEmpty { DlnaService.DEFAULT_NAME }
            CastState.friendlyName = name
            getSharedPreferences(DlnaService.PREFS, MODE_PRIVATE)
                .edit().putString(DlnaService.KEY_NAME, name).apply()
            toast(getString(R.string.settings_saved))
        }

        // 更新服务器地址
        val urlEdit = findViewById<EditText>(R.id.updateUrlEdit)
        urlEdit.setText(UpdateChecker.updateUrl(this))
        findViewById<Button>(R.id.saveUrlButton).setOnClickListener {
            UpdateChecker.saveUpdateUrl(this, urlEdit.text.toString())
            toast(getString(R.string.settings_update_url_saved))
        }
        findViewById<Button>(R.id.checkUpdateButton).setOnClickListener {
            val url = UpdateChecker.updateUrl(this)
            if (url.isEmpty()) {
                toast(getString(R.string.settings_update_no_url))
            } else {
                toast(getString(R.string.settings_update_checking))
                UpdateChecker.checkInBackground(this, url, showLatestDialog = true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(diagUpdater)
    }

    override fun onPause() {
        handler.removeCallbacks(diagUpdater)
        super.onPause()
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
}
