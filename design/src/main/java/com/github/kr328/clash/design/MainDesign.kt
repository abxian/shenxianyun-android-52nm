package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficTotal
import com.github.kr328.clash.design.databinding.DesignAboutBinding
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainDesign(context: Context) : Design<MainDesign.Request>(context) {
    enum class Request {
        ToggleStatus,
        ImportByCode,
        UpdateSubscription,
        RenewCode,
        SelectLine,
        SetRuleMode,
        SetGlobalMode,
        OpenProxy,
        OpenProfiles,
        OpenProviders,
        OpenLogs,
        OpenSettings,
        OpenHelp,
        OpenAbout,
    }

    private val binding = DesignMainBinding
        .inflate(context.layoutInflater, context.root, false)
    private var currentClashRunning = false

    override val root: View
        get() = binding.root

    suspend fun setProfileName(name: String?) {
        withContext(Dispatchers.Main) {
            binding.profileName = name
        }
    }

    suspend fun setActivationCode(code: String?) {
        withContext(Dispatchers.Main) {
            binding.activationCode = code
        }
    }

    suspend fun setActivationExpiresAt(expiresAt: String?) {
        withContext(Dispatchers.Main) {
            binding.activationExpiresAt = expiresAt
        }
    }

    suspend fun setClashRunning(running: Boolean) {
        withContext(Dispatchers.Main) {
            currentClashRunning = running
            binding.clashRunning = running
        }
    }

    suspend fun setForwarded(value: Long) {
        withContext(Dispatchers.Main) {
            binding.forwarded = value.trafficTotal()
        }
    }

    suspend fun setMode(mode: TunnelState.Mode) {
        withContext(Dispatchers.Main) {
            binding.mode = when (mode) {
                TunnelState.Mode.Direct -> context.getString(R.string.direct_mode)
                TunnelState.Mode.Global -> context.getString(R.string.global_mode)
                TunnelState.Mode.Rule -> context.getString(R.string.rule_mode)
                else -> context.getString(R.string.rule_mode)
            }
            binding.ruleMode = mode == TunnelState.Mode.Rule
            binding.globalMode = mode == TunnelState.Mode.Global
            updateModeButton(binding.ruleModeButton, mode == TunnelState.Mode.Rule)
            updateModeButton(binding.globalModeButton, mode == TunnelState.Mode.Global)
        }
    }

    private fun updateModeButton(button: MaterialButton, selected: Boolean) {
        val background = ContextCompat.getColor(
            context,
            if (selected) R.color.sxy_purple else R.color.sxy_surface_soft,
        )
        val foreground = ContextCompat.getColor(
            context,
            if (selected) android.R.color.white else R.color.sxy_text_soft,
        )
        val stroke = ContextCompat.getColor(
            context,
            if (selected) R.color.sxy_cyan else R.color.sxy_surface_stroke_soft,
        )

        button.backgroundTintList = android.content.res.ColorStateList.valueOf(background)
        button.setTextColor(foreground)
        button.strokeColor = android.content.res.ColorStateList.valueOf(stroke)
        button.strokeWidth = if (selected) 2.dp else 1.dp
        button.icon = if (selected) {
            ContextCompat.getDrawable(context, R.drawable.ic_outline_check_circle)
        } else {
            null
        }
        button.iconTint = android.content.res.ColorStateList.valueOf(foreground)
        button.alpha = if (selected) 1f else 0.72f
        button.scaleX = if (selected) 1f else 0.96f
        button.scaleY = if (selected) 1f else 0.96f
    }

    suspend fun setHasProviders(has: Boolean) {
        withContext(Dispatchers.Main) {
            binding.hasProviders = has
        }
    }

    suspend fun setLineStatus(text: String?) {
        withContext(Dispatchers.Main) {
            binding.lineStatus = text
        }
    }

    /** 线路条目：label 如「神仙云1」，ok=连通性，current=当前使用。 */
    data class LineItem(val label: String, val ok: Boolean, val current: Boolean)

    /**
     * 线路选择弹窗（圆角卡片样式）：每行 = 状态点 + 名称 + 可用性，点选返回下标，取消返回 null。
     * 不展示具体网址。
     */
    suspend fun showLineSelector(items: List<LineItem>): Int? =
        withContext(Dispatchers.Main) {
            val result = kotlinx.coroutines.CompletableDeferred<Int?>()
            val density = context.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            val container = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }

            val title = android.widget.TextView(context).apply {
                text = "选择服务线路"
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(16), dp(14), dp(16), dp(6))
            }
            container.addView(title)

            val hint = android.widget.TextView(context).apply {
                text = "自动选择可用线路，点选可手动切换"
                textSize = 12f
                alpha = 0.6f
                setPadding(dp(16), 0, dp(16), dp(10))
            }
            container.addView(hint)

            lateinit var dialog: AlertDialog

            items.forEachIndexed { index, item ->
                val row = android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    val tv = android.util.TypedValue()
                    context.theme.resolveAttribute(
                        android.R.attr.selectableItemBackground, tv, true,
                    )
                    setBackgroundResource(tv.resourceId)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        result.complete(index)
                        dialog.dismiss()
                    }
                }

                // 状态点：绿=可用 红=不通
                val dot = android.widget.TextView(context).apply {
                    text = "●"
                    textSize = 13f
                    setTextColor(if (item.ok) 0xFF2ECC8F.toInt() else 0xFFE05B5B.toInt())
                    setPadding(0, 0, dp(12), 0)
                }
                row.addView(dot)

                val name = android.widget.TextView(context).apply {
                    text = item.label
                    textSize = 15f
                    if (item.current) setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                    )
                }
                row.addView(name)

                val state = android.widget.TextView(context).apply {
                    text = buildString {
                        append(if (item.ok) "可用" else "不通")
                        if (item.current) append(" · 当前")
                    }
                    textSize = 12f
                    alpha = if (item.ok) 0.75f else 0.45f
                    if (item.current) setTextColor(0xFF2ECC8F.toInt())
                }
                row.addView(state)

                container.addView(row)
            }

            val scroll = android.widget.ScrollView(context).apply { addView(container) }

            dialog = AlertDialog.Builder(context)
                .setView(scroll)
                .setOnDismissListener { if (!result.isCompleted) result.complete(null) }
                .create()
            dialog.window?.setBackgroundDrawableResource(R.drawable.bg_line_dialog)
            dialog.window?.setWindowAnimations(android.R.style.Animation_Dialog)
            dialog.show()
            result.await()
        }

    suspend fun showAbout(versionName: String) {
        withContext(Dispatchers.Main) {
            val binding = DesignAboutBinding.inflate(context.layoutInflater).apply {
                this.versionName = versionName
            }

            AlertDialog.Builder(context)
                .setView(binding.root)
                .show()
        }
    }

    init {
        binding.self = this

        binding.colorClashStarted = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        binding.colorClashStopped = context.resolveThemedColor(R.attr.colorClashStopped)
    }

    fun request(request: Request) {
        if (request == Request.ToggleStatus && !currentClashRunning) {
            playStartEffect()
        }
        requests.trySend(request)
    }

    private fun playStartEffect() {
        binding.powerButton.animate().cancel()
        binding.powerHalo.animate().cancel()

        binding.powerButton.animate()
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(110L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                binding.powerButton.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(260L)
                    .setInterpolator(OvershootInterpolator(1.7f))
                    .start()
            }
            .start()

        binding.powerHalo.apply {
            rotation = 0f
            scaleX = 0.82f
            scaleY = 0.82f
            alpha = 0.95f
            animate()
                .rotation(180f)
                .scaleX(1.16f)
                .scaleY(1.16f)
                .alpha(0.18f)
                .setDuration(520L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    rotation = 0f
                    scaleX = 1f
                    scaleY = 1f
                    alpha = if (currentClashRunning) 0.95f else 0.5f
                }
                .start()
        }
    }

    private val Int.dp: Int
        get() = (this * context.resources.displayMetrics.density).toInt()
}
