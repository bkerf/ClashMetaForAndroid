package com.github.kr328.clash.service.model

data class AutoSwitchConfig(
    val enabled: Boolean,
    val targetGroup: String,
    val candidates: Set<String>,
    val probeIntervalSeconds: Long,
    val switchCooldownSeconds: Long,
    val preferStableOnly: Boolean
) {
    companion object {
        const val DEFAULT_PROBE_INTERVAL_SECONDS: Long = 60
        const val DEFAULT_SWITCH_COOLDOWN_SECONDS: Long = 30
    }
}
