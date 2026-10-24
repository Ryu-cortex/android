package com.ryucortex.onehourlock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 全画面を覆ってタッチ・戻るキーをすべて吸収するオーバーレイ。
 */
class LockOverlay(
    private val context: Context,
    private val onPhone: () -> Unit,
    private val onMaps: () -> Unit,
    private val spotifyAvailable: () -> Boolean,
    private val onSpotify: () -> Unit,
    private val tabs: () -> List<String>,
    private val onScan: () -> Unit,
    private val onOpenTab: (String) -> Unit,
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
            gravity = Gravity.CENTER
            setPadding((32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt())
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
        content.addView(TextView(context).apply {
            text = "ロック中も使えるのは電話・マップ・Spotify と、QR コードで開いたページだけです"
            textSize = 13f
            setTextColor(Color.parseColor("#99FFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (8 * dp).toInt())
        })
        fun addButton(label: String, onClick: () -> Unit) {
            content.addView(Button(context).apply {
                text = label
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        addButton("電話（緊急通報）", onPhone)
        addButton("マップ", onMaps)
        if (spotifyAvailable()) addButton("Spotify", onSpotify)
        addButton("QR コードを読み取る") { onScan() }

        val openedTabs = tabs()
        if (openedTabs.isNotEmpty()) {
            content.addView(TextView(context).apply {
                text = "今日 QR コードで開いたページ"
                textSize = 13f
                setTextColor(Color.parseColor("#99FFFFFF"))
                gravity = Gravity.CENTER
                setPadding(0, (16 * dp).toInt(), 0, (4 * dp).toInt())
            })
            for (url in openedTabs) {
                val uri = Uri.parse(url)
                addButton((uri.host.orEmpty() + uri.path.orEmpty()).take(40)) { onOpenTab(url) }
            }
        }

        return FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#FF101418"))
            // ページが増えても全部押せるようにスクロール可能にする
            addView(
                ScrollView(context).apply {
                    isFillViewport = true
                    addView(content, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ))
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
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
