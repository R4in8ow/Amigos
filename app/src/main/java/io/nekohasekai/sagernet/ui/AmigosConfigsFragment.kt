package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.ads.AdRequest
import com.r4in8ow.amigos.R
import com.r4in8ow.amigos.databinding.LayoutAmigosConfigsGroupBinding
import com.r4in8ow.amigos.databinding.LayoutAmigosConfigsRowBinding
import com.r4in8ow.amigos.databinding.LayoutAmigosConfigsBinding
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.free.FreeServerManager
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.utils.AmigosAds
import io.nekohasekai.sagernet.utils.AmigosPing
import io.nekohasekai.sagernet.utils.AmigosSecurity
import io.nekohasekai.sagernet.utils.AmigosVpnIp

class AmigosConfigsFragment : Fragment() {

    private var _binding: LayoutAmigosConfigsBinding? = null
    private val binding get() = _binding!!

    private val adapter = ConfigsAdapter(
        onSelect = { profile -> selectServer(profile) },
        onUpdateFree = { updateFree() },
        onUpdatePremium = { updatePremium() },
        onLogin = {
            startActivity(Intent(requireContext(), AmigosLoginActivity::class.java))
        }
    )

    private var loading = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = LayoutAmigosConfigsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AmigosSecurity.applyFlagSecure(requireActivity())

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.pingAllButton.setOnClickListener { pingAll() }

