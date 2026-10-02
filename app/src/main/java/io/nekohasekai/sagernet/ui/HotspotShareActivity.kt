package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.r4in8ow.amigos.R
import com.r4in8ow.amigos.databinding.LayoutHotspotShareBinding
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Amigos hotspot sharing (no-root proxy method): when the VPN is connected,
 * the built-in mixed (HTTP/SOCKS) proxy listens on all interfaces, so devices
 * on this phone's Wi-Fi hotspot can route through the VPN by setting this
 * phone as their Wi-Fi proxy.
 */
class HotspotShareActivity : ThemedActivity(), SagerConnection.Callback {

    private lateinit var binding: LayoutHotspotShareBinding
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_HOTSPOT_SHARE, false)
    private var vpnConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutHotspotShareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.amigos_hotspot_title)
            setDisplayHomeAsUpEnabled(true)
        }

        binding.shareSwitch.isChecked = DataStore.allowAccess
        binding.shareSwitch.setOnCheckedChangeListener { _, checked ->
            DataStore.allowAccess = checked
            if (DataStore.serviceState.started) {
                SagerNet.reloadService()
            }
            updateProxyCard()
        }
        binding.refreshIpButton.setOnClickListener { refreshHotspotIp() }

        updateConnectionUi(DataStore.serviceState, null)
        updateProxyCard()
        connection.connect(this, this)
    }

    override fun onResume() {
        super.onResume()
        if (binding.shareSwitch.isChecked) refreshHotspotIp()
    }

    override fun onDestroy() {
        try {
            connection.disconnect(this)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    // --- SagerConnection.Callback ---

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        updateConnectionUi(state, profileName)
        updateProxyCard()
    }

    override fun onServiceConnected(service: ISagerNetService) = Unit

    // --- UI ---

    private fun updateConnectionUi(state: BaseService.State, profileName: String?) {
        vpnConnected = state.connected
        binding.statusText.text = getString(
            if (vpnConnected) R.string.amigos_speedtest_connected
            else R.string.amigos_speedtest_not_connected
        )
        binding.statusDot.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (vpnConnected) android.R.color.holo_green_dark else android.R.color.darker_gray,
        )
        val name = profileName ?: currentProfileName()
        binding.profileText.text = name ?: ""
        binding.profileText.visibility = if (name.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun updateProxyCard() {
        val sharing = binding.shareSwitch.isChecked
        binding.proxyCard.visibility = if (sharing) View.VISIBLE else View.GONE
        if (!sharing) return
        binding.proxyPortValue.text = DataStore.mixedPort.toString()
        refreshHotspotIp()
    }

    private fun refreshHotspotIp() {
        lifecycleScope.launch {
            val ip = withContext(Dispatchers.IO) { detectHotspotIp() }
            if (ip != null) {
                binding.proxyHostValue.text = ip
            } else {
                binding.proxyHostValue.text = getString(R.string.amigos_hotspot_default_ip)
            }
            when {
                ip == null -> {
                    binding.hotspotHint.text = getString(R.string.amigos_hotspot_no_ip)
                    binding.hotspotHint.visibility = View.VISIBLE
                }
                !vpnConnected -> {
                    binding.hotspotHint.text = getString(R.string.amigos_hotspot_need_vpn)
                    binding.hotspotHint.visibility = View.VISIBLE
                }
                else -> binding.hotspotHint.visibility = View.GONE
            }
        }
    }

    private fun currentProfileName(): String? {
        return try {
            val id = DataStore.selectedProxy
            if (id <= 0) null else ProfileManager.getProfile(id)?.displayName()
        } catch (e: Exception) {
            Logs.w("HotspotShare: profile name lookup failed: ${e.message}")
            null
        }
    }

    /**
     * Finds this phone's own IPv4 on the Wi-Fi hotspot / tethering interface,
     * which is the proxy host address hotspot clients must use.
     */
    private fun detectHotspotIp(): String? {
        return try {
            val addrs = mutableListOf<Pair<String, String>>()
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { addrs.add(ni.name to it) }
                    }
                }
            }
            if (addrs.isEmpty()) return null
            fun isHotspotIf(name: String): Boolean {
                val n = name.lowercase()
                return n.startsWith("ap") || n.startsWith("wlan") || n.contains("softap") ||
                        n.startsWith("swlan") || n.startsWith("rndis") || n.startsWith("usb")
            }
            val hotspotAddrs = addrs.filter { isHotspotIf(it.first) }.ifEmpty { addrs }
            hotspotAddrs.firstOrNull { (_, ip) ->
                ip.startsWith("192.168.43.") || ip.startsWith("192.168.137.") ||
                        ip.startsWith("192.168.42.")
            }?.second ?: hotspotAddrs.firstOrNull()?.second
        } catch (e: Exception) {
            Logs.w("HotspotShare: hotspot IP detect failed: ${e.message}")
            null
        }
    }
}
