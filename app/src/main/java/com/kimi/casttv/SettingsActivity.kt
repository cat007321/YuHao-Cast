package com.kimi.casttv

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.kimi.casttv.dlna.CastState
import com.kimi.casttv.dlna.DlnaService

/**
 * 设置页：设备名称、更新服务器与检查更新、版本信息。
 */
class SettingsActivity : android.app.Activity() {

    private lateinit var versionText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
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

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
}
