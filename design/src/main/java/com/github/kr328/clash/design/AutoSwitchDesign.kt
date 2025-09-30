package com.github.kr328.clash.design

import android.content.Context
import android.graphics.Rect
import android.text.format.DateFormat
import android.view.View
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.DesignAutoSwitchBinding
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.design.adapter.AutoSwitchCandidateAdapter
import com.github.kr328.clash.design.adapter.AutoSwitchCandidateUiModel
import com.github.kr328.clash.service.model.AutoSwitchStatusSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

class AutoSwitchDesign(context: Context) : Design<AutoSwitchDesign.Request>(context) {
    sealed class Request {
        data class ToggleEnabled(val enabled: Boolean) : Request()
        data class SelectGroup(val name: String) : Request()
        data class ToggleCandidate(val name: String, val checked: Boolean) : Request()
        object SelectAllCandidates : Request()
        object ClearAllCandidates : Request()
        data class UpdateProbeInterval(val seconds: Long) : Request()
        data class UpdateCooldown(val seconds: Long) : Request()
        data class UpdatePreferStable(val enabled: Boolean) : Request()
        object RetestLatency : Request()
    }

    private val binding = DesignAutoSwitchBinding
        .inflate(context.layoutInflater, context.root, false)

    private var suppressEnableCallback = false
    private var suppressIntervalCallback = false
    private var suppressCooldownCallback = false

    private val groupAdapter = ArrayAdapter<String>(
        context,
        android.R.layout.simple_dropdown_item_1line
    )

    private val candidateAdapter = AutoSwitchCandidateAdapter(context) { proxy, checked ->
        requests.trySend(Request.ToggleCandidate(proxy.name, checked))
    }

    private val candidateSpacing = context.resources.getDimensionPixelSize(R.dimen.auto_switch_candidate_spacing)
    private val candidateSpanCount = 2
    private var latencyRefreshAvailable = false
    private var isLatencyTesting = false

    override val root: View
        get() = binding.root

