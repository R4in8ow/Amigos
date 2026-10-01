package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.r4in8ow.amigos.R
import com.r4in8ow.amigos.databinding.LayoutFreeServerHeaderBinding
import com.r4in8ow.amigos.databinding.LayoutFreeServerItemBinding
import com.r4in8ow.amigos.databinding.LayoutFreeServersBinding
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.free.FreeServerManager
import io.nekohasekai.sagernet.free.FreeServerManager.FreeServerEntry
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.tryToShow
import io.nekohasekai.sagernet.utils.AmigosAds

class FreeServersActivity : ThemedActivity() {

    private lateinit var binding: LayoutFreeServersBinding
    private lateinit var adapter: FreeServerAdapter
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutFreeServersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.free_servers)
            setDisplayHomeAsUpEnabled(true)
        }

        adapter = FreeServerAdapter { entry, profileId -> selectServer(profileId) }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.refreshLayout.setColorSchemeColors(getColorAttr(R.attr.colorAccent))
        binding.refreshLayout.setOnRefreshListener { load(showProgress = false) }
        binding.retryButton.setOnClickListener { load() }

        if (!DataStore.freeServersDisclaimerShown) {
            showDisclaimer()
        } else {
            load()
        }
        setupBanner()
    }

    private fun setupBanner() {
        if (!AmigosAds.isAdsEnabled()) return
        val adView = binding.adBanner
        val metrics = resources.displayMetrics
        val adWidth = (metrics.widthPixels / metrics.density).toInt()
        adView.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, adWidth))
        adView.adUnitId = AmigosAds.bannerAdUnitId
        adView.visibility = View.VISIBLE
        adView.loadAd(AdRequest.Builder().build())
    }

    override fun onResume() {
        super.onResume()
        binding.adBanner.resume()
    }

    override fun onPause() {
        binding.adBanner.pause()
        super.onPause()
    }

    override fun onDestroy() {
        binding.adBanner.destroy()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.free_servers_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_refresh -> {
                if (!loading) {
                    binding.refreshLayout.isRefreshing = true
                    load(showProgress = false)
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showDisclaimer() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.free_servers_disclaimer_title)
            .setMessage(R.string.free_servers_disclaimer_message)
            .setCancelable(false)
            .setPositiveButton(R.string.free_servers_disclaimer_ack) { _, _ ->
                DataStore.freeServersDisclaimerShown = true
                load()
            }
            .create()
            .tryToShow()
    }

    private fun load(showProgress: Boolean = true) {
        if (loading) return
        loading = true
        binding.progress.visibility = if (showProgress) View.VISIBLE else View.GONE
        binding.emptyView.visibility = View.GONE
        updateLabel()
        runOnDefaultDispatcher {
            var result: FreeServerManager.FetchResult? = null
            var degraded = false
            try {
                result = FreeServerManager.refresh()
            } catch (e: Exception) {
                Logs.w("FreeServers: refresh failed: ${e.message}")
                degraded = true
                result = FreeServerManager.loadCached()
                if (result != null) {
                    val group = FreeServerManager.findOrCreateGroup()
                    if (SagerDatabase.proxyDao.getByGroup(group.id).isEmpty()) {
                        try {
                            FreeServerManager.syncToGroup(result.servers)
                        } catch (e2: Exception) {
                            Logs.w("FreeServers: cache sync failed: ${e2.message}")
                        }
                    }
                }
            }
            if (result != null) {
                DataStore.amigosFreeMode = true
                try {
                    SubscriptionUpdater.reconfigureUpdater()
                } catch (e: Exception) {
                    Logs.w("FreeServers: reconfigure updater failed: ${e.message}")
                }
            }
            val rows = result?.let { buildRows(it) } ?: emptyList()
            onMainDispatcher {
                loading = false
                binding.progress.visibility = View.GONE
                binding.refreshLayout.isRefreshing = false
                updateLabel()
                if (result == null) {
                    binding.emptyText.text = getString(R.string.free_servers_load_failed)
                    binding.emptyView.visibility = View.VISIBLE
                } else {
                    if (degraded) {
                        Toast.makeText(
                            this@FreeServersActivity,
                            R.string.free_servers_refresh_failed,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    if (rows.isEmpty()) {
                        binding.emptyText.text = getString(R.string.free_servers_empty)
                        binding.emptyView.visibility = View.VISIBLE
                    }
                    adapter.submit(rows)
                }
            }
        }
    }

    private suspend fun buildRows(result: FreeServerManager.FetchResult): List<FreeServerAdapter.Row> {
        val group = FreeServerManager.findOrCreateGroup()
        val nameToId = SagerDatabase.proxyDao.getByGroup(group.id)
            .associateBy({ it.displayName() }, { it.id })
        val selectedId = DataStore.selectedProxy
        val byRegion = LinkedHashMap<String, MutableList<FreeServerEntry>>()
        for (entry in result.servers) {
            byRegion.getOrPut(entry.region) { ArrayList() }.add(entry)
        }
        val rows = ArrayList<FreeServerAdapter.Row>()
        for ((region, entries) in byRegion) {
            rows.add(FreeServerAdapter.Row.Header(region, entries.size))
            for (entry in entries) {
                val profileId = nameToId[entry.name] ?: 0L
                rows.add(
                    FreeServerAdapter.Row.Server(
                        entry,
                        profileId,
                        profileId != 0L && profileId == selectedId
                    )
                )
            }
        }
        return rows
    }

    private fun updateLabel() {
        val label = FreeServerManager.lastUpdatedLabel()
        binding.updatedLabel.text = if (label.isBlank()) {
            getString(R.string.free_servers_never_updated)
        } else {
            getString(R.string.free_servers_updated, label)
        }
    }

    private fun selectServer(profileId: Long) {
        if (profileId == 0L) return
        runOnDefaultDispatcher {
            DataStore.selectedProxy = profileId
            onMainDispatcher { goMain() }
        }
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }

    class FreeServerAdapter(
        private val onSelect: (FreeServerEntry, Long) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        sealed class Row {
            data class Header(val region: String, val count: Int) : Row()
            data class Server(val entry: FreeServerEntry, val profileId: Long, val selected: Boolean) : Row()
        }

        private var rows: List<Row> = emptyList()

        fun submit(newRows: List<Row>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Header -> 0
            is Row.Server -> 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                HeaderHolder(LayoutFreeServerHeaderBinding.inflate(inflater, parent, false))
            } else {
                ServerHolder(LayoutFreeServerItemBinding.inflate(inflater, parent, false), onSelect)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).bind(row)
                is Row.Server -> (holder as ServerHolder).bind(row)
            }
        }

        override fun getItemCount(): Int = rows.size

        class HeaderHolder(private val binding: LayoutFreeServerHeaderBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(row: Row.Header) {
                binding.regionTitle.text = "${row.region} (${row.count})"
            }
        }

        class ServerHolder(
            private val binding: LayoutFreeServerItemBinding,
            private val onSelect: (FreeServerEntry, Long) -> Unit
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(row: Row.Server) {
                binding.serverName.text = row.entry.name
                binding.serverSubtitle.text = "${row.entry.region} · ${row.entry.type}"
                binding.selectedMark.visibility = if (row.selected) View.VISIBLE else View.GONE
                binding.root.setOnClickListener { onSelect(row.entry, row.profileId) }
            }
        }
    }
}
