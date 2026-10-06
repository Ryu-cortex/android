package com.ryucortex.onehourlock

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** 今日の使用状況と、動作に必要な権限のセットアップ画面 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 10_000L)
        }
    }

    private lateinit var usageText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var steps: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        column.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            setTextColor(Color.BLACK)
        })
        column.addView(TextView(this).apply {
            text = "1日のスマホ使用時間が1時間を超えると、0:00まで端末を操作できなくなります。"
            textSize = 14f
            setPadding(0, (8 * dp).toInt(), 0, (24 * dp).toInt())
        })
        usageText = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        column.addView(usageText)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 60
        }
        column.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (24 * dp).toInt(),
        ).apply { bottomMargin = (24 * dp).toInt() })
        column.addView(TextView(this).apply {
            text = "セットアップ"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, (8 * dp).toInt())
        })
        steps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(steps)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(column)
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onResume() {
        super.onResume()
        LimitService.start(this)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    @SuppressLint("SetTextI18n")
    private fun refresh() {
        val hasUsage = UsageTracker.hasUsageAccess(this)
        if (hasUsage) {
            val used = UsageTracker.todayScreenTimeMs(this)
            val remaining = UsageTracker.DAILY_LIMIT_MS - used
            usageText.text = "今日の使用: ${UsageTracker.formatDuration(used)}\n" +
                if (remaining > 0) "残り ${UsageTracker.formatDuration(remaining, roundUp = true)}" else "上限に達しました"
            progress.progress = (used / 60_000L).toInt().coerceAtMost(60)
        } else {
            usageText.text = "使用状況へのアクセスを許可してください"
            progress.progress = 0
        }

        val admin = ComponentName(this, AdminReceiver::class.java)
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val pm = getSystemService(PowerManager::class.java)

        steps.removeAllViews()
        addStep(
            "1. 使用状況へのアクセス（必須）",
            "今日の画面ON時間を計測するために使います。",
            hasUsage,
        ) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        addStep(
            "2. 他のアプリの上に重ねて表示（必須）",
            "上限を超えたときに画面全体をロックするために使います。",
            Settings.canDrawOverlays(this),
        ) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        addStep(
            "3. バッテリー最適化の対象外にする（推奨）",
            "バックグラウンドで監視が止められないようにします。",
            pm.isIgnoringBatteryOptimizations(packageName),
        ) {
            @SuppressLint("BatteryLife")
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"),
            )
            startActivity(intent)
        }
        addStep(
            "4. デバイス管理者を有効にする（推奨）",
            "勝手にアンインストールできなくなります。",
            dpm.isAdminActive(admin),
        ) {
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_description))
            )
        }
    }

    @SuppressLint("SetTextI18n")
    private fun addStep(title: String, description: String, done: Boolean, action: () -> Unit) {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        }
        row.addView(TextView(this).apply {
            text = (if (done) "✅ " else "⬜ ") + title
            textSize = 16f
            setTextColor(Color.BLACK)
        })
        row.addView(TextView(this).apply {
            text = description
            textSize = 13f
        })
        if (!done) {
            row.addView(Button(this).apply {
                text = "設定を開く"
                setOnClickListener { runCatching(action) }
            })
        }
        steps.addView(row)
    }
}