    init {
        binding.self = this
        binding.enabled = false
        binding.groupTitle = ""

        binding.activityBarLayout.applyFrom(context)

        binding.enableSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressEnableCallback) return@setOnCheckedChangeListener
            binding.enabled = isChecked
            requests.trySend(Request.ToggleEnabled(isChecked))
        }

        binding.preferStableCheckbox.setOnCheckedChangeListener { _, isChecked ->
            requests.trySend(Request.UpdatePreferStable(isChecked))
        }

        binding.groupInput.setAdapter(groupAdapter)
        binding.groupInput.setOnItemClickListener { _, _, position, _ ->
            val value = groupAdapter.getItem(position) ?: return@setOnItemClickListener
            requests.trySend(Request.SelectGroup(value))
        }
        binding.groupInput.setOnClickListener {
            binding.groupInput.showDropDown()
        }

        binding.selectAllButton.setOnClickListener {
            requests.trySend(Request.SelectAllCandidates)
        }
        binding.clearAllButton.setOnClickListener {
            requests.trySend(Request.ClearAllCandidates)
        }
        binding.latencyRefreshButton.setOnClickListener {
            requests.trySend(Request.RetestLatency)
        }

        binding.intervalInput.doAfterTextChanged {
            if (suppressIntervalCallback) return@doAfterTextChanged
            val value = it?.toString()?.toLongOrNull() ?: return@doAfterTextChanged
            requests.trySend(Request.UpdateProbeInterval(value))
        }
        binding.cooldownInput.doAfterTextChanged {
            if (suppressCooldownCallback) return@doAfterTextChanged
            val value = it?.toString()?.toLongOrNull() ?: return@doAfterTextChanged
            requests.trySend(Request.UpdateCooldown(value))
        }

        binding.candidateList.apply {
            adapter = candidateAdapter
            layoutManager = GridLayoutManager(context, candidateSpanCount)
            itemAnimator = null
            isNestedScrollingEnabled = false
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: Rect,
                    view: View,
                    parent: RecyclerView,
                    state: RecyclerView.State
                ) {
                    val position = parent.getChildAdapterPosition(view)
                    if (position == RecyclerView.NO_POSITION) {
                        return
                    }

                    val column = position % candidateSpanCount

                    outRect.left = candidateSpacing - column * candidateSpacing / candidateSpanCount
                    outRect.right = (column + 1) * candidateSpacing / candidateSpanCount
                    outRect.top = if (position >= candidateSpanCount) candidateSpacing else 0
                    outRect.bottom = candidateSpacing
                }
            })
        }
    }

    fun setEnabled(enabled: Boolean) {
        binding.enabled = enabled
        suppressEnableCallback = true
        binding.enableSwitch.isChecked = enabled
        suppressEnableCallback = false
        candidateAdapter.interactive = enabled
        updateCandidateActionsState()
    }

    fun setPreferStable(value: Boolean) {
        binding.preferStableCheckbox.isChecked = value
    }

    fun setGroupName(name: String) {
        binding.groupTitle = name
        binding.groupInput.setText(name, false)
    }

    fun setGroups(groups: List<String>) {
        groupAdapter.clear()
        groupAdapter.addAll(groups)
    }

    suspend fun setInterval(seconds: Long) {
        withContext(Dispatchers.Main) {
            suppressIntervalCallback = true
            binding.intervalInput.setText(seconds.toString())
            suppressIntervalCallback = false
        }
    }

    suspend fun setCooldown(seconds: Long) {
        withContext(Dispatchers.Main) {
            suppressCooldownCallback = true
            binding.cooldownInput.setText(seconds.toString())
            suppressCooldownCallback = false
        }
    }

    suspend fun showCandidates(proxies: List<Proxy>, selected: Set<String>) {
        withContext(Dispatchers.Main) {
            binding.loadingIndicator.visibility = View.GONE
            val uiModels = proxies.map { proxy ->
                AutoSwitchCandidateUiModel.from(
                    context = context,
                    proxy = proxy,
                    isSelected = selected.contains(proxy.name)
                )
            }

            candidateAdapter.submitList(uiModels)

            val hasCandidates = uiModels.isNotEmpty()
            latencyRefreshAvailable = hasCandidates && !isLatencyTesting
            binding.emptyCandidatesView.isVisible = !hasCandidates
            binding.candidateList.isVisible = hasCandidates

                updateCandidateActionsState(hasCandidatesOverride = hasCandidates)
        }
    }

    fun showLoadingCandidates() {
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.emptyCandidatesView.isVisible = false
        binding.candidateList.isVisible = false
        candidateAdapter.submitList(emptyList())
        latencyRefreshAvailable = false
        updateCandidateActionsState(hasCandidatesOverride = false)
        binding.latencyStatus.visibility = View.GONE
    }

    suspend fun showSaveSuccess() {
        showToast(R.string.auto_switch_saved, ToastDuration.Short)
    }

    suspend fun showLatencyTesting() {
        withContext(Dispatchers.Main) {
            binding.latencyStatus.visibility = View.VISIBLE
            binding.latencyStatus.text = context.getString(R.string.auto_switch_latency_testing)
            isLatencyTesting = true
            latencyRefreshAvailable = false
            updateCandidateActionsState()
        }
    }

    suspend fun showLatencySuccess(timestamp: Long) {
        withContext(Dispatchers.Main) {
            val formatted = DateFormat.getTimeFormat(context).format(Date(timestamp))
            binding.latencyStatus.visibility = View.VISIBLE
            binding.latencyStatus.text = context.getString(R.string.auto_switch_latency_success, formatted)
            isLatencyTesting = false
            latencyRefreshAvailable = candidateAdapter.currentList.isNotEmpty()
            updateCandidateActionsState()
        }
    }

    suspend fun showLatencyFailure() {
        withContext(Dispatchers.Main) {
            binding.latencyStatus.visibility = View.VISIBLE
            binding.latencyStatus.text = context.getString(R.string.auto_switch_latency_failed)
            isLatencyTesting = false
            latencyRefreshAvailable = candidateAdapter.currentList.isNotEmpty()
            updateCandidateActionsState()
        }
    }

    suspend fun showRuntimeStatus(status: AutoSwitchStatusSnapshot?) {
        withContext(Dispatchers.Main) {
            val view = binding.runtimeStatus
            if (status == null || status.message.isBlank()) {
                view.visibility = View.GONE
                return@withContext
            }

            val timestamp = status.timestampMillis
            val timeText = if (timestamp > 0) {
                DateFormat.getTimeFormat(context).format(Date(timestamp))
            } else {
                context.getString(R.string.auto_switch_runtime_status_time_unknown)
            }

            view.visibility = View.VISIBLE
            view.text = context.getString(
                R.string.auto_switch_runtime_status_format,
                timeText,
                status.message
            )
        }
    }

    private fun updateCandidateActionsState(
        hasCandidatesOverride: Boolean? = null,
    ) {
        val hasCandidates = hasCandidatesOverride ?: (candidateAdapter.itemCount > 0)
        val enabled = binding.enableSwitch.isChecked
        val latencyAllowed = latencyRefreshAvailable

        binding.selectAllButton.isEnabled = enabled && hasCandidates
        binding.clearAllButton.isEnabled = enabled && hasCandidates
        binding.latencyRefreshButton.isEnabled = enabled && hasCandidates && latencyAllowed
    }
}
