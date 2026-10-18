package com.ryucortex.onehourlock

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.TelecomManager

/**
 * ロック中でも使ってよいアプリ（電話・マップ・Spotify）の判定と、前面アプリの検出。
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

    private var lastForeground: String? = null
    private var lastForegroundAt = 0L
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
    fun foreground(now: Long = System.currentTimeMillis()): Pair<String?, Long> {
        // イベントの書き込み遅れに備えて少し重ねて読む（同じイベントを再処理しても結果は同じ）
        val from = if (queriedUntil == 0L) now - 10L * 60L * 1000L else queriedUntil - 2_000L
        val events = usm.queryEvents(from, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastForeground = event.packageName
                lastForegroundAt = event.timeStamp
            }
        }
        queriedUntil = now
        return lastForeground to lastForegroundAt
    }

    private fun handlersOf(intent: Intent): List<String> =
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }
}
