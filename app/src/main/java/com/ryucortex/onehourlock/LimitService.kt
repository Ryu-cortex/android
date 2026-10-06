package com.ryucortex.onehourlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings

/**
 * 常駐して今日の使用時間を監視し、上限を超えたら全画面オーバーレイで端末を使えなくする。
 */
class LimitService : Service() {

    companion object {
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_WARNING = "warning"
        private const val NOTIFICATION_ID = 1
        private const val WARNING_NOTIFICATION_ID = 2
        private const val WARNING_BEFORE_MS = 5L * 60L * 1000L
        private const val EMERGENCY_GRACE_MS = 60L * 1000L
        private const val MAX_POLL_MS = 15_000L
        private const val LOCKED_POLL_MS = 1_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LimitService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = Runnable { tick() }
    private lateinit var overlay: LockOverlay

    /** ロック中の日（0:00 のミリ秒）。ロック中は日付が変わるまで使用時間を再計算しない */
    private var lockedDayStart: Long? = null
    private var emergencyUntil = 0L
    private var warnedDayStart: Long? = null

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> handler.removeCallbacks(tickRunnable)
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_DATE_CHANGED -> {
                    lockedDayStart = null
                    scheduleTick(0)
                }
                else -> scheduleTick(0)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val notification = buildStatusNotification("監視を開始しています…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        overlay = LockOverlay(this) { onEmergencyPressed() }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(systemReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(systemReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scheduleTick(0)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(systemReceiver)
        overlay.hide()
        super.onDestroy()
    }

    private fun scheduleTick(delayMs: Long) {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, delayMs)
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val dayStart = UsageTracker.startOfToday(now)

        if (lockedDayStart != null && lockedDayStart != dayStart) {
            // 日付が変わったのでロック解除の可能性あり → 再計算
            lockedDayStart = null
        }

        if (lockedDayStart == null) {
            if (!UsageTracker.hasUsageAccess(this)) {
                overlay.hide()
                updateStatus("「使用状況へのアクセス」を許可してください")
                scheduleTick(MAX_POLL_MS)
                return
            }
            val used = UsageTracker.todayScreenTimeMs(this)
            val remaining = UsageTracker.DAILY_LIMIT_MS - used
            if (remaining > 0) {
                overlay.hide()
                maybeWarn(dayStart, remaining)
                updateStatus(
                    "今日の使用: ${UsageTracker.formatDuration(used)} / " +
                        "残り ${UsageTracker.formatDuration(remaining, roundUp = true)}"
                )
                if (isScreenOn()) scheduleTick(remaining.coerceIn(1_000L, MAX_POLL_MS))
                return
            }
            lockedDayStart = dayStart
            updateStatus("今日の上限(1時間)に達しました。0:00に解除されます")
        }

        enforceLock(now)
        if (isScreenOn()) scheduleTick(LOCKED_POLL_MS)
    }

    private fun enforceLock(now: Long) {
        // 通話中・着信中・緊急発信の猶予中はロック画面を外す
        if (isInPhoneCall() || now < emergencyUntil) {
            overlay.hide()
            return
        }
        if (Settings.canDrawOverlays(this)) {
            overlay.show(UsageTracker.startOfTomorrow(now) - now)
        } else {
            // オーバーレイ権限が奪われていたら、デバイス管理者権限で画面ロックし続ける
            val dpm = getSystemService(DevicePolicyManager::class.java)
            val admin = ComponentName(this, AdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) dpm.lockNow()
        }
    }

    private fun onEmergencyPressed() {
        emergencyUntil = System.currentTimeMillis() + EMERGENCY_GRACE_MS
        overlay.hide()
        val dial = Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(dial) }
        scheduleTick(LOCKED_POLL_MS)
    }

    private fun isInPhoneCall(): Boolean {
        val mode = getSystemService(AudioManager::class.java).mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE
    }

    private fun isScreenOn(): Boolean = getSystemService(PowerManager::class.java).isInteractive

    private fun maybeWarn(dayStart: Long, remaining: Long) {
        if (remaining > WARNING_BEFORE_MS || warnedDayStart == dayStart) return
        warnedDayStart = dayStart
        val n = Notification.Builder(this, CHANNEL_WARNING)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle("まもなくロックされます")
            .setContentText("残り ${UsageTracker.formatDuration(remaining, roundUp = true)} で今日は使えなくなります")
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(WARNING_NOTIFICATION_ID, n)
    }

    private fun updateStatus(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildStatusNotification(text))
    }

    private fun buildStatusNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "使用時間の監視", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WARNING, "ロック前の警告", NotificationManager.IMPORTANCE_HIGH)
        )
    }
}
