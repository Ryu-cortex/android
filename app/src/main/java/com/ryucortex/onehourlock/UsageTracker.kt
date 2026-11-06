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
 * 夜に使わなかった日は、朝の一定時間をカウントしない（朝ボーナス）。
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

    // ---- 朝ボーナス ----
    // 前日 22:00〜今日 6:00 の画面ON時間が合計 NIGHT_TOLERANCE_MS 以下なら、
    // 今日 6:00〜9:00 に使った最初の MORNING_BONUS_MS は1時間の上限にカウントしない。

    private const val NIGHT_START_HOUR = 22
    private const val NIGHT_END_HOUR = 6
    private const val MORNING_END_HOUR = 9

    /** 時刻確認やアラーム停止くらいは「使っていない」とみなす */
    private const val NIGHT_TOLERANCE_MS = 5L * 60L * 1000L

    /** 朝ボーナスでノーカウントになる時間 */
    const val MORNING_BONUS_MS = 30L * 60L * 1000L

    data class DayUsage(
        /** 今日の画面ON時間（ボーナス分も含む） */
        val totalMs: Long,
        /** 上限にカウントされる時間 */
        val countedMs: Long,
        /** 夜使わなかったので今朝のボーナスを獲得したか（6:00 前は判定前なので false） */
        val bonusEarned: Boolean,
        /** ボーナスのうち使った時間 */
        val bonusUsedMs: Long,
        /** 今がボーナスの時間帯（6:00〜9:00）か */
        val inMorningWindow: Boolean,
    ) {
        val remainingMs: Long get() = DAILY_LIMIT_MS - countedMs
        val bonusLeftMs: Long get() = if (bonusEarned) MORNING_BONUS_MS - bonusUsedMs else 0L
        val bonusActive: Boolean get() = inMorningWindow && bonusLeftMs > 0
    }

    fun todayUsage(context: Context): DayUsage {
        val now = System.currentTimeMillis()
        val dayStart = startOfToday(now)
        val nightStart = atHour(dayStart, NIGHT_START_HOUR, dayOffset = -1)
        val nightEnd = atHour(dayStart, NIGHT_END_HOUR)
        val morningEnd = atHour(dayStart, MORNING_END_HOUR)

        val intervals = screenOnIntervals(context, nightStart, now)
        val total = intervals.sumOf { overlap(it.first, it.second, dayStart, now) }

        val bonusEarned = now >= nightEnd &&
            intervals.sumOf { overlap(it.first, it.second, nightStart, nightEnd) } <= NIGHT_TOLERANCE_MS
        val bonusUsed = if (bonusEarned) {
            intervals.sumOf { overlap(it.first, it.second, nightEnd, minOf(now, morningEnd)) }
                .coerceAtMost(MORNING_BONUS_MS)
        } else 0L

        return DayUsage(
            totalMs = total,
            countedMs = total - bonusUsed,
            bonusEarned = bonusEarned,
            bonusUsedMs = bonusUsed,
            inMorningWindow = now in nightEnd until morningEnd,
        )
    }

    /** [from]〜[now] の画面ON区間の一覧（範囲外にはみ出す区間も含む） */
    private fun screenOnIntervals(context: Context, from: Long, now: Long): List<Pair<Long, Long>> {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        // [from] の時点で画面がONだったかを知るため、さらに1日遡って読む
        val events = usm.queryEvents(from - 24L * 60L * 60L * 1000L, now)

        val result = mutableListOf<Pair<Long, Long>>()
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
                    interactiveSince?.let { result += it to event.timeStamp }
                    interactiveSince = null
                }
            }
        }

        val screenOn = context.getSystemService(PowerManager::class.java).isInteractive
        if (interactiveSince != null) {
            result += interactiveSince to now
        } else if (!sawAnyScreenEvent && screenOn) {
            // 丸1日以上画面がつきっぱなしでイベントが無い場合
            result += from to now
        }
        return result
    }

    /** 朝ボーナスの状態を表示用の文にする。表示することが無ければ null */
    fun bonusLabel(u: DayUsage): String? = when {
        u.bonusActive -> "朝ボーナス中: あと${formatDuration(u.bonusLeftMs, roundUp = true)}ノーカウント（${MORNING_END_HOUR}:00まで）"
        u.inMorningWindow && !u.bonusEarned -> "昨夜使ったので今朝のボーナスはありません"
        else -> null
    }

    private fun atHour(dayStart: Long, hour: Int, dayOffset: Int = 0): Long =
        Calendar.getInstance().apply {
            timeInMillis = dayStart
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
        }.timeInMillis

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
