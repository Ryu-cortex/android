package com.ryucortex.onehourlock

import android.content.Context

/**
 * ロック中に QR コードから開いたページの一覧。日付が変わると空になる。
 */
object ScannedTabs {
    private const val PREFS = "scanned_tabs"
    private const val KEY_DAY = "day"
    private const val KEY_URLS = "urls"
    private const val MAX_TABS = 20

    fun list(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_DAY, 0L) != UsageTracker.startOfToday()) return emptyList()
        return prefs.getString(KEY_URLS, "").orEmpty().split('\n').filter { it.isNotBlank() }
    }

    fun add(context: Context, url: String) {
        val urls = (listOf(url) + list(context).filter { it != url }).take(MAX_TABS)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_DAY, UsageTracker.startOfToday())
            .putString(KEY_URLS, urls.joinToString("\n"))
            .apply()
    }
}
