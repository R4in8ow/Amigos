package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object AmigosPing {

    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get() || DataStore.runningTest

    fun cancel() {
        DataStore.runningTest = false
    }

    fun testProfiles(
        profiles: List<ProxyEntity>,
        onProfile: (ProxyEntity) -> Unit,
        onProgress: (tested: Int, total: Int) -> Unit,
        onDone: () -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) return
        if (DataStore.runningTest) {
            running.set(false)
            return
        }
        DataStore.runningTest = true
        val tested = AtomicInteger(0)
        val total = profiles.size
        runOnDefaultDispatcher {
            val queue = ConcurrentLinkedQueue(profiles)
            val jobs = mutableListOf<Job>()
            repeat(DataStore.connectionTestConcurrent.coerceAtLeast(1)) {
                jobs += launch(Dispatchers.IO) {
                    val urlTest = UrlTest()
                    while (isActive && DataStore.runningTest) {
                        val profile = queue.poll() ?: break
                        profile.status = 0
                        onMainDispatcher { onProfile(profile) }
                        try {
                            val result = urlTest.doTest(profile)
                            profile.status = 1
                            profile.ping = result
                        } catch (e: PluginManager.PluginNotFoundException) {
                            profile.status = 2
                            profile.error = e.readableMessage
                        } catch (e: Exception) {
                            profile.status = 3
                            profile.error = e.readableMessage
                        }
                        try {
                            ProfileManager.updateProfile(profile)
                        } catch (_: Exception) {
                        }
                        val done = tested.incrementAndGet()
                        onMainDispatcher {
                            onProfile(profile)
                            onProgress(done, total)
                        }
                    }
                }
            }
            for (job in jobs) {
                try {
                    job.join()
                } catch (_: Exception) {
                }
            }
            DataStore.runningTest = false
            running.set(false)
            onMainDispatcher { onDone() }
        }
    }

    fun protocolPriority(profile: ProxyEntity): Int = when {
        profile.type == ProxyEntity.TYPE_VMESS && profile.vmessBean?.isVLESS == true -> 0
        profile.type == ProxyEntity.TYPE_TROJAN -> 1
        profile.type == ProxyEntity.TYPE_VMESS -> 2
        profile.type == ProxyEntity.TYPE_SS -> 3
        else -> 4
    }

    fun protocolBadge(profile: ProxyEntity): String = when {
        profile.type == ProxyEntity.TYPE_VMESS && profile.vmessBean?.isVLESS == true -> "VLESS"
        profile.type == ProxyEntity.TYPE_VMESS -> "VMESS"
        profile.type == ProxyEntity.TYPE_TROJAN -> "TROJAN"
        profile.type == ProxyEntity.TYPE_SS -> "SS"
        profile.type == ProxyEntity.TYPE_HYSTERIA -> "HY2"
        profile.type == ProxyEntity.TYPE_TUIC -> "TUIC"
        else -> "VPN"
    }
}
