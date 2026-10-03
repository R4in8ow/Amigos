package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.r4in8ow.amigos.BuildConfig
import com.r4in8ow.amigos.R
import com.r4in8ow.amigos.databinding.LayoutAmigosSettingsBinding
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.AmigosAccount
import io.nekohasekai.sagernet.utils.AmigosSecurity
import kotlinx.coroutines.launch

class AmigosSettingsFragment : Fragment() {

    private var _binding: LayoutAmigosSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = LayoutAmigosSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AmigosSecurity.applyFlagSecure(requireActivity())

        binding.loginButton.setOnClickListener {
            startActivity(Intent(requireContext(), AmigosLoginActivity::class.java))
        }
        binding.logoutButton.setOnClickListener { logout() }
        binding.renewButton.setOnClickListener { openTelegram() }
        binding.advancedCard.setOnClickListener {
            (activity as? MainActivity)?.openAdvancedSettings()
        }
        binding.versionText.text = "Amigos ${BuildConfig.VERSION_NAME}"

        renderAccount()
    }

    override fun onResume() {
        super.onResume()
        renderAccount()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun renderAccount() {
        val b = _binding ?: return
        val username = DataStore.amigosUsername
        if (username.isBlank()) {
            b.loggedOutView.visibility = View.VISIBLE
            b.loggedInView.visibility = View.GONE
            return
        }
        b.loggedOutView.visibility = View.GONE
        b.loggedInView.visibility = View.VISIBLE
        b.usernameText.text = username
        b.statusText.text = "…"
        lifecycleScope.launch {
            val info = try {
                AmigosAccount.fetch(username)
            } catch (e: Exception) {
                null
            }
            val bb = _binding ?: return@launch
            if (info == null) {
                bb.statusText.text = "—"
                return@launch
            }
            val expired = AmigosAccount.isExpired(info)
            val statusLine = (if (expired) "Expired" else "Active") +
                " · " + AmigosAccount.expiryLabel(info) +
                "\n" + AmigosAccount.usageLabel(info)
            bb.statusText.text = statusLine
            bb.statusText.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (expired) R.color.material_red_500 else R.color.material_green_500
                )
            )
            bb.renewButton.visibility = if (expired) View.VISIBLE else View.GONE
        }
    }

    private fun logout() {
        DataStore.amigosUsername = ""
        DataStore.amigosFreeMode = true
        renderAccount()
    }

    private fun openTelegram() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=R4in8ow")))
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/R4in8ow")))
        }
    }
}
