package com.github.kr328.clash.design.adapter

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.ViewGroup
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.res.use
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.AdapterAutoSwitchCandidateBinding
import com.github.kr328.clash.design.util.layoutInflater

class AutoSwitchCandidateAdapter(
    private val context: Context,
    private val onToggle: (Proxy, Boolean) -> Unit,
) : ListAdapter<AutoSwitchCandidateUiModel, AutoSwitchCandidateAdapter.Holder>(DiffCallback) {

    var interactive: Boolean = true
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = AdapterAutoSwitchCandidateBinding.inflate(context.layoutInflater, parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: AdapterAutoSwitchCandidateBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.isCheckable = true
        }

        fun bind(item: AutoSwitchCandidateUiModel) {
            binding.root.setOnClickListener {
                if (!interactive) return@setOnClickListener
                val nextState = !binding.root.isChecked
                binding.root.isChecked = nextState
                onToggle(item.proxy, nextState)
            }

            binding.root.isChecked = item.isSelected
            binding.root.isEnabled = interactive
            binding.root.alpha = if (interactive) 1f else 0.56f

            binding.nameView.text = item.displayName

            binding.latencyView.text = item.latency.text
            ViewCompat.setBackgroundTintList(
                binding.latencyIndicator,
                ColorStateList.valueOf(item.latency.indicatorColor)
            )
            binding.latencyView.setTextColor(item.latency.textColor)
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<AutoSwitchCandidateUiModel>() {
        override fun areItemsTheSame(
            oldItem: AutoSwitchCandidateUiModel,
            newItem: AutoSwitchCandidateUiModel,
        ): Boolean {
            return oldItem.proxy.name == newItem.proxy.name
        }

        override fun areContentsTheSame(
            oldItem: AutoSwitchCandidateUiModel,
            newItem: AutoSwitchCandidateUiModel,
        ): Boolean {
            return oldItem == newItem
        }
    }
}

data class AutoSwitchCandidateUiModel(
    val proxy: Proxy,
    val isSelected: Boolean,
    val displayName: String,
    val latency: LatencyInfo,
) {
    companion object {
        fun from(context: Context, proxy: Proxy, isSelected: Boolean): AutoSwitchCandidateUiModel {
            val displayName = proxy.title.ifEmpty { proxy.name }
            val latency = LatencyInfo.from(context, proxy.delay)

            return AutoSwitchCandidateUiModel(
                proxy = proxy,
                isSelected = isSelected,
                displayName = displayName,
                latency = latency,
            )
        }
    }
}

data class LatencyInfo(
    val text: String,
    @ColorInt val indicatorColor: Int,
    @ColorInt val textColor: Int,
) {
    companion object {
        private const val QUALITY_FAST_MAX = 200
        private const val QUALITY_MODERATE_MAX = 500

        fun from(context: Context, delay: Int): LatencyInfo {
            val defaultTextColor = context.themeColor(android.R.attr.textColorSecondary, Color.DKGRAY)
            return when {
                delay in 0..QUALITY_FAST_MAX -> buildLatencyInfo(
                    context = context,
                    latencyText = context.getString(R.string.auto_switch_latency_format_ms, delay),
                    qualityText = context.getString(R.string.auto_switch_latency_quality_fast),
                    indicatorAttr = com.google.android.material.R.attr.colorPrimary,
                    defaultTextColor = defaultTextColor
                )

                delay in (QUALITY_FAST_MAX + 1)..QUALITY_MODERATE_MAX -> buildLatencyInfo(
                    context = context,
                    latencyText = context.getString(R.string.auto_switch_latency_format_ms, delay),
                    qualityText = context.getString(R.string.auto_switch_latency_quality_moderate),
                    indicatorAttr = com.google.android.material.R.attr.colorSecondary,
                    defaultTextColor = defaultTextColor
                )

                delay > QUALITY_MODERATE_MAX && delay in 0..Int.MAX_VALUE -> buildLatencyInfo(
                    context = context,
                    latencyText = context.getString(R.string.auto_switch_latency_format_ms, delay),
                    qualityText = context.getString(R.string.auto_switch_latency_quality_slow),
                    indicatorAttr = com.google.android.material.R.attr.colorError,
                    defaultTextColor = defaultTextColor
                )

                delay == -1 -> buildLatencyInfo(
                    context = context,
                    latencyText = context.getString(R.string.auto_switch_latency_timeout),
                    qualityText = context.getString(R.string.auto_switch_latency_quality_timeout),
                    indicatorAttr = android.R.attr.textColorHint,
                    defaultTextColor = defaultTextColor,
                    emphasize = false
                )

                else -> buildLatencyInfo(
                    context = context,
                    latencyText = context.getString(R.string.auto_switch_latency_unavailable),
                    qualityText = context.getString(R.string.auto_switch_latency_quality_unknown),
                    indicatorAttr = android.R.attr.textColorHint,
                    defaultTextColor = defaultTextColor,
                    emphasize = false
                )
            }
        }

        private fun buildLatencyInfo(
            context: Context,
            latencyText: String,
            qualityText: String,
            @AttrRes indicatorAttr: Int,
            @ColorInt defaultTextColor: Int,
            emphasize: Boolean = true,
        ): LatencyInfo {
            val indicatorColor = context.themeColor(indicatorAttr, defaultTextColor)
            val textColor = if (emphasize) indicatorColor else defaultTextColor
            val formatted = context.getString(
                R.string.auto_switch_latency_quality_format,
                latencyText,
                qualityText
            )
            return LatencyInfo(
                text = formatted,
                indicatorColor = indicatorColor,
                textColor = textColor
            )
        }
    }
}

@ColorInt
private fun Context.themeColor(@AttrRes attr: Int, @ColorInt fallback: Int): Int {
    val typedArray = obtainStyledAttributes(intArrayOf(attr))
    return typedArray.use {
        it.getColor(0, fallback)
    }
}
