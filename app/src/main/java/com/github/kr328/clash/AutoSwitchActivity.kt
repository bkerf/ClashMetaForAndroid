package com.github.kr328.clash

import android.os.Bundle
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.design.AutoSwitchDesign
import com.github.kr328.clash.design.AutoSwitchDesign.Request
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.model.AutoSwitchConfig
import com.github.kr328.clash.service.store.AutoSwitchStore
import com.github.kr328.clash.service.util.sendAutoSwitchUpdated
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

class AutoSwitchActivity : BaseActivity<AutoSwitchDesign>() {
    private lateinit var store: AutoSwitchStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.auto_switch_title)
        store = AutoSwitchStore(this)
    }

    override suspend fun main() {
        val design = AutoSwitchDesign(this)
        setContentDesign(design)

    var config = store.snapshot()
    var currentProxies: List<Proxy> = emptyList()
    var probeJob: Job? = null

        design.setEnabled(config.enabled)
        design.setPreferStable(config.preferStableOnly)
        design.setInterval(config.probeIntervalSeconds)
        design.setCooldown(config.switchCooldownSeconds)

        val groupNames = loadAvailableGroups(design)
        if (groupNames.isEmpty()) {
            design.showToast(R.string.auto_switch_no_groups, ToastDuration.Long)
            return
        }

        val targetGroup = when {
            groupNames.contains(config.targetGroup) -> config.targetGroup
            else -> groupNames.first()
        }

        if (targetGroup != config.targetGroup) {
            config = config.copy(targetGroup = targetGroup, candidates = emptySet())
            persist(config)
        }

        design.setGroupName(targetGroup)
        design.showLoadingCandidates()

        reloadCandidates(design, targetGroup, config).also { (proxies, updatedConfig) ->
            currentProxies = proxies
            config = updatedConfig
        }

        refreshRuntimeStatus(design)

        fun launchLatencyProbe(group: String) {
            probeJob?.cancel()
            probeJob = launch {
                if (currentProxies.isEmpty()) {
                    design.showLatencyFailure()
                    return@launch
                }

                design.showLatencyTesting()

                val success = try {
                    withClash { healthCheck(group) }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }

                reloadCandidates(design, group, config).also { (proxies, updatedConfig) ->
                    currentProxies = proxies
                    config = updatedConfig
                }
                refreshRuntimeStatus(design)

                if (success) {
                    design.showLatencySuccess(System.currentTimeMillis())
                } else {
                    design.showLatencyFailure()
                }
            }
        }

        if (currentProxies.isNotEmpty()) {
            launchLatencyProbe(targetGroup)
        }

        while (isActive) {
            select<Unit> {
                design.requests.onReceive { request ->
                    when (request) {
                        is Request.ToggleEnabled -> {
                            val newConfig = config.copy(enabled = request.enabled)
                            if (newConfig != config) {
                                config = newConfig
                                persist(config)
                                design.setEnabled(config.enabled)
                                refreshRuntimeStatus(design)
                            }
                        }
                        is Request.SelectGroup -> {
                            if (request.name != config.targetGroup) {
                                probeJob?.cancel()
                                config = config.copy(targetGroup = request.name, candidates = emptySet())
                                persist(config)
                                design.setGroupName(request.name)
                                design.showLoadingCandidates()
                                reloadCandidates(design, request.name, config).also { (proxies, updatedConfig) ->
                                    currentProxies = proxies
                                    config = updatedConfig
                                }
                                refreshRuntimeStatus(design)
                                if (currentProxies.isNotEmpty()) {
                                    launchLatencyProbe(request.name)
                                }
                            }
                        }
                        is Request.ToggleCandidate -> {
                            val mutable = config.candidates.toMutableSet()
                            if (request.checked) mutable.add(request.name) else mutable.remove(request.name)
                            val newConfig = config.copy(candidates = mutable)
                            if (newConfig != config) {
                                config = newConfig
                                persist(config)
                                refreshRuntimeStatus(design)
                            }
                        }
                        Request.SelectAllCandidates -> {
                            val all = currentProxies.mapTo(mutableSetOf()) { it.name }
                            val newConfig = config.copy(candidates = all)
                            if (newConfig != config) {
                                config = newConfig
                                persist(config)
                                design.showCandidates(currentProxies, all)
                                refreshRuntimeStatus(design)
                            }
                        }
                        Request.ClearAllCandidates -> {
                            if (config.candidates.isNotEmpty()) {
                                config = config.copy(candidates = emptySet())
                                persist(config)
                                design.showCandidates(currentProxies, emptySet())
                                refreshRuntimeStatus(design)
                            }
                        }
                        is Request.UpdateProbeInterval -> {
                            val sanitized = request.seconds.coerceAtLeast(15)
                            val newConfig = config.copy(probeIntervalSeconds = sanitized)
                            if (newConfig != config) {
                                config = newConfig
                                persist(config)
                            }
                            design.setInterval(config.probeIntervalSeconds)
                            refreshRuntimeStatus(design)
                        }
                        is Request.UpdateCooldown -> {
                            val sanitized = request.seconds.coerceAtLeast(5)
                            val newConfig = config.copy(switchCooldownSeconds = sanitized)
                            if (newConfig != config) {
                                config = newConfig
                                persist(config)
                            }
                            design.setCooldown(config.switchCooldownSeconds)
                            refreshRuntimeStatus(design)
                        }
                        is Request.UpdatePreferStable -> {
                            val newConfig = config.copy(preferStableOnly = request.enabled)
                            if (newConfig != config) {
                                config = newConfig
                                persist(config)
                                refreshRuntimeStatus(design)
                            }
                        }
                        Request.RetestLatency -> {
                            if (currentProxies.isEmpty()) {
                                launch {
                                    design.showToast(R.string.auto_switch_empty, ToastDuration.Short)
                                }
                            } else {
                                launchLatencyProbe(config.targetGroup)
                                refreshRuntimeStatus(design)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadAvailableGroups(design: AutoSwitchDesign): List<String> {
        return try {
            val groups = withClash { queryProxyGroupNames(true) }
            design.setGroups(groups)
            groups
        } catch (e: Exception) {
            design.showToast(R.string.auto_switch_load_groups_failed, ToastDuration.Long)
            emptyList()
        }
    }

    private suspend fun loadGroupCandidates(
        design: AutoSwitchDesign,
        groupName: String
    ): List<Proxy> {
        return try {
            val group = withClash { queryProxyGroup(groupName, ProxySort.Delay) }
            if (!group.type.group) {
                design.showToast(R.string.auto_switch_group_not_selector, ToastDuration.Long)
                emptyList()
            } else {
                val proxies = group.proxies.filterNot { it.type.group }
                proxies
            }
        } catch (e: Exception) {
            design.showToast(R.string.auto_switch_load_group_failed, ToastDuration.Long)
            emptyList()
        }
    }

    private suspend fun reloadCandidates(
        design: AutoSwitchDesign,
        groupName: String,
        currentConfig: AutoSwitchConfig
    ): Pair<List<Proxy>, AutoSwitchConfig> {
        val proxies = loadGroupCandidates(design, groupName)
        val names = proxies.map { it.name }.toSet()
        val filtered = currentConfig.candidates.filterTo(mutableSetOf()) { names.contains(it) }

        var updated = currentConfig
        if (filtered.size != currentConfig.candidates.size) {
            updated = currentConfig.copy(candidates = filtered)
            persist(updated)
        }

        design.showCandidates(proxies, filtered)

        return proxies to updated
    }

    private fun persist(config: AutoSwitchConfig) {
        store.update(config)
        applicationContext.sendAutoSwitchUpdated(Intents.AutoSwitchUpdateSource.CONFIG)
    }

    private suspend fun refreshRuntimeStatus(design: AutoSwitchDesign) {
        design.showRuntimeStatus(store.statusSnapshot())
    }
}
