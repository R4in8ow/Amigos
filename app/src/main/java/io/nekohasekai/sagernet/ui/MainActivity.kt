package io.nekohasekai.sagernet.ui

import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.r4in8ow.amigos.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import com.r4in8ow.amigos.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import com.r4in8ow.amigos.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.free.FreeServerManager
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.AmigosAds
import io.nekohasekai.sagernet.utils.AmigosPing
import io.nekohasekai.sagernet.utils.AmigosSecurity
import moe.matsuri.nb4a.utils.Util
import kotlin.math.abs

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener {

    lateinit var binding: LayoutMainBinding

    companion object {
        const val TAB_HOME = 0
        const val TAB_CONFIGS = 1
        const val TAB_SETTINGS = 2
        private const val KEY_FLOAT_TX = "amigos_float_tx"
        private const val KEY_FLOAT_TY = "amigos_float_ty"
    }

    private var currentTab = TAB_HOME

    private var connectFloatTx = 0f
    private var connectFloatTy = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AmigosSecurity.applyFlagSecure(this)

        binding = LayoutMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_home -> showTab(TAB_HOME)
                R.id.tab_configs -> showTab(TAB_CONFIGS)
                R.id.tab_settings -> showTab(TAB_SETTINGS)
                else -> false
            }
        }

        if (savedInstanceState == null) {
            showTab(TAB_HOME)
        } else {
            connectFloatTx = savedInstanceState.getFloat(KEY_FLOAT_TX, 0f)
            connectFloatTy = savedInstanceState.getFloat(KEY_FLOAT_TY, 0f)
        }
        setupConnectFloat()
        onBackPressedDispatcher.addCallback {
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
            } else {
                moveTaskToBack(true)
            }
        }

        changeState(BaseService.State.Idle)
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)
        GroupManager.userInterface = GroupInterfaceAdapter(this)

        runOnDefaultDispatcher {
            val hasGroups = SagerDatabase.groupDao.allGroups().isNotEmpty()
            if (!hasGroups) {
                DataStore.amigosFreeMode = true
                try {
                    FreeServerManager.refresh()
                } catch (_: Exception) {
                }
            }
        }

        if (intent?.action == Intent.ACTION_VIEW) {
            onNewIntent(intent)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            val checkPermission =
                ContextCompat.checkSelfPermission(this@MainActivity, POST_NOTIFICATIONS)
            if (checkPermission != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this@MainActivity, arrayOf(POST_NOTIFICATIONS), 0
                )
            }
        }

        if (isPreview) {
            MaterialAlertDialogBuilder(this)
                .setTitle(BuildConfig.PRE_VERSION_NAME)
                .setMessage(R.string.preview_version_hint)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    fun showTab(tab: Int): Boolean {
        supportFragmentManager.popBackStack(
            null,
            androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
        )
        val fragment: Fragment = when (tab) {
            TAB_CONFIGS -> AmigosConfigsFragment()
            TAB_SETTINGS -> AmigosSettingsFragment()
            else -> AmigosHomeFragment()
        }
        currentTab = tab
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
        return true
    }

    fun openAdvancedSettings() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, SettingsFragment())
            .addToBackStack("advanced_settings")
            .commitAllowingStateLoss()
    }

    fun toggleVpn() {
        if (DataStore.serviceState.canStop) {
            SagerNet.stopService()
        } else {
            connect.launch(null)
        }
    }

    fun connectToSelected() {
        if (DataStore.serviceState.canStop) {
            SagerNet.reloadService()
        } else {
            connect.launch(null)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloat(KEY_FLOAT_TX, connectFloatTx)
        outState.putFloat(KEY_FLOAT_TY, connectFloatTy)
    }

    private fun setupConnectFloat() {
        val floatBox = binding.connectFloat
        val button = binding.connectButton
        floatBox.translationX = connectFloatTx
        floatBox.translationY = connectFloatTy
        updateConnectButton(DataStore.serviceState)

        button.setOnClickListener { toggleVpn() }
        binding.pingPill.setOnClickListener { quickPing() }

        val touchSlop =
            android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var baseTx = 0f
        var baseTy = 0f
        var dragging = false
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    baseTx = floatBox.translationX
                    baseTy = floatBox.translationY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        val parent = floatBox.parent as View
                        val minTx = -(parent.width - floatBox.width).toFloat()
                        val minTy = -(parent.height - floatBox.height).toFloat()
                        connectFloatTx = (baseTx + dx).coerceIn(minTx, 0f)
                        connectFloatTy = (baseTy + dy).coerceIn(minTy, 0f)
                        floatBox.translationX = connectFloatTx
                        floatBox.translationY = connectFloatTy
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        dragging = false
                        true
                    } else {
                        v.performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun updateConnectButton(state: BaseService.State) {
        if (!::binding.isInitialized) return
        val connected = state == BaseService.State.Connected
        val tint = if (connected) {
            ContextCompat.getColorStateList(this, R.color.amigos_connected_green)
        } else {
            ContextCompat.getColorStateList(this, R.color.amigos_amber)
        }
        binding.connectButton.backgroundTintList = tint
    }

    private fun quickPing() {
        if (AmigosPing.isRunning()) return
        runOnDefaultDispatcher {
            val profile = try {
                SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
            } catch (_: Exception) {
                null
            }
            if (profile == null) {
                onMainDispatcher {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.amigos_home_no_server,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@runOnDefaultDispatcher
            }
            DataStore.runningTest = true
            val ms = try {
                UrlTest().doTest(profile)
            } catch (_: Exception) {
                -1
            } finally {
                DataStore.runningTest = false
            }
            onMainDispatcher {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.amigos_home_ping_result, ms),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val uri = intent.data ?: return

        runOnDefaultDispatcher {
            if (uri.scheme == "sn" && uri.host == "subscription" || uri.scheme == "clash") {
                importSubscription(uri)
            } else {
                importProfile(uri)
            }
        }
    }

    fun urlTest(): Int {
        if (!DataStore.serviceState.connected || connection.service == null) {
            error("not started")
        }
        return connection.service!!.urlTest()
    }

    suspend fun importSubscription(uri: Uri) {
        val group: ProxyGroup

        val url = uri.getQueryParameter("url")
        if (!url.isNullOrBlank()) {
            group = ProxyGroup(type = GroupType.SUBSCRIPTION)
            val subscription = SubscriptionBean()
            group.subscription = subscription

            subscription.link = url
            group.name = uri.getQueryParameter("name")
        } else {
            val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() } ?: return
            try {
                group = KryoConverters.deserialize(
                    ProxyGroup().apply { export = true }, Util.zlibDecompress(Util.b64Decode(data))
                ).apply {
                    export = false
                }
            } catch (e: Exception) {
                onMainDispatcher {
                    alert(e.readableMessage).show()
                }
                return
            }
        }

        val name = group.name.takeIf { !it.isNullOrBlank() } ?: group.subscription?.link
        ?: group.subscription?.token
        if (name.isNullOrBlank()) return

        group.name = group.name.takeIf { !it.isNullOrBlank() }
            ?: ("Subscription #" + System.currentTimeMillis())

        onMainDispatcher {
            showTab(TAB_CONFIGS)

            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.subscription_import)
                .setMessage(getString(R.string.subscription_import_message, name))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportSubscription(group)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        GroupManager.createGroup(subscription)
        GroupUpdater.startUpdate(subscription, true)
    }

    suspend fun importProfile(uri: Uri) {
        val profile = try {
            parseProxies(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }

        onMainDispatcher {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = DataStore.selectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)

        onMainDispatcher {
            showTab(TAB_CONFIGS)
            snackbar(resources.getQuantityString(R.plurals.added, 1, 1)).show()
        }
    }

    override fun missingPlugin(profileName: String, pluginName: String) {
        val pluginEntity = PluginEntry.find(pluginName)

        if (pluginEntity == null) {
            snackbar(getString(R.string.plugin_unknown, pluginName)).show()
            return
        }

        MaterialAlertDialogBuilder(this).setTitle(R.string.missing_plugin)
            .setMessage(
                getString(
                    R.string.profile_requiring_plugin, pluginName, pluginEntity.displayName
                )
            )
            .setPositiveButton(R.string.action_download) { _, _ ->
                showDownloadDialog(pluginEntity)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.action_learn_more) { _, _ ->
                launchCustomTab("https://matsuridayo.github.io/nb4a-plugin/")
            }
            .show()
    }

    private fun showDownloadDialog(pluginEntry: PluginEntry) {
        var index = 0
        var playIndex = -1
        var fdroidIndex = -1

        val items = mutableListOf<String>()
        if (pluginEntry.downloadSource.playStore) {
            items.add(getString(R.string.install_from_play_store))
            playIndex = index++
        }
        if (pluginEntry.downloadSource.fdroid) {
            items.add(getString(R.string.install_from_fdroid))
            fdroidIndex = index++
        }

        items.add(getString(R.string.download))
        val downloadIndex = index

        MaterialAlertDialogBuilder(this).setTitle(pluginEntry.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    playIndex -> launchCustomTab("https://play.google.com/store/apps/details?id=${pluginEntry.packageName}")
                    fdroidIndex -> launchCustomTab("https://f-droid.org/packages/${pluginEntry.packageName}/")
                    downloadIndex -> launchCustomTab(pluginEntry.downloadSource.downloadLink)
                }
            }
            .show()
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
    ) {
        DataStore.serviceState = state
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG)
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        val wasActive = DataStore.serviceState.canStop
        changeState(state, msg, true)
        updateConnectButton(state)
        if (AmigosAds.isAdsEnabled()) {
            if (state == BaseService.State.Connected) {
                AmigosAds.preloadInterstitial(this)
            } else if (wasActive && (state == BaseService.State.Stopped || state == BaseService.State.Idle)) {
                AmigosAds.showInterstitialIfDue(this)
            }
        }
    }

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    override fun onServiceConnected(service: ISagerNetService) = changeState(
        try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    override fun cbSpeedUpdate(stats: SpeedDisplayData) = Unit

    override fun cbTrafficUpdate(data: TrafficData) {
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(data)
        }
    }

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.SERVICE_MODE -> onBinderDied()
            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                if (DataStore.serviceState.canStop) {
                    snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                        SagerNet.reloadService()
                    }.show()
                }
            }
        }
    }

    override fun onStart() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
    }

    override fun onStop() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }
}
