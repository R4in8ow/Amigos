package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import com.r4in8ow.amigos.R
import io.nekohasekai.sagernet.widget.ListListener

class SettingsFragment : ToolbarFragment(R.layout.layout_config_settings) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.settings)

        parentFragmentManager.beginTransaction()
            .replace(R.id.settings, SettingsPreferenceFragment())
            .commitAllowingStateLoss()
    }

}