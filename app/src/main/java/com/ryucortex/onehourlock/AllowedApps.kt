package com.ryucortex.onehourlock

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.TelecomManager

/**
 * ロック中でも使ってよいアプリ（電話・マップ・Spotify・QRで開いたページ）の判定と、前面アプリの検出。
 */
class AllowedApps(private val context: Context) {

    companion object {
        const val GOOGLE_MAPS = "com.google.android.apps.maps"
        const val SPOTIFY = "com.spotify.music"

        /** アプリ起動時や権限ダイアログなどで一瞬前面に出るシステム画面 */
        private val SYSTEM_HELPERS = setOf(
            "android",
            "com.android.intentresolver",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.server.telecom",
            "com.android.phone",
        )
    }

    private val pm = context.packageManager
    private val usm = context.getSystemService(UsageStatsManager::class.java)

    /** 前面に来た画面 */
    data class Foreground(val packageName: String?, val className: String?, val resumedAt: Long)

    /** このアプリの中でロック中に使ってよい画面（設定画面などは含めない） */
    private val ownAllowedClasses = setOf(
        ScanActivity::class.java.name,
        WebTabActivity::class.java.name,
    )

    private var lastForeground = Foreground(null, null, 0L)
    private var queriedUntil = 0L

    /** 電話アプリ・マップアプリ・Spotify・システム補助画面のパッケージ一覧 */
    fun packages(): Set<String> {
        val result = mutableSetOf<String>()
        result += SYSTEM_HELPERS
        result += GOOGLE_MAPS
        result += SPOTIFY
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { result += it }
        result += handlersOf(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
        result += handlersOf(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")))
        return result
    }

    fun dialIntent(): Intent =
        Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun mapsIntent(): Intent =
        (pm.getLaunchIntentForPackage(GOOGLE_MAPS)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Spotify が入っていなければ null */
    fun spotifyIntent(): Intent? =
        pm.getLaunchIntentForPackage(SPOTIFY)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 直近で前面に来たアプリのパッケージ名と、その時刻を返す。
     * 前回問い合わせ以降のイベントだけを読むので毎秒呼んでも軽い。
     */
    fun foreground(now: Long = System.currentTimeMillis()): Foreground {
        // イベントの書き込み遅れに備えて少し重ねて読む（同じイベントを再処理しても結果は同じ）
        val from = if (queriedUntil == 0L) now - 10L * 60L * 1000L else queriedUntil - 2_000L
        val events = usm.queryEvents(from, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastForeground = Foreground(event.packageName, event.className, event.timeStamp)
            }
        }
        queriedUntil = now
        return lastForeground
    }

    /** [packages] で得た一覧に入っているアプリか、このアプリの QR/ページ表示画面なら true */
    fun isAllowed(fg: Foreground, allowedPackages: Set<String>): Boolean {
        val pkg = fg.packageName ?: return false
        if (pkg == context.packageName) return fg.className in ownAllowedClasses
        return pkg in allowedPackages
    }

    fun scanIntent(): Intent =
        Intent(context, ScanActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun webTabIntent(url: String): Intent =
        WebTabActivity.intent(context, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun handlersOf(intent: Intent): List<String> =
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }
}
