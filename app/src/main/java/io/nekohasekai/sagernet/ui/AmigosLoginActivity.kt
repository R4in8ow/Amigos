package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.r4in8ow.amigos.databinding.LayoutAmigosLoginBinding
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.AmigosSecurity

/**
 * Amigos onboarding: user enters their username, the app fetches
 * https://amigos.r4in8ow.online/sub/{username} and imports it as a
 * subscription group with auto-update enabled.
 */
class AmigosLoginActivity : AppCompatActivity() {

    private lateinit var binding: LayoutAmigosLoginBinding
    private var working = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AmigosSecurity.applyFlagSecure(this)
        binding = LayoutAmigosLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // already signed in? go straight to main
        if (DataStore.amigosUsername.isNotBlank()) {
            goMain()
            return
        }

        binding.continueButton.setOnClickListener { submit() }
        binding.freeButton.setOnClickListener {
            if (!working) {
                DataStore.amigosFreeMode = true
                startActivity(Intent(this, FreeServersActivity::class.java))
            }
        }
        binding.usernameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else false
        }
    }

    private fun sanitizeUsername(raw: String): String {
        return raw.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "")
    }

    private fun setWorking(active: Boolean) {
        working = active
        binding.continueButton.isEnabled = !active
        binding.freeButton.isEnabled = !active
        binding.usernameInput.isEnabled = !active
        binding.usernameLayout.isEnabled = !active
        binding.loginProgress.visibility = if (active) View.VISIBLE else View.GONE
        if (active) {
            binding.loginError.visibility = View.GONE
            binding.continueButton.text = getString(R.string.amigos_login_working)
        } else {
            binding.continueButton.text = getString(R.string.amigos_login_continue)
        }
    }

    private fun showError(message: String) {
        binding.loginError.text = message
        binding.loginError.visibility = View.VISIBLE
    }

    private fun submit() {
        if (working) return
        val username = sanitizeUsername(binding.usernameInput.text?.toString() ?: "")
        if (username.isEmpty()) {
            showError(getString(R.string.amigos_login_error_empty))
            return
        }
        setWorking(true)
        runOnDefaultDispatcher {
            try {
                val link = Key.AMIGOS_SUB_BASE + username
                val group = findOrCreateGroup(link)
                val ok = GroupUpdater.executeUpdate(group, true)
                val profileCount = SagerDatabase.proxyDao.getByGroup(group.id).size
                if (!ok || profileCount == 0) {
                    throw IllegalStateException("empty subscription")
                }
                DataStore.amigosUsername = username
                onMainDispatcher { goMain() }
            } catch (e: Exception) {
                Logs.w("Amigos premium subscription update failed: ${e.javaClass.simpleName}")
                onMainDispatcher {
                    setWorking(false)
                    showError(getString(R.string.amigos_login_error_failed))
                }
            }
        }
    }

    private suspend fun findOrCreateGroup(link: String): ProxyGroup {
        val existing = SagerDatabase.groupDao.subscriptions()
            .firstOrNull { it.subscription?.link?.startsWith(Key.AMIGOS_SUB_BASE) == true }
        return if (existing != null) {
            existing.apply {
                name = getString(R.string.amigos_premium_group)
                subscription!!.link = link
                subscription!!.lastUpdated = 0
                subscription!!.autoUpdate = true
                if (subscription!!.autoUpdateDelay <= 0) subscription!!.autoUpdateDelay = 360
                serialize()
            }.also { GroupManager.updateGroup(it) }
        } else {
            GroupManager.createGroup(
                ProxyGroup(
                    name = getString(R.string.amigos_premium_group),
                    type = GroupType.SUBSCRIPTION,
                    subscription = SubscriptionBean().apply {
                        this.link = link
                        autoUpdate = true
                        autoUpdateDelay = 360
                        updateWhenConnectedOnly = false
                    }
                ).apply { serialize() }
            )
        }
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }
}
