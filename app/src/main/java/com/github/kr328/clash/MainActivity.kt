package com.github.kr328.clash

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.bridge.*
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.remote.IClashManager

class MainActivity : BaseActivity<MainDesign>() {
    private var userInitiatedStop = false

    override suspend fun main() {
        val design = MainDesign(this)

        setContentDesign(design)

        val autoStartPreference = uiStore.autoStartClash || StatusProvider.shouldStartClashOnBoot

        uiStore.autoStartClash = autoStartPreference
        StatusProvider.shouldStartClashOnBoot = autoStartPreference

        if (!clashRunning && autoStartPreference) {
            val started = design.startClash()

            if (!started) {
                uiStore.autoStartClash = false
                StatusProvider.shouldStartClashOnBoot = false
            }
        }

        design.fetch()

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ProfileLoaded,
                        Event.ProfileChanged -> design.fetch()
                        Event.ClashStart -> {
                            userInitiatedStop = false
                            uiStore.autoStartClash = true
                            StatusProvider.shouldStartClashOnBoot = true

                            design.fetch()
                        }
                        Event.ClashStop -> {
                            if (userInitiatedStop) {
                                userInitiatedStop = false
                            } else {
                                StatusProvider.shouldStartClashOnBoot = uiStore.autoStartClash
                            }

                            design.fetch()
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MainDesign.Request.ToggleStatus -> {
                            if (clashRunning) {
                                userInitiatedStop = true
                                uiStore.autoStartClash = false
                                StatusProvider.shouldStartClashOnBoot = false

                                stopClashService()
                            } else {
                                val started = design.startClash()

                                if (started) {
                                    uiStore.autoStartClash = true
                                    StatusProvider.shouldStartClashOnBoot = true
                                }
                            }
                        }
                        MainDesign.Request.OpenProxy ->
                            startActivity(ProxyActivity::class.intent)
                        MainDesign.Request.OpenProfiles ->
                            startActivity(ProfilesActivity::class.intent)
                        MainDesign.Request.OpenProviders ->
                            startActivity(ProvidersActivity::class.intent)
                        MainDesign.Request.OpenLogs -> {
                            if (LogcatService.running) {
                                startActivity(LogcatActivity::class.intent)
                            } else {
                                startActivity(LogsActivity::class.intent)
                            }
                        }
                        MainDesign.Request.OpenSettings ->
                            startActivity(SettingsActivity::class.intent)
                        MainDesign.Request.OpenHelp ->
                            startActivity(HelpActivity::class.intent)
                        MainDesign.Request.OpenAbout ->
                            design.showAbout(queryAppVersionName())
                    }
                }
                if (clashRunning) {
                    ticker.onReceive {
                        design.fetchTraffic()
                    }
                }
            }
        }
    }

    private suspend fun MainDesign.fetch() {
        setClashRunning(clashRunning)

        val state = withClash {
            queryTunnelState()
        }
        val providers = withClash {
            queryProviders()
        }
        val proxyDisplay = if (clashRunning) fetchPrimaryProxyDisplay(state.mode) else null

        setHeaderInfo(proxyDisplay?.title, proxyDisplay?.subtitle)
        setMode(state.mode)
        setHasProviders(providers.isNotEmpty())

        withProfile {
            setProfileName(queryActive()?.name)
        }
    }

    private suspend fun MainDesign.fetchTraffic() {
        withClash {
            setForwarded(queryTrafficTotal())
        }
    }

    private suspend fun MainDesign.startClash(): Boolean {
        val active = withProfile { queryActive() }

        if (active == null || !active.imported) {
            showToast(R.string.no_profile_selected, ToastDuration.Long) {
                setAction(R.string.profiles) {
                    startActivity(ProfilesActivity::class.intent)
                }
            }

            return false
        }

        return try {
            val vpnRequest = startClashService()

            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK) {
                    startClashService()
                    true
                } else {
                    false
                }
            } else {
                true
            }
        } catch (e: Exception) {
            design?.showToast(R.string.unable_to_start_vpn, ToastDuration.Long)
            false
        }
    }

    private suspend fun fetchPrimaryProxyDisplay(mode: TunnelState.Mode): ProxyDisplayInfo? {
        return try {
            withClash {
                queryPrimaryProxy(
                    preferredGroup = uiStore.proxyLastGroup.takeIf { it.isNotBlank() },
                    mode = mode,
                    excludeNotSelectable = uiStore.proxyExcludeNotSelectable
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun IClashManager.queryPrimaryProxy(
        preferredGroup: String?,
        mode: TunnelState.Mode,
        excludeNotSelectable: Boolean
    ): ProxyDisplayInfo? {
        val names = runCatching { queryProxyGroupNames(excludeNotSelectable) }.getOrElse { return null }
        if (names.isEmpty()) return null

        val prioritized = buildPriorityList(names, preferredGroup, mode)
        val visited = mutableSetOf<String>()

        for (name in prioritized) {
            if (!names.contains(name) || !visited.add(name)) continue

            val group = runCatching { queryProxyGroup(name, ProxySort.Default) }.getOrNull() ?: continue
            if (group.type != Proxy.Type.Selector) continue
            if (group.now.isBlank()) continue

            val selected = group.proxies.find { it.name == group.now }
            val title = selected?.title?.takeIf { it.isNotBlank() } ?: group.now
            val subtitle = selected?.subtitle?.takeIf { it.isNotBlank() } ?: selected?.type?.name

            return ProxyDisplayInfo(title, subtitle)
        }

        return null
    }

    private fun buildPriorityList(
        names: List<String>,
        preferredGroup: String?,
        mode: TunnelState.Mode
    ): List<String> {
        val priority = mutableListOf<String>()

        preferredGroup?.takeIf { names.contains(it) }?.let(priority::add)

        val modeKeywords = when (mode) {
            TunnelState.Mode.Global -> listOf("global")
            TunnelState.Mode.Direct -> listOf("direct")
            TunnelState.Mode.Rule -> listOf("proxy", "select", "节点", "節點", "选择", "選擇")
            TunnelState.Mode.Script -> emptyList()
        }

        for (keyword in modeKeywords) {
            names.firstOrNull { it.equals(keyword, ignoreCase = true) }?.let(priority::add)
            names.firstOrNull { it.contains(keyword, ignoreCase = true) }?.let(priority::add)
        }

        priority.addAll(names)

        return priority
    }

    private data class ProxyDisplayInfo(val title: String, val subtitle: String?)

    private suspend fun queryAppVersionName(): String {
        return withContext(Dispatchers.IO) {
            packageManager.getPackageInfo(packageName, 0).versionName + "\n" + Bridge.nativeCoreVersion().replace("_", "-")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher =
                registerForActivityResult(RequestPermission()
                ) { isGranted: Boolean ->
                }
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

val mainActivityAlias = "${MainActivity::class.java.name}Alias"