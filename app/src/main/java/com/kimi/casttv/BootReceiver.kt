package com.kimi.casttv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.kimi.casttv.dlna.DlnaService

/**
 * 开机自启动：投影/电视重启后自动拉起投屏服务，
 * 无需先打开应用即可被手机搜索到。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        android.util.Log.i("BootReceiver", "boot completed, starting cast service")
        ContextCompat.startForegroundService(context, Intent(context, DlnaService::class.java))
    }
}
