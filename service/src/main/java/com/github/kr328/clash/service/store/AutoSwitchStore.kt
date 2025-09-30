package com.github.kr328.clash.service.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.service.PreferenceProvider
import com.github.kr328.clash.service.model.AutoSwitchConfig
import com.github.kr328.clash.service.model.AutoSwitchStatusSnapshot

class AutoSwitchStore(context: Context) {
    private val store = Store(
        PreferenceProvider
            .createSharedPreferencesFromContext(context)
            .asStoreProvider()
    )

    var enabled: Boolean by store.boolean(
        key = KEY_ENABLED,
        defaultValue = false
    )

    var targetGroup: String by store.string(
        key = KEY_TARGET_GROUP,
        defaultValue = DEFAULT_GROUP
    )

    var candidateProxies: Set<String> by store.stringSet(
        key = KEY_CANDIDATE_PROXIES,
        defaultValue = emptySet()
    )

    var probeIntervalSeconds: Long by store.long(
        key = KEY_PROBE_INTERVAL_SECONDS,
        defaultValue = AutoSwitchConfig.DEFAULT_PROBE_INTERVAL_SECONDS
    )

    var switchCooldownSeconds: Long by store.long(
        key = KEY_SWITCH_COOLDOWN_SECONDS,
        defaultValue = AutoSwitchConfig.DEFAULT_SWITCH_COOLDOWN_SECONDS
    )

    var preferStableOnly: Boolean by store.boolean(
        key = KEY_PREFER_STABLE_ONLY,
        defaultValue = true
    )

    private var lastStatusMessage: String by store.string(
        key = KEY_LAST_STATUS_MESSAGE,
        defaultValue = ""
    )

    private var lastStatusTimestamp: Long by store.long(
        key = KEY_LAST_STATUS_TIMESTAMP,
        defaultValue = 0L
    )

    fun snapshot(): AutoSwitchConfig {
        return AutoSwitchConfig(
            enabled = enabled,
            targetGroup = targetGroup,
            candidates = candidateProxies,
            probeIntervalSeconds = probeIntervalSeconds.coerceAtLeast(15),
            switchCooldownSeconds = switchCooldownSeconds.coerceAtLeast(5),
            preferStableOnly = preferStableOnly
        )
    }

    fun update(config: AutoSwitchConfig) {
        enabled = config.enabled
        targetGroup = config.targetGroup
        candidateProxies = config.candidates
        probeIntervalSeconds = config.probeIntervalSeconds
        switchCooldownSeconds = config.switchCooldownSeconds
        preferStableOnly = config.preferStableOnly
    }

    fun statusSnapshot(): AutoSwitchStatusSnapshot? {
        val message = lastStatusMessage
        if (message.isBlank()) {
            return null
        }

        return AutoSwitchStatusSnapshot(
            timestampMillis = lastStatusTimestamp,
            message = message
        )
    }

    fun recordStatus(message: String, timestampMillis: Long = System.currentTimeMillis()) {
        lastStatusMessage = message
        lastStatusTimestamp = timestampMillis
    }

    companion object {
        private const val KEY_ENABLED = "auto_switch_enabled"
        private const val KEY_TARGET_GROUP = "auto_switch_target_group"
        private const val KEY_CANDIDATE_PROXIES = "auto_switch_candidate_proxies"
        private const val KEY_PROBE_INTERVAL_SECONDS = "auto_switch_probe_interval_seconds"
        private const val KEY_SWITCH_COOLDOWN_SECONDS = "auto_switch_switch_cooldown_seconds"
        private const val KEY_PREFER_STABLE_ONLY = "auto_switch_prefer_stable_only"
        private const val KEY_LAST_STATUS_MESSAGE = "auto_switch_last_status_message"
        private const val KEY_LAST_STATUS_TIMESTAMP = "auto_switch_last_status_timestamp"

        private const val DEFAULT_GROUP = "GLOBAL"
    }
}
