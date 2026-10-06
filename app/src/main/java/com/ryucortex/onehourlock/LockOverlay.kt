package com.ryucortex.onehourlock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 全画面を覆ってタッチ・戻るキーをすべて吸収するオーバーレイ。
 */
class LockOverlay(
    private val context: Context,
    private val onEmergency: () -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private var countdown: TextView? = null

    fun show(untilUnlockMs: Long) {
        val view = root ?: createView().also {
            windowManager.addView(it, layoutParams())
            root = it
        }
        countdown?.text = "解除まであと ${formatHms(untilUnlockMs)}"
        view.requestFocus()
    }

    fun hide() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        countdown = null
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun createView(): View {
        val dp = context.resources.displayMetrics.density

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((32 * dp).toInt(), 0, (32 * dp).toInt(), 0)
        }
        content.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_lock)
        }, LinearLayout.LayoutParams((96 * dp).toInt(), (96 * dp).toInt()))
        content.addView(TextView(context).apply {
            text = "今日はもうおしまい"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, (24 * dp).toInt(), 0, (8 * dp).toInt())
        })
        content.addView(TextView(context).apply {
            text = "スマホの使用時間が1時間を超えました。\n0:00 になると自動で解除されます。"
            textSize = 16f
            setTextColor(Color.parseColor("#CCFFFFFF"))
            gravity = Gravity.CENTER
        })
        val cd = TextView(context).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, (24 * dp).toInt(), 0, (48 * dp).toInt())
        }
        countdown = cd
        content.addView(cd)
        content.addView(Button(context).apply {
            text = "緊急通報（電話を開く）"
            setOnClickListener { onEmergency() }
        })

        return FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#FF101418"))
            addView(
                content,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
            // 背面へのタッチをすべて吸収する
            setOnTouchListener { _, _ -> true }
            // 戻るキー等を吸収する
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, _ ->
                keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU
            }
        }
    }

    private fun formatHms(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) / 1000L)
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }
}
