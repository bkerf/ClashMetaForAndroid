package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import android.os.SystemClock
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.model.AutoSwitchConfig
import com.github.kr328.clash.service.store.AutoSwitchStore
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.util.sendAutoSwitchUpdated
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

class AutoSwitchModule(service: Service) : Module<Unit>(service) {
    private val serviceStore = ServiceStore(service)
    private val store = AutoSwitchStore(service)
    private val log = Log.tagged("AutoSwitch")

    private data class EvaluationResult(val lastSwitchTime: Long, val retryDelayMillis: Long?)

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun run() {
        var lastSwitchTime = 0L
        var nextDelayOverride: Long? = null

        val updates = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_AUTO_SWITCH_UPDATED)
            addAction(Intents.ACTION_PROFILE_LOADED)
            addAction(Intents.ACTION_CLASH_STOPPED)
        }

        info("AutoSwitch module started")

        while (true) {
            val config = store.snapshot()

            debug(
                "Loop enabled=${config.enabled}, group=${config.targetGroup}, candidates=${config.candidates.joinToString()}, " +
                    "probe=${config.probeIntervalSeconds}s, cooldown=${config.switchCooldownSeconds}s, preferStable=${config.preferStableOnly}"
            )

            if (!config.enabled) {
                recordStatus(service.getString(R.string.auto_switch_status_runtime_disabled))
                nextDelayOverride = null
                val intent = nextRelevantIntent(updates) ?: return
                if (intent.action == Intents.ACTION_CLASH_STOPPED) {
                    return
                }
                continue
            }

            if (config.targetGroup.isBlank()) {
                recordStatus(service.getString(R.string.auto_switch_status_runtime_waiting_group))
                nextDelayOverride = null
                val intent = nextRelevantIntent(updates) ?: return
                if (intent.action == Intents.ACTION_CLASH_STOPPED) {
                    return
                }
                continue
            }

            if (config.candidates.isEmpty()) {
                recordStatus(
                    service.getString(
                        R.string.auto_switch_status_runtime_waiting_candidates,
                        config.targetGroup
                    )
                )
                nextDelayOverride = null
                val intent = nextRelevantIntent(updates) ?: return
                if (intent.action == Intents.ACTION_CLASH_STOPPED) {
                    return
                }
                continue
            }

            try {
                val result = evaluateAndMaybeSwitch(config, lastSwitchTime)
                if (result.lastSwitchTime != lastSwitchTime) {
                    lastSwitchTime = result.lastSwitchTime
                }
                nextDelayOverride = result.retryDelayMillis
            } catch (e: Exception) {
                warn("Evaluation failed: ${e.message}", e)
                recordStatus(
                    service.getString(
                        R.string.auto_switch_status_runtime_error,
                        e.message ?: e::class.java.simpleName
                    )
                )
                nextDelayOverride = FAST_RETRY_MILLIS
            }

            val defaultWait = TimeUnit.SECONDS.toMillis(config.probeIntervalSeconds)
                .coerceIn(MIN_INTERVAL_MILLIS, MAX_INTERVAL_MILLIS)
            val waitMillis = nextDelayOverride?.coerceIn(FAST_RETRY_MILLIS, MAX_INTERVAL_MILLIS)
                ?: defaultWait
            nextDelayOverride = null

            val intent = awaitNextTrigger(updates, waitMillis)

            if (intent != null && intent.action == Intents.ACTION_CLASH_STOPPED) {
                return
            }
        }
    }

    private suspend fun evaluateAndMaybeSwitch(
        config: AutoSwitchConfig,
        lastSwitchTime: Long
    ): EvaluationResult {
        val candidateNames = config.candidates
        val cooldownMillis = TimeUnit.SECONDS.toMillis(config.switchCooldownSeconds)
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        var retryDelayMillis: Long? = null

        debug("Evaluate start group=${config.targetGroup}, candidates=${candidateNames.joinToString()}")

        withTimeoutOrNull(HEALTHCHECK_TIMEOUT_MILLIS) {
            Clash.healthCheck(config.targetGroup).await()
        }

        val group = Clash.queryGroup(config.targetGroup, ProxySort.Delay)
        val candidates = group.proxies.filter { candidateNames.contains(it.name) }

        if (candidates.isEmpty()) {
            warn("No candidates in group=${config.targetGroup}, now=${group.now}, requested=${candidateNames.joinToString()}")
            recordStatus(
                service.getString(
                    R.string.auto_switch_status_runtime_missing_candidates,
                    config.targetGroup,
                    group.proxies.joinToString { it.name }
                ),
                nowWall
            )
            retryDelayMillis = FAST_RETRY_MILLIS
            return EvaluationResult(lastSwitchTime, retryDelayMillis)
        }

        val current = candidates.find { it.name == group.now }
        val fullCurrent = group.proxies.find { it.name == group.now }
        val currentDisplay = fullCurrent?.let { displayName(it) }
            ?: group.now.takeIf { it.isNotBlank() }
            ?: service.getString(R.string.auto_switch_status_runtime_unknown_proxy)
        val isCurrentAllowed = current != null
        val stableRange = 0..Short.MAX_VALUE
        val stableCandidates = candidates.filter { it.delay in stableRange }
        val preferredCandidates = when {
            config.preferStableOnly && stableCandidates.isNotEmpty() -> stableCandidates
            else -> candidates
        }

        val desired = preferredCandidates.minByOrNull { proxyDelayScore(it.delay) } ?: run {
            warn("No preferred candidate for group=${config.targetGroup}, preferStable=${config.preferStableOnly}")
            return EvaluationResult(lastSwitchTime, retryDelayMillis)
        }

        val desiredDisplay = displayName(desired)
        val desiredDelayText = formatDelay(desired.delay)

        debug(
            "Evaluation group=${config.targetGroup}, now=${group.now}, currentDelay=${current?.delay ?: "-"}, " +
                "desired=${desired.name}:${desired.delay}, cooldown=${TimeUnit.MILLISECONDS.toSeconds(cooldownMillis)}s, " +
                "preferStable=${config.preferStableOnly}, stableCandidates=${stableCandidates.size}, " +
                "allCandidates=${candidates.joinToString { "${it.name}:${it.delay}" }}"
        )

        if (desired.name == group.now) {
            debug("Skip: already at desired=${desired.name}")
            recordStatus(
                service.getString(
                    R.string.auto_switch_status_runtime_already_optimal,
                    config.targetGroup,
                    desiredDisplay
                ),
                nowWall
            )
            return EvaluationResult(lastSwitchTime, retryDelayMillis)
        }

        val cooldownActive = nowElapsed - lastSwitchTime < cooldownMillis
        val remainingSeconds = if (cooldownActive) {
            val remaining = cooldownMillis - (nowElapsed - lastSwitchTime)
            TimeUnit.MILLISECONDS.toSeconds(remaining.coerceAtLeast(0))
        } else 0L

        val shouldSwitch = when {
            current == null -> true
            current.delay !in stableRange -> true
            desired.delay !in stableRange && config.preferStableOnly -> false
            desired.delay !in stableRange -> current.delay !in stableRange
            current.delay !in stableRange -> true
            desired.delay + MIN_IMPROVEMENT_MILLIS < current.delay -> true
            else -> false
        }

        if (cooldownActive && isCurrentAllowed) {
            debug("Skip: cooldown active for group=${config.targetGroup}, keep=${group.now}")
            recordStatus(
                service.getString(
                    R.string.auto_switch_status_runtime_skip_cooldown,
                    config.targetGroup,
                    currentDisplay,
                    remainingSeconds.toInt().coerceAtLeast(1)
                ),
                nowWall
            )
            return EvaluationResult(lastSwitchTime, retryDelayMillis)
        }

        if (!shouldSwitch && isCurrentAllowed) {
            debug("Skip: no improvement for group=${config.targetGroup}, current=${group.now}, desired=${desired.name}")
            recordStatus(
                service.getString(
                    R.string.auto_switch_status_runtime_skip_no_improvement,
                    config.targetGroup,
                    currentDisplay
                ),
                nowWall
            )
            return EvaluationResult(lastSwitchTime, retryDelayMillis)
        }

        if (!isCurrentAllowed) {
            warn("Current=${group.now} not in candidates for group=${config.targetGroup}, switching to ${desired.name}")
        }

        val applied = Clash.patchSelector(config.targetGroup, desired.name)
        if (applied) {
            serviceStore.activeProfile?.let { uuid ->
                try {
                    SelectionDao().setSelected(Selection(uuid, config.targetGroup, desired.name))
                } catch (t: Throwable) {
                    warn("Failed to persist selection for group=${config.targetGroup}: ${t.message}", t)
                }
            }

            val (confirmed, latestGroup) = confirmSwitch(config.targetGroup, desired.name, group)
            val timestamp = System.currentTimeMillis()
            if (confirmed) {
                val message = if (!isCurrentAllowed) {
                    service.getString(
                        R.string.auto_switch_status_runtime_switch_forced,
                        config.targetGroup,
                        desiredDisplay,
                        desiredDelayText,
                        currentDisplay
                    )
                } else {
                    service.getString(
                        R.string.auto_switch_status_runtime_switch_applied,
                        config.targetGroup,
                        desiredDisplay,
                        desiredDelayText
                    )
                }
                recordStatus(message, timestamp)
                info("Switched ${config.targetGroup} -> ${desired.name} (delay=${desired.delay})")
                return EvaluationResult(nowElapsed, null)
            }

            val actualNow = latestGroup.now.takeIf { it.isNotBlank() }
            val actualDisplay = actualNow?.let { nowName ->
                latestGroup.proxies.find { it.name == nowName }?.let(::displayName) ?: nowName
            } ?: service.getString(R.string.auto_switch_status_runtime_unknown_proxy)

            warn("Switch confirmation failed for group=${config.targetGroup}, desired=${desired.name}, actual=${latestGroup.now}")
            recordStatus(
                service.getString(
                    R.string.auto_switch_status_runtime_confirm_failed,
                    config.targetGroup,
                    desiredDisplay,
                    actualDisplay
                ),
                timestamp
            )
            retryDelayMillis = FAST_RETRY_MILLIS
            return EvaluationResult(lastSwitchTime, retryDelayMillis)
        }

        warn("Failed to apply selector group=${config.targetGroup}, desired=${desired.name}")
        recordStatus(
            service.getString(
                R.string.auto_switch_status_runtime_apply_failed,
                config.targetGroup,
                desiredDisplay
            ),
            nowWall
        )
        retryDelayMillis = FAST_RETRY_MILLIS
        return EvaluationResult(lastSwitchTime, retryDelayMillis)
    }

    private fun proxyDelayScore(delay: Int): Int {
        return if (delay in 0..Short.MAX_VALUE) delay else Int.MAX_VALUE
    }

    private fun displayName(proxy: Proxy): String {
        return proxy.title.takeIf { it.isNotBlank() } ?: proxy.name
    }

    private fun formatDelay(delay: Int): String {
        return when {
            delay in 0..Short.MAX_VALUE -> service.getString(R.string.auto_switch_status_runtime_delay_ms, delay)
            delay == -1 -> service.getString(R.string.auto_switch_status_runtime_delay_timeout)
            else -> service.getString(R.string.auto_switch_status_runtime_delay_unknown)
        }
    }

    private suspend fun confirmSwitch(
        groupName: String,
        desiredName: String,
        initialGroup: ProxyGroup
    ): Pair<Boolean, ProxyGroup> {
        var latestGroup = initialGroup

        repeat(SWITCH_CONFIRM_MAX_ATTEMPTS) {
            delay(SWITCH_CONFIRM_DELAY_MILLIS)
            latestGroup = Clash.queryGroup(groupName, ProxySort.Delay)
            if (latestGroup.now == desiredName) {
                return true to latestGroup
            }
        }

        return (latestGroup.now == desiredName) to latestGroup
    }

    private fun debug(message: String, throwable: Throwable? = null) {
        log.d(message, throwable)
        Log.d("[AutoSwitch] $message", throwable)
    }

    private fun info(message: String) {
        log.i(message)
        Log.i("[AutoSwitch] $message")
    }

    private fun warn(message: String, throwable: Throwable? = null) {
        log.w(message, throwable)
        Log.w("[AutoSwitch] $message", throwable)
    }

    private fun recordStatus(message: String, timestampMillis: Long = System.currentTimeMillis()) {
        store.recordStatus(message, timestampMillis)
        service.sendAutoSwitchUpdated(Intents.AutoSwitchUpdateSource.STATUS)
    }

    private suspend fun awaitNextTrigger(
        updates: ReceiveChannel<Intent>,
        waitMillis: Long
    ): Intent? {
        if (waitMillis <= 0L) {
            return nextRelevantIntent(updates)
        }

        var remaining = waitMillis
        val deadline = SystemClock.elapsedRealtime() + waitMillis

        while (true) {
            val intent = withTimeoutOrNull(remaining) {
                updates.receiveOrNullCompat()
            } ?: return null

            if (!intent.isStatusUpdateOnly()) {
                return intent
            }

            remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            if (remaining == 0L) {
                return null
            }
        }
    }

    private suspend fun nextRelevantIntent(updates: ReceiveChannel<Intent>): Intent? {
        while (true) {
            val intent = updates.receiveOrNullCompat() ?: return null
            if (!intent.isStatusUpdateOnly()) {
                return intent
            }
        }
    }

    private suspend fun ReceiveChannel<Intent>.receiveOrNullCompat(): Intent? {
        return try {
            receive()
        } catch (_: ClosedReceiveChannelException) {
            null
        }
    }

    private fun Intent.isStatusUpdateOnly(): Boolean {
        if (action != Intents.ACTION_AUTO_SWITCH_UPDATED) {
            return false
        }

        val source = getStringExtra(Intents.EXTRA_AUTO_SWITCH_UPDATE_SOURCE)
        return source == Intents.AutoSwitchUpdateSource.STATUS
    }

    companion object {
        private const val MIN_INTERVAL_MILLIS = 5_000L
        private const val MAX_INTERVAL_MILLIS = 300_000L
        private const val HEALTHCHECK_TIMEOUT_MILLIS = 15_000L
        private const val MIN_IMPROVEMENT_MILLIS = 50
        private const val SWITCH_CONFIRM_MAX_ATTEMPTS = 3
        private const val SWITCH_CONFIRM_DELAY_MILLIS = 500L
        private const val FAST_RETRY_MILLIS = 1_000L
    }
}
