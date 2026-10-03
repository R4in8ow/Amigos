package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.r4in8ow.amigos.R
import com.r4in8ow.amigos.databinding.LayoutAmigosHomeBinding
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.free.FreeServerManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.utils.AmigosAccount
import io.nekohasekai.sagernet.utils.AmigosPing
import io.nekohasekai.sagernet.utils.AmigosSecurity
import io.nekohasekai.sagernet.utils.AmigosVpnIp
import kotlinx.coroutines.launch

class AmigosHomeFragment : Fragment(), SagerConnection.Callback {

    private var _binding: LayoutAmigosHomeBinding? = null
    private val binding get() = _binding!!

    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_AMIGOS_HOME, true)

    private var vpnIp: String? = null
    private var vpnCountry: String? = null
    private var vpnCountryCode: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = LayoutAmigosHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AmigosSecurity.applyFlagSecure(requireActivity())

        binding.smartConnectSwitch.isChecked = DataStore.amigosSmartConnect
        binding.smartConnectSwitch.setOnCheckedChangeListener { _, checked ->
            DataStore.amigosSmartConnect = checked
            if (checked) smartConnect()
        }

        binding.speedTestCard.setOnClickListener {
            startActivity(Intent(requireContext(), SpeedTestActivity::class.java))
        }
        binding.telegramCard.setOnClickListener { openTelegram() }

        connection.connect(requireContext(), this)
        updateUi(DataStore.serviceState)
        if (DataStore.serviceState == BaseService.State.Connected) {
            fetchVpnIp()
            loadServerName()
        }
    }

    override fun onDestroyView() {
        try {
            connection.disconnect(requireContext())
        } catch (_: Exception) {
        }
        _binding = null
        super.onDestroyView()
    }

    override fun onServiceConnected(service: ISagerNetService) = Unit

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        updateUi(state)
        if (state == BaseService.State.Connected) {
            fetchVpnIp()
            loadServerName()
        } else {
            vpnIp = null
            vpnCountry = null
            vpnCountryCode = null
        }
    }

    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        _binding?.let {
            it.uploadText.text = AmigosAccount.formatBytes(stats.txRateProxy) + "/s"
            it.downloadText.text = AmigosAccount.formatBytes(stats.rxRateProxy) + "/s"
        }
    }

    private fun updateUi(state: BaseService.State) {
        val b = _binding ?: return
        val connected = state == BaseService.State.Connected
        val connecting = state == BaseService.State.Connecting
        b.stateText.text = when {
            connected -> getString(R.string.amigos_home_connected)
            connecting -> getString(R.string.amigos_home_connecting)
            else -> getString(R.string.amigos_home_disconnected)
        }
        val dot = when {
            connected -> R.drawable.amigos_dot_green
            connecting -> R.drawable.amigos_dot_amber
            else -> R.drawable.amigos_dot_gray
        }
        b.stateDot.setBackgroundResource(dot)
        if (!connected && !connecting) {
            b.uploadText.text = "0 B/s"
            b.downloadText.text = "0 B/s"
            b.vpnIpText.text = "—"
        }
        updateVpnIpText()
    }

    private fun updateVpnIpText() {
        val b = _binding ?: return
        val ip = vpnIp ?: return
        val flag = AmigosVpnIp.countryFlag(vpnCountryCode)
        b.vpnIpText.text = listOf(
            AmigosVpnIp.maskIp(ip),
            flag,
            vpnCountry ?: ""
        ).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun loadServerName() {
        runOnDefaultDispatcher {
            val name = try {
                SagerDatabase.proxyDao.getById(DataStore.selectedProxy)?.displayName()
            } catch (_: Exception) {
                null
            }
            onMainDispatcher {
                _binding?.serverText?.text = name ?: ""
            }
        }
    }

    private fun fetchVpnIp() {
        vpnIp = null
        vpnCountry = null
        vpnCountryCode = null
        lifecycleScope.launch {
            val result = try {
                AmigosVpnIp.fetch()
            } catch (e: Exception) {
                Logs.w("Amigos home VPN IP fetch failed: ${e.message}")
                null
            }
            vpnIp = result?.ip
            vpnCountry = result?.country
            vpnCountryCode = result?.countryCode
            updateVpnIpText()
        }
    }

    private fun openTelegram() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=R4in8ow")))
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/R4in8ow")))
        }
    }

    private fun smartConnect() {
        if (AmigosPing.isRunning()) return
        _binding?.smartConnectSwitch?.isEnabled = false
        runOnDefaultDispatcher {
            val group = activeGroup()
            if (group == null) {
                onMainDispatcher {
                    _binding?.let {
                        it.smartConnectSwitch.isEnabled = true
                        it.smartConnectSwitch.isChecked = false
                    }
                    DataStore.amigosSmartConnect = false
                }
                return@runOnDefaultDispatcher
            }
            var profiles = try {
                SagerDatabase.proxyDao.getByGroup(group.id)
            } catch (_: Exception) {
                emptyList()
            }
            if (profiles.isEmpty() && group.name == FreeServerManager.GROUP_NAME) {
                try {
                    FreeServerManager.refresh()
                    profiles = SagerDatabase.proxyDao.getByGroup(group.id)
                } catch (e: Exception) {
                    Logs.w("Amigos smart connect free refresh failed: ${e.message}")
                }
            }
            val total = profiles.size
            onMainDispatcher {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.amigos_home_smart_testing, 0, total),
                    Toast.LENGTH_SHORT
                ).show()
            }
            AmigosPing.testProfiles(
                profiles,
                onProfile = {},
                onProgress = { done, _ ->
                    _binding?.let {
                        if (done == total) {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.amigos_home_smart_testing, done, total),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                onDone = {
                    _binding?.smartConnectSwitch?.isEnabled = true
                    pickBestAndConnect(profiles)
                }
            )
        }
    }

    private suspend fun activeGroup() = try {
        val selected = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
        if (selected != null) {
            SagerDatabase.groupDao.getById(selected.groupId)
        } else {
            premiumGroup() ?: FreeServerManager.findOrCreateGroup()
        }
    } catch (_: Exception) {
        null
    }

    private suspend fun premiumGroup() =
        try {
            SagerDatabase.groupDao.subscriptions()
                .firstOrNull { it.subscription?.link?.startsWith(Key.AMIGOS_SUB_BASE) == true }
        } catch (_: Exception) {
            null
        }

    private fun pickBestAndConnect(profiles: List<ProxyEntity>) {
        val best = profiles
            .filter { it.status == 1 }
            .minWithOrNull(compareBy({ it.ping }, { AmigosPing.protocolPriority(it) }))
        if (best == null) {
            _binding?.let {
                Toast.makeText(
                    requireContext(),
                    R.string.amigos_home_smart_none,
                    Toast.LENGTH_LONG
                ).show()
                it.smartConnectSwitch.isChecked = false
            }
            DataStore.amigosSmartConnect = false
            return
        }
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = best.id
        runOnMainDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(best.id, true)
        }
        loadServerName()
        Toast.makeText(
            requireContext(),
            getString(R.string.amigos_home_smart_connected, best.displayName(), best.ping),
            Toast.LENGTH_SHORT
        ).show()
        (activity as? MainActivity)?.connectToSelected()
    }
}
