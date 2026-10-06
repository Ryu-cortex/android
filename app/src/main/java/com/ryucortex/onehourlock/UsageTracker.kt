package com.ryucortex.onehourlock

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.os.Process
import java.util.Calendar

/**
 * 今日（端末のローカル時刻で0:00〜）の画面ON時間を UsageStatsManager のイベントから計算する。
 * 自前でカウントしないので、アプリが落ちていた時間も正しく含まれる。
 */
object UsageTracker {

    /** 1日の上限（ミリ秒） */
    const val DAILY_LIMIT_MS = 60L * 60L * 1000L

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun startOfToday(now: Long = System.currentTimeMillis()): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    fun startOfTomorrow(now: Long = System.currentTimeMillis()): Long =
        Calendar.getInstance().apply {
            timeInMillis = startOfToday(now)
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis

    /** 今日の画面ON時間（ミリ秒） */
    fun todayScreenTimeMs(context: Context): Long {
        val now = System.currentTimeMillis()
        val dayStart = startOfToday(now)
        val usm = context.getSystemService(UsageStatsManager::class.java)
        // 0:00 時点で画面がONだったかを知るため、前日分から遡って読む
        val events = usm.queryEvents(dayStart - 24L * 60L * 60L * 1000L, now)

        var total = 0L
        var interactiveSince: Long? = null
        var sawAnyScreenEvent = false
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    sawAnyScreenEvent = true
                    if (interactiveSince == null) interactiveSince = event.timeStamp
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    sawAnyScreenEvent = true
                    interactiveSince?.let { total += overlap(it, event.timeStamp, dayStart, now) }
                    interactiveSince = null
                }
            }
        }

        val screenOn = context.getSystemService(PowerManager::class.java).isInteractive
        if (interactiveSince != null) {
            total += overlap(interactiveSince, now, dayStart, now)
        } else if (!sawAnyScreenEvent && screenOn) {
            // 丸1日以上画面がつきっぱなしでイベントが無い場合
            total += now - dayStart
        }
        return total
    }

    private fun overlap(start: Long, end: Long, rangeStart: Long, rangeEnd: Long): Long {
        val s = maxOf(start, rangeStart)
        val e = minOf(end, rangeEnd)
        return if (e > s) e - s else 0L
    }

    fun formatDuration(ms: Long, roundUp: Boolean = false): String {
        val clamped = ms.coerceAtLeast(0L)
        val totalMin = if (roundUp) (clamped + 59_999L) / 60_000L else clamped / 60_000L
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}時間${m}分" else "${m}分"
    }
}
