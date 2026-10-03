package io.nekohasekai.sagernet.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.r4in8ow.amigos.R
import com.r4in8ow.amigos.databinding.LayoutAmigosPremiumBinding
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.utils.AmigosSecurity
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Amigos premium screen.
 *
 * Top: subscription status (username, active/expired, expiry date, data usage).
 * Middle: premium servers — Ping All tests every server in the premium
 * subscription group, Auto Smart Connect picks the lowest-ping working
 * server (VLESS preferred on ties) and connects.
 * Bottom: live connection dashboard — state, server, VPN IP, up/down
 * speed, CPU and RAM usage — shown while the VPN is connected.
 */
@OptIn(DelicateCoroutinesApi::class)
class AmigosPremiumActivity : AppCompatActivity(), SagerConnection.Callback {

    private lateinit var binding: LayoutAmigosPremiumBinding
    private var working = false

    private data class UserInfo(
        val username: String,
        val expiry: Long,
        val up: Long,
        val down: Long,
        val total: Long,
    )

    // ---------- service connection (live speed + state) ----------

    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_SPEED_TEST, true)

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
    }

    private var lastTxRate = 0L
    private var lastRxRate = 0L
    private var vpnIp: String? = null

    override fun onServiceConnected(service: ISagerNetService) = Unit

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        updateDashboard()
        if (state == BaseService.State.Connected) {
            fetchVpnIp()
            loadConnectedServer()
            startSysUpdater()
        } else {
            stopSysUpdater()
        }
    }

    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        lastTxRate = stats.txRateProxy
        lastRxRate = stats.rxRateProxy
        if (binding.dashboardCard.visibility == View.VISIBLE) {
            binding.connSpeedText.text = getString(
                R.string.amigos_dashboard_speed,
                formatBytes(lastTxRate), formatBytes(lastRxRate)
            )
        }
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AmigosSecurity.applyFlagSecure(this)

        if (DataStore.amigosUsername.isBlank()) {
            startActivity(Intent(this, AmigosLoginActivity::class.java))
            finish()
            return
        }

        binding = LayoutAmigosPremiumBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.amigos_premium_title)
        }

        binding.usernameText.text = DataStore.amigosUsername
        binding.refreshButton.setOnClickListener { load() }
        binding.logoutButton.setOnClickListener { logout() }
        binding.renewButton.setOnClickListener { openTelegram() }

        binding.serversRecycler.layoutManager = LinearLayoutManager(this)
        binding.serversRecycler.isNestedScrollingEnabled = false
        binding.serversRecycler.adapter = serverAdapter
        binding.pingAllButton.setOnClickListener { pingAll() }
        binding.smartConnectButton.setOnClickListener { smartConnect() }
        binding.disconnectButton.setOnClickListener {
            if (DataStore.serviceState.canStop) SagerNet.stopService()
        }

        connection.connect(this, this)

        load()
        loadServerList()
        updateDashboard()
        if (DataStore.serviceState == BaseService.State.Connected) {
            fetchVpnIp()
            loadConnectedServer()
            startSysUpdater()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSysUpdater()
        try {
            connection.disconnect(this)
        } catch (_: Exception) {
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ---------- subscription info ----------

    private fun setWorking(active: Boolean) {
        working = active
        binding.loadingProgress.visibility = if (active) View.VISIBLE else View.GONE
        binding.refreshButton.isEnabled = !active
        if (active) binding.errorText.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorText.visibility = View.VISIBLE
    }

    private fun load() {
        if (working) return
        setWorking(true)
        val username = DataStore.amigosUsername
        runOnDefaultDispatcher {
            try {
                val info = fetchUserInfo(username)
                onMainDispatcher { render(info) }
            } catch (e: NotFoundException) {
                Logs.w("Amigos premium user not found: $username")
                onMainDispatcher {
                    setWorking(false)
                    showError(getString(R.string.amigos_premium_not_found))
                }
            } catch (e: Exception) {
                Logs.w("Amigos premium info fetch failed: ${e.javaClass.simpleName}")
                onMainDispatcher {
                    setWorking(false)
                    showError(getString(R.string.amigos_premium_error))
                }
            }
        }
    }

    private class NotFoundException : Exception()

    private fun fetchUserInfo(username: String): UserInfo {
        val url = Key.AMIGOS_API_BASE + "user/" + URLEncoder.encode(username, "UTF-8")
        val client = Libcore.newHttpClient().apply { modernTLS() }
        val response = client.newRequest().apply { setURL(url) }.execute()
        val body = Util.getStringBox(response.contentString)
        val json = JSONObject(body)
        if (!json.has("username")) {
            if (json.optString("detail").contains("not found", ignoreCase = true)) {
                throw NotFoundException()
            }
            throw IllegalStateException("unexpected response")
        }
        return UserInfo(
            username = json.getString("username"),
            expiry = json.optLong("expiry", 0L),
            up = json.optLong("up", 0L),
            down = json.optLong("down", 0L),
            total = json.optLong("total", 0L),
        )
    }

    private fun render(info: UserInfo) {
        setWorking(false)
        val now = System.currentTimeMillis()
        val expired = info.expiry > 0 && info.expiry < now

        binding.statusText.apply {
            if (expired) {
                text = getString(R.string.amigos_premium_status_expired)
                setTextColor(ContextCompat.getColor(context, R.color.material_red_500))
            } else {
                text = getString(R.string.amigos_premium_status_active)
                setTextColor(ContextCompat.getColor(context, R.color.material_green_500))
            }
        }

        binding.expiryText.text = if (info.expiry <= 0) {
            getString(R.string.amigos_premium_lifetime)
        } else {
            getString(
                R.string.amigos_premium_expires_on,
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(info.expiry))
            )
        }

        val used = info.up + info.down
        if (info.total <= 0) {
            binding.usageText.text =
                getString(R.string.amigos_premium_usage_unlimited, formatBytes(used))
            binding.usageProgress.visibility = View.GONE
        } else {
            binding.usageText.text = getString(
                R.string.amigos_premium_usage_detail,
                formatBytes(used),
                formatBytes(info.total)
            )
            binding.usageProgress.visibility = View.VISIBLE
            binding.usageProgress.progress =
                ((used * 100L) / info.total).coerceIn(0L, 100L).toInt()
        }

        binding.renewButton.visibility = if (expired) View.VISIBLE else View.GONE
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    private fun openTelegram() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=R4in8ow")))
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/R4in8ow")))
        }
    }

    private fun logout() {
        DataStore.amigosUsername = ""
        DataStore.amigosFreeMode = true
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }

    // ---------- premium servers: Ping All ----------

    private val serverAdapter = ServerAdapter()
    private var testing = false
    private var testedCount = 0
    private var testedTotal = 0

    private fun premiumGroup(): ProxyGroup? =
        SagerDatabase.groupDao.subscriptions()
            .firstOrNull { it.subscription?.link?.startsWith(Key.AMIGOS_SUB_BASE) == true }

    private fun loadServerList() {
        runOnDefaultDispatcher {
            val group = try {
                premiumGroup()
            } catch (_: Exception) {
                null
            }
            val profiles = try {
                group?.let { SagerDatabase.proxyDao.getByGroup(it.id) } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            onMainDispatcher {
                serverAdapter.setProfiles(profiles)
                updateServersUi()
            }
        }
    }

    private fun updateServersUi() {
        val n = serverAdapter.itemCount
        binding.serversProgress.visibility = if (testing) View.VISIBLE else View.GONE
        binding.serversStatusText.text = if (testing) {
            getString(R.string.amigos_servers_testing, testedCount, testedTotal)
        } else if (n == 0) {
            getString(R.string.amigos_servers_empty)
        } else {
            getString(R.string.amigos_servers_count, n)
        }
        binding.pingAllButton.isEnabled = !testing
        binding.smartConnectButton.isEnabled = !testing
    }

    private fun showServersError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun pingAll(onDone: (() -> Unit)? = null) {
        if (testing || DataStore.runningTest) return
        val group = try {
            premiumGroup()
        } catch (_: Exception) {
            null
        }
        if (group == null) {
            showServersError(getString(R.string.amigos_servers_no_group))
            return
        }
        testing = true
        DataStore.runningTest = true
        testedCount = 0
        updateServersUi()
        runOnDefaultDispatcher {
            val profiles = try {
                SagerDatabase.proxyDao.getByGroup(group.id)
            } catch (_: Exception) {
                emptyList()
            }
            testedTotal = profiles.size
            onMainDispatcher {
                serverAdapter.setProfiles(profiles)
                updateServersUi()
            }
            val queue = ConcurrentLinkedQueue(profiles)
            val jobs = mutableListOf<Job>()
            repeat(DataStore.connectionTestConcurrent.coerceAtLeast(1)) {
                jobs += launch(Dispatchers.IO) {
                    val urlTest = UrlTest()
                    while (isActive) {
                        val profile = queue.poll() ?: break
                        profile.status = 0
                        onMainDispatcher { serverAdapter.update(profile) }
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
                        testedCount++
                        onMainDispatcher {
                            serverAdapter.update(profile)
                            updateServersUi()
                        }
                    }
                }
            }
            jobs.joinAll()
            DataStore.runningTest = false
            testing = false
            onMainDispatcher {
                updateServersUi()
                onDone?.invoke()
            }
        }
    }

    // ---------- Auto Smart Connect ----------

    private fun protocolPriority(profile: ProxyEntity): Int = when {
        profile.type == ProxyEntity.TYPE_VMESS && profile.vmessBean?.isVLESS == true -> 0
        profile.type == ProxyEntity.TYPE_TROJAN -> 1
        profile.type == ProxyEntity.TYPE_VMESS -> 2
        profile.type == ProxyEntity.TYPE_SS -> 3
        else -> 4
    }

    private fun smartConnect() {
        if (testing || DataStore.runningTest) return
        if (serverAdapter.current().none { it.status == 1 }) {
            pingAll { pickAndConnect() }
        } else {
            pickAndConnect()
        }
    }

    private fun pickAndConnect() {
        val best = serverAdapter.current()
            .filter { it.status == 1 }
            .minWithOrNull(compareBy({ it.ping }, { protocolPriority(it) }))
        if (best == null) {
            showServersError(getString(R.string.amigos_servers_none_working))
            return
        }
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = best.id
        runOnMainDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(best.id, true)
        }
        serverAdapter.notifyDataSetChanged()
        Toast.makeText(
            this,
            getString(R.string.amigos_smart_connected, best.displayName(), best.ping),
            Toast.LENGTH_SHORT
        ).show()
        if (DataStore.serviceState.canStop) {
            SagerNet.reloadService()
        } else {
            connect.launch(null)
        }
    }

    private inner class ServerAdapter : RecyclerView.Adapter<ServerAdapter.VH>() {
        private val items = mutableListOf<ProxyEntity>()

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(android.R.id.text1)
            val subtitle: TextView = view.findViewById(android.R.id.text2)
        }

        fun setProfiles(profiles: List<ProxyEntity>) {
            items.clear()
            items.addAll(profiles)
            notifyDataSetChanged()
        }

        fun current(): List<ProxyEntity> = items.toList()

        fun update(profile: ProxyEntity) {
            val i = items.indexOfFirst { it.id == profile.id }
            if (i >= 0) {
                items[i] = profile
                notifyItemChanged(i)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return VH(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val profile = items[position]
            val selected = profile.id == DataStore.selectedProxy
            holder.title.text = (if (selected) "● " else "") + profile.displayName()
            holder.subtitle.text = when (profile.status) {
                0 -> getString(R.string.amigos_server_testing)
                1 -> getString(R.string.amigos_server_ping, profile.ping) +
                    if (selected) " · " + getString(R.string.amigos_server_selected) else ""
                2, 3 -> getString(R.string.amigos_server_failed)
                else -> getString(R.string.amigos_server_untested)
            }
            holder.subtitle.setTextColor(
                ContextCompat.getColor(
                    holder.itemView.context,
                    when (profile.status) {
                        1 -> R.color.material_green_500
                        2, 3 -> R.color.material_red_500
                        else -> android.R.color.secondary_text_dark
                    }
                )
            )
            holder.itemView.setOnClickListener {
                val old = DataStore.selectedProxy
                DataStore.selectedProxy = profile.id
                runOnMainDispatcher {
                    ProfileManager.postUpdate(old, true)
                    ProfileManager.postUpdate(profile.id, true)
                }
                notifyDataSetChanged()
            }
        }
    }

    // ---------- connection dashboard ----------

    private val sysHandler = Handler(Looper.getMainLooper())
    private var sysRunning = false
    private val sysTick = object : Runnable {
        override fun run() {
            if (!sysRunning) return
            runOnDefaultDispatcher {
                val cpu = measureCpuPct()
                val ram = measureRamPct()
                onMainDispatcher {
                    binding.connSysText.text = getString(
                        R.string.amigos_dashboard_sys,
                        if (cpu >= 0) cpu.toString() else "—",
                        if (ram >= 0) ram.toString() else "—"
                    )
                }
            }
            sysHandler.postDelayed(this, 2000)
        }
    }

    private fun startSysUpdater() {
        if (sysRunning) return
        sysRunning = true
        sysHandler.post(sysTick)
    }

    private fun stopSysUpdater() {
        sysRunning = false
        sysHandler.removeCallbacks(sysTick)
    }

    private fun updateDashboard() {
        val state = DataStore.serviceState
        val show = state == BaseService.State.Connected || state == BaseService.State.Connecting
        binding.dashboardCard.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        binding.connStateText.text = getString(R.string.amigos_dashboard_state, state.name)
        binding.connIpText.text = getString(R.string.amigos_dashboard_ip, vpnIp ?: "…")
        binding.connSpeedText.text = getString(
            R.string.amigos_dashboard_speed,
            formatBytes(lastTxRate), formatBytes(lastRxRate)
        )
    }

    private fun loadConnectedServer() {
        runOnDefaultDispatcher {
            val name = try {
                SagerDatabase.proxyDao.getById(DataStore.selectedProxy)?.displayName()
            } catch (_: Exception) {
                null
            }
            onMainDispatcher {
                binding.connServerText.text =
                    getString(R.string.amigos_dashboard_server, name ?: "—")
            }
        }
    }

    private fun fetchVpnIp() {
        vpnIp = null
        runOnDefaultDispatcher {
            try {
                val client = Libcore.newHttpClient().apply { modernTLS() }
                val response = client.newRequest().apply {
                    setURL("https://api.ipify.org?format=json")
                }.execute()
                val ip = JSONObject(Util.getStringBox(response.contentString)).optString("ip", "")
                onMainDispatcher {
                    vpnIp = ip.ifBlank { "—" }
                    updateDashboard()
                }
            } catch (_: Exception) {
                onMainDispatcher {
                    vpnIp = "—"
                    updateDashboard()
                }
            }
        }
    }

    private fun readCpuStat(): Pair<Long, Long>? = try {
        val line = File("/proc/stat").bufferedReader().use { it.readLine() } ?: return null
        val p = line.trim().split(Regex("\\s+"))
        if (p.size < 8 || p[0] != "cpu") return null
        val nums = p.drop(1).map { it.toLong() }
        val idle = nums[3] + nums[4]
        nums.sum() to idle
    } catch (_: Exception) {
        null
    }

    private fun measureCpuPct(): Int {
        val a = readCpuStat() ?: return -1
        try {
            Thread.sleep(400)
        } catch (_: InterruptedException) {
            return -1
        }
        val b = readCpuStat() ?: return -1
        val dt = b.first - a.first
        val di = b.second - a.second
        if (dt <= 0) return -1
        return ((1.0 - di.toDouble() / dt) * 100).toInt().coerceIn(0, 100)
    }

    private fun measureRamPct(): Int = try {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        if (mi.totalMem <= 0) -1
        else ((mi.totalMem - mi.availMem) * 100 / mi.totalMem).toInt().coerceIn(0, 100)
    } catch (_: Exception) {
        -1
    }
}
