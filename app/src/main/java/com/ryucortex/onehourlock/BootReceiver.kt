package com.ryucortex.onehourlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 再起動・アプリ更新後に監視を自動で再開する */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> runCatching { LimitService.start(context) }
        }
    }
}
