package com.ryucortex.onehourlock

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * デバイス管理者。有効な間はアプリをアンインストールできず、
 * オーバーレイ権限が無効化された場合でも画面ロックで制限を続けられる。
 */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "無効にすると「1時間ロック」をアンインストールできるようになります。本当に無効にしますか？"
}
