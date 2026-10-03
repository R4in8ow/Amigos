package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.r4in8ow.amigos.R
import com.r4in8ow.amigos.databinding.LayoutAmigosPremiumBinding
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.AmigosSecurity
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Amigos premium status screen: shows the signed-in username, subscription
 * status (active / expired / lifetime), expiry date and data usage, fetched
 * from https://amigos.r4in8ow.online/api/user/{username}.
 * When the subscription is expired, a renew prompt links to @R4in8ow on Telegram.
 */
class AmigosPremiumActivity : AppCompatActivity() {

    private lateinit var binding: LayoutAmigosPremiumBinding
    private var working = false

    private data class UserInfo(
        val username: String,
        val expiry: Long,
        val up: Long,
        val down: Long,
        val total: Long,
    )

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

        load()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

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
}