        setupBanner()
        load()
    }

    override fun onResume() {
        super.onResume()
        _binding?.adBanner?.resume()
        if (!loading && !AmigosPing.isRunning()) load()
    }

    override fun onPause() {
        _binding?.adBanner?.pause()
        super.onPause()
    }

    override fun onDestroyView() {
        _binding?.adBanner?.destroy()
        _binding = null
        super.onDestroyView()
    }

    private fun setupBanner() {
        if (!AmigosAds.isAdsEnabled()) return
        binding.adBanner.visibility = View.VISIBLE
        binding.adBanner.loadAd(AdRequest.Builder().build())
    }

    private fun load() {
        if (loading) return
        loading = true
        runOnDefaultDispatcher {
            val rows = ArrayList<ConfigsAdapter.Row>()
            val freeGroup = try {
                FreeServerManager.findOrCreateGroup()
            } catch (_: Exception) {
                null
            }
            val freeProfiles = try {
                freeGroup?.let { SagerDatabase.proxyDao.getByGroup(it.id) } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            rows.add(
                ConfigsAdapter.Row.Header(
                    ConfigsAdapter.GROUP_FREE,
                    getString(R.string.amigos_configs_free_servers) + " (${freeProfiles.size})",
                    showUpdate = true,
                    showLogin = false
                )
            )
            if (freeProfiles.isEmpty()) {
                rows.add(ConfigsAdapter.Row.Empty(getString(R.string.amigos_configs_empty)))
            }
            for (p in freeProfiles) rows.add(ConfigsAdapter.Row.Server(p))

            val premiumGroup = premiumGroup()
            val premiumProfiles = try {
                premiumGroup?.let { SagerDatabase.proxyDao.getByGroup(it.id) } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            val loggedIn = DataStore.amigosUsername.isNotBlank()
            rows.add(
                ConfigsAdapter.Row.Header(
                    ConfigsAdapter.GROUP_PREMIUM,
                    getString(R.string.amigos_configs_premium) + " (${premiumProfiles.size})",
                    showUpdate = loggedIn,
                    showLogin = !loggedIn
                )
            )
            if (loggedIn) {
                if (premiumProfiles.isEmpty()) {
                    rows.add(ConfigsAdapter.Row.Empty(getString(R.string.amigos_configs_empty)))
                }
                for (p in premiumProfiles) rows.add(ConfigsAdapter.Row.Server(p))
            } else {
                rows.add(ConfigsAdapter.Row.Empty(getString(R.string.amigos_configs_login_to_view)))
            }
            onMainDispatcher {
                loading = false
                _binding?.let { adapter.submit(rows) }
            }
        }
    }

    private suspend fun premiumGroup(): ProxyGroup? =
        try {
            SagerDatabase.groupDao.subscriptions()
                .firstOrNull { it.subscription?.link?.startsWith(Key.AMIGOS_SUB_BASE) == true }
        } catch (_: Exception) {
            null
        }

    private fun updateFree() {
        if (loading || AmigosPing.isRunning()) return
        loading = true
        runOnDefaultDispatcher {
            try {
                FreeServerManager.refresh()
            } catch (e: Exception) {
                Logs.w("Amigos configs free update failed: ${e.message}")
                try {
                    val cached = FreeServerManager.loadCached()
                    if (cached != null) {
                        val group = FreeServerManager.findOrCreateGroup()
                        if (SagerDatabase.proxyDao.getByGroup(group.id).isEmpty()) {
                            FreeServerManager.syncToGroup(cached.servers)
                        }
                    }
                } catch (_: Exception) {
                }
            }
            onMainDispatcher {
                loading = false
                updatePingStatus(null)
                load()
            }
        }
    }

    private fun updatePremium() {
        if (loading || AmigosPing.isRunning()) return
        loading = true
        runOnDefaultDispatcher {
            try {
                val group = premiumGroup()
                if (group != null) GroupUpdater.executeUpdate(group, true)
            } catch (e: Exception) {
                Logs.w("Amigos configs premium update failed: ${e.message}")
            }
            onMainDispatcher {
                loading = false
                load()
            }
        }
    }

    private fun pingAll() {
        if (AmigosPing.isRunning()) {
            AmigosPing.cancel()
            updatePingStatus(null)
            return
        }
        val profiles = adapter.currentServers()
        if (profiles.isEmpty()) {
            Toast.makeText(
                requireContext(),
                R.string.amigos_configs_no_group,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        binding.pingAllButton.text = getString(R.string.amigos_configs_cancel)
        AmigosPing.testProfiles(
            profiles,
            onProfile = { adapter.updateProfile(it) },
            onProgress = { done, total ->
                updatePingStatus(getString(R.string.amigos_configs_testing, done, total))
            },
            onDone = {
                _binding?.let {
                    it.pingAllButton.text = getString(R.string.amigos_configs_ping_all)
                    updatePingStatus(null)
                }
            }
        )
    }

    private fun updatePingStatus(text: String?) {
        _binding?.pingStatus?.let {
            if (text == null) {
                it.visibility = View.GONE
            } else {
                it.text = text
                it.visibility = View.VISIBLE
            }
        }
    }

    private fun selectServer(profile: ProxyEntity) {
        runOnDefaultDispatcher {
            val old = DataStore.selectedProxy
            DataStore.selectedProxy = profile.id
            runOnMainDispatcher {
                ProfileManager.postUpdate(old, true)
                ProfileManager.postUpdate(profile.id, true)
            }
            onMainDispatcher { adapter.refreshSelection() }
        }
    }

    class ConfigsAdapter(
        private val onSelect: (ProxyEntity) -> Unit,
        private val onUpdateFree: () -> Unit,
        private val onUpdatePremium: () -> Unit,
        private val onLogin: () -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            const val GROUP_FREE = 1
            const val GROUP_PREMIUM = 2
        }

        sealed class Row {
            data class Header(
                val group: Int,
                val title: String,
                val showUpdate: Boolean,
                val showLogin: Boolean,
            ) : Row()

            data class Server(val profile: ProxyEntity) : Row()
            data class Empty(val message: String) : Row()
        }

        private val rows = ArrayList<Row>()

        fun submit(newRows: List<Row>) {
            rows.clear()
            rows.addAll(newRows)
            notifyDataSetChanged()
        }

        fun currentServers(): List<ProxyEntity> =
            rows.filterIsInstance<Row.Server>().map { it.profile }

        fun updateProfile(profile: ProxyEntity) {
            val i = rows.indexOfFirst {
                it is Row.Server && it.profile.id == profile.id
            }
            if (i >= 0) {
                rows[i] = Row.Server(profile)
                notifyItemChanged(i)
            }
        }

        fun refreshSelection() = notifyDataSetChanged()

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Header -> 0
            is Row.Server -> 1
            is Row.Empty -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> HeaderHolder(
                    LayoutAmigosConfigsGroupBinding.inflate(inflater, parent, false),
                    onUpdateFree, onUpdatePremium, onLogin
                )
                1 -> ServerHolder(
                    LayoutAmigosConfigsRowBinding.inflate(inflater, parent, false),
                    onSelect
                )
                else -> EmptyHolder(
                    LayoutAmigosConfigsGroupBinding.inflate(inflater, parent, false)
                )
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).bind(row)
                is Row.Server -> (holder as ServerHolder).bind(row)
                is Row.Empty -> (holder as EmptyHolder).bind(row)
            }
        }

        override fun getItemCount(): Int = rows.size

        class HeaderHolder(
            private val binding: LayoutAmigosConfigsGroupBinding,
            private val onUpdateFree: () -> Unit,
            private val onUpdatePremium: () -> Unit,
            private val onLogin: () -> Unit,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(row: Row.Header) {
                binding.groupNameText.text = row.title
                binding.updateButton.visibility =
                    if (row.showUpdate) View.VISIBLE else View.GONE
                binding.updateButton.text = if (row.showLogin) {
                    binding.root.context.getString(R.string.amigos_configs_login)
                } else {
                    binding.root.context.getString(R.string.amigos_configs_update)
                }
                binding.updateButton.setOnClickListener {
                    if (row.showLogin) {
                        onLogin()
                    } else if (row.group == GROUP_FREE) {
                        onUpdateFree()
                    } else {
                        onUpdatePremium()
                    }
                }
            }
        }

        class ServerHolder(
            private val binding: LayoutAmigosConfigsRowBinding,
            private val onSelect: (ProxyEntity) -> Unit,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(row: Row.Server) {
                val profile = row.profile
                val context = binding.root.context
                binding.badgeText.text = AmigosPing.protocolBadge(profile)
                val selected = profile.id == DataStore.selectedProxy
                binding.nameText.text =
                    (if (selected) "● " else "") + profile.displayName()
                val address = try {
                    profile.requireBean().serverAddress ?: ""
                } catch (_: Exception) {
                    ""
                }
                binding.addressText.text = AmigosVpnIp.maskHost(address)
                when (profile.status) {
                    1 -> {
                        binding.pingText.text =
                            context.getString(R.string.amigos_configs_ping_toast, profile.ping)
                        binding.pingText.setBackgroundResource(R.drawable.amigos_ping_ok)
                    }
                    2, 3 -> {
                        binding.pingText.text = "timeout"
                        binding.pingText.setBackgroundResource(R.drawable.amigos_ping_fail)
                    }
                    else -> {
                        binding.pingText.text = "—"
                        binding.pingText.setBackgroundResource(R.drawable.amigos_ping_none)
                    }
                }
                binding.root.setOnClickListener { onSelect(profile) }
                binding.root.alpha = if (selected) 1.0f else 0.95f
            }
        }

        class EmptyHolder(
            private val binding: LayoutAmigosConfigsGroupBinding,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(row: Row.Empty) {
                binding.groupNameText.text = row.message
                binding.groupNameText.textSize = 13f
                binding.groupNameText.setTextColor(
                    ContextCompat.getColor(binding.root.context, android.R.color.darker_gray)
                )
                binding.updateButton.visibility = View.GONE
            }
        }
    }
}
