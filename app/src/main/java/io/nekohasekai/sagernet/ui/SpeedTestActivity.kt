package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.r4in8ow.amigos.R
import com.r4in8ow.amigos.databinding.LayoutSpeedTestBinding
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.AmigosSpeedTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Amigos speed test: shows the active connection's country (exit IP),
 * live download/upload speeds, session totals, and runs a real
 * throughput test through the VPN tunnel.
 */
class SpeedTestActivity : ThemedActivity(), SagerConnection.Callback {

    private lateinit var binding: LayoutSpeedTestBinding
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_SPEED_TEST, false)
    private var testJob: Job? = null
    private var testing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutSpeedTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.amigos_speedtest_title)
            setDisplayHomeAsUpEnabled(true)
        }

        updateConnectionUi(DataStore.serviceState, null)
        binding.refreshLocationButton.setOnClickListener { refreshLocation() }
        binding.startTestButton.setOnClickListener { runTest() }

        connection.connect(this, this)
        refreshLocation()
    }

    override fun onDestroy() {
        testJob?.cancel()
        try {
            connection.disconnect(this)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    // --- SagerConnection.Callback ---

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        updateConnectionUi(state, profileName)
    }

    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        binding.liveDown.text = formatSpeed(stats.rxRateProxy)
        binding.liveUp.text = formatSpeed(stats.txRateProxy)
        binding.sessionTotal.text = getString(
            R.string.amigos_speedtest_session,
            Formatter.formatFileSize(this, stats.rxTotal),
            Formatter.formatFileSize(this, stats.txTotal),
        )
    }

    override fun onServiceConnected(service: ISagerNetService) = Unit

    // --- UI ---

    private fun updateConnectionUi(state: BaseService.State, profileName: String?) {
        val connected = state.connected
        binding.statusText.text = getString(
            if (connected) R.string.amigos_speedtest_connected else R.string.amigos_speedtest_not_connected
        )
        binding.statusDot.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (connected) android.R.color.holo_green_dark else android.R.color.darker_gray,
        )
        val name = profileName ?: currentProfileName()
        binding.profileText.text = name ?: ""
        binding.profileText.visibility = if (name.isNullOrBlank()) View.GONE else View.VISIBLE
        if (!connected) {
            binding.liveDown.text = formatSpeed(0)
            binding.liveUp.text = formatSpeed(0)
        }
    }

    private fun currentProfileName(): String? {
        return try {
            val id = DataStore.selectedProxy
            if (id <= 0) null else ProfileManager.getProfile(id)?.displayName()
        } catch (e: Exception) {
            Logs.w("SpeedTest: profile name lookup failed: ${e.message}")
            null
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return "${Formatter.formatFileSize(this, bytesPerSec)}/s"
    }

    private fun refreshLocation() {
        binding.countryName.text = getString(R.string.amigos_speedtest_locating)
        binding.exitIp.text = ""
        binding.countryFlag.text = ""
        lifecycleScope.launch {
            val info = AmigosSpeedTest.fetchExitInfo()
            if (info != null) {
                binding.countryFlag.text = AmigosSpeedTest.countryFlag(info.countryCode)
                binding.countryName.text = AmigosSpeedTest.countryName(info.countryCode)
                binding.exitIp.text = AmigosSpeedTest.maskIp(info.ip)
            } else {
                binding.countryName.text = getString(R.string.amigos_speedtest_location_failed)
            }
        }
    }

    private fun runTest() {
        if (testing) return
        testing = true
        binding.startTestButton.isEnabled = false
        binding.testProgress.visibility = View.VISIBLE
        binding.testProgress.progress = 0
        binding.testStatus.visibility = View.VISIBLE
        binding.resultPing.text = "—"
        binding.resultDown.text = "—"
        binding.resultUp.text = "—"

        testJob = lifecycleScope.launch {
            try {
                setTestStatus(getString(R.string.amigos_speedtest_testing_ping))
                val pingMs = withContext(Dispatchers.IO) {
                    connection.service?.urlTest()
                        ?: throw IllegalStateException("service not available")
                }
                binding.resultPing.text = getString(R.string.amigos_speedtest_ms, pingMs)

                setTestStatus(getString(R.string.amigos_speedtest_testing_download))
                val down = AmigosSpeedTest.runDownloadTest { p ->
                    binding.testProgress.progress = (p * 50).toInt()
                }
                binding.resultDown.text = formatMbps(down)

                setTestStatus(getString(R.string.amigos_speedtest_testing_upload))
                val up = AmigosSpeedTest.runUploadTest { p ->
                    binding.testProgress.progress = 50 + (p * 50).toInt()
                }
                binding.resultUp.text = formatMbps(up)

                binding.testProgress.progress = 100
                setTestStatus(getString(R.string.amigos_speedtest_done))
            } catch (e: Exception) {
                Logs.w("SpeedTest failed: ${e.message}")
                setTestStatus(getString(R.string.amigos_speedtest_failed))
            } finally {
                testing = false
                binding.startTestButton.isEnabled = true
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(2500)
                    if (!testing) {
                        binding.testProgress.visibility = View.GONE
                        binding.testStatus.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun setTestStatus(text: String) {
        binding.testStatus.text = text
    }

    private fun formatMbps(mbps: Double): String {
        return String.format(Locale.US, "%.1f Mbps", mbps)
    }
}
