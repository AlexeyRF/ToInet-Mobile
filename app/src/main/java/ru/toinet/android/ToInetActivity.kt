package ru.toinet.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsetsController
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.navigation.findNavController
import com.scottyab.rootbeer.RootBeer
import ru.toinet.android.byedpi.services.ByeDpiProxyService
import ru.toinet.android.easyvpn.EasyVpnService
import ru.toinet.android.operaproxy.OperaProxyService
import ru.toinet.android.service.ToInetConstants
import ru.toinet.android.service.ToInetService
import ru.toinet.android.svpn.SvpnService
import ru.toinet.android.tgws.TgwsService
import ru.toinet.android.ui.connect.ConnectUiState
import ru.toinet.android.ui.connect.ConnectViewModel
import ru.toinet.android.ui.connect.RequestPostNotificationPermission
import ru.toinet.android.ui.core.BaseActivity
import ru.toinet.android.ui.core.DeviceAuthenticationPrompt
import ru.toinet.android.ui.more.LogBottomSheet
import ru.toinet.android.util.ACTION_VPN_PROFILE_CHANGED
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.isAnyStandaloneServiceRunning
import ru.toinet.android.util.isServiceRunningInPackage
import ru.toinet.android.util.sendIntentToService
import ru.toinet.android.util.showToast
import ru.toinet.android.vpn.data.ServiceStatus
import ru.toinet.android.vpn.services.AppVpnService
import org.torproject.jni.TorService

class ToInetActivity : BaseActivity() {

    private lateinit var logBottomSheet: LogBottomSheet
    private lateinit var navController: NavController

    var portSocks: Int = -1
    var portHttp: Int = -1

    var previousReceivedTorStatus: String? = null

    private var rootLayout: View? = null

    private val connectViewModel: ConnectViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        previousReceivedTorStatus = savedInstanceState?.getString(KEY_TOR_STATUS)

        title = getString(R.string.app_name)

        try {
            createToInet()

        } catch (_: RuntimeException) {
            intent = null
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_TOR_STATUS, previousReceivedTorStatus)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)

        previousReceivedTorStatus = savedInstanceState.getString(KEY_TOR_STATUS)
    }

    private fun createToInet() {
        setContentView(R.layout.activity_toinet)
        rootLayout = findViewById(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.nav_fragment)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        logBottomSheet = LogBottomSheet()

        navController = findNavController(R.id.nav_fragment)

        navController.addOnDestinationChangedListener { _, destination, _ ->
        }

        val filter = IntentFilter().apply {
            addAction(ToInetConstants.LOCAL_ACTION_STATUS)
            addAction(ToInetConstants.LOCAL_ACTION_LOG)
            addAction(ToInetConstants.LOCAL_ACTION_PORTS)
            addAction(ACTION_VPN_PROFILE_CHANGED)
        }

        ContextCompat.registerReceiver(this, toinetServiceBroadcastReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED)

        requestNotificationPermission()

        if (!rootDetectionShown && Prefs.detectRoot() && RootBeer(this).isRooted) {
            //we found indication of root
            applicationContext.showToast(getString(R.string.root_warning))

            rootDetectionShown = true
        }

        ru.toinet.android.util.ArchHelper.checkAndShowWarning(this)

        onBackPressedDispatcher.addCallback(this) {
            navController.currentBackStackEntry?.let {
                when (it.destination.id) {
                    R.id.connectFragment -> {
                        finish()
                    }
                    else -> {
                        navController.popBackStack()
                    }
                }
            }

        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp()
    }

    private fun requestNotificationPermission() {
        // automatically granted on Android 12 and lower
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
            return
        val checkPostNotificationPerm =
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        when (checkPostNotificationPerm) {
            PackageManager.PERMISSION_GRANTED -> {
                Log.d(
                    "ToInetActivity",
                    "Granted Permission ${Manifest.permission.POST_NOTIFICATIONS}"
                )
            }

            else -> {
                Log.d(
                    "ToInetActivity",
                    "Try Prompting For ${Manifest.permission.POST_NOTIFICATIONS}"
                )
                requestPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }
        }
    }

    // Register the permissions callback, which handles the user's response to the
    // system permissions dialog.
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("ToInetActivity", "User just granted ${Manifest.permission.POST_NOTIFICATIONS}")
        } else {
            Log.d("ToInetActivity", "Notification denied")
            RequestPostNotificationPermission().show(
                supportFragmentManager,
                "RequestNotificationDialog"
            )
        }
    }

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            connectViewModel.triggerStartTorAndVpn()
        }
    }

    override fun onStart() {
        super.onStart()
        promptDeviceAuthenticationIfRequired()
    }

    override fun onResume() {
        super.onResume()
        val isViewModelStarting = connectViewModel.uiState.value is ConnectUiState.Starting
        val effectiveStatus = resolveEffectiveStatus(this, previousReceivedTorStatus, isViewModelStarting)
        previousReceivedTorStatus = effectiveStatus
        if (effectiveStatus != TorService.STATUS_STARTING || !isViewModelStarting) {
            connectViewModel.updateState(this, effectiveStatus)
        }
        val hasActiveService = if (Prefs.isGlobalVpnEnabled) {
            AppVpnService.activeService != null
        } else {
            isAnyStandaloneServiceRunning() || hasAnyLiveStandaloneService(this)
        }
        if (hasActiveService) {
            sendIntentToService(ToInetConstants.CMD_ACTIVE)
        }
        checkStartVpnFromIntent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkStartVpnFromIntent()
    }

    private fun checkStartVpnFromIntent() {
        if (intent?.getBooleanExtra("start_vpn", false) == true) {
            intent?.removeExtra("start_vpn")
            setIntent(intent?.cloneFilter())
            val vpnIntent = android.net.VpnService.prepare(this)
            if (vpnIntent != null) {
                vpnPrepareLauncher.launch(vpnIntent)
            } else {
                connectViewModel.triggerStartTorAndVpn()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            unregisterReceiver(toinetServiceBroadcastReceiver)
        } catch (_: IllegalArgumentException) {}
    }

    private val toinetServiceBroadcastReceiver = object : BroadcastReceiver() {
        @SuppressLint("SetTextI18n")
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(TorService.EXTRA_STATUS)
            when (intent?.action) {
                ToInetConstants.LOCAL_ACTION_STATUS -> {
                    if (status != null) {
                        connectViewModel.updateState(this@ToInetActivity, status)
                        previousReceivedTorStatus = status
                    }
                }

                ToInetConstants.LOCAL_ACTION_LOG -> {
                    intent.getStringExtra(ToInetConstants.LOCAL_EXTRA_BOOTSTRAP_PERCENT)?.let {
                        connectViewModel.updateBootstrapPercent(it.toIntOrNull() ?: 0)
                    }
                    intent.getStringExtra(ToInetConstants.LOCAL_EXTRA_LOG)?.let {
                        logBottomSheet.appendLog(it)
                        connectViewModel.updateLogState(it)
                    }
                }

                ToInetConstants.LOCAL_ACTION_PORTS -> {
                    val socks = intent.getIntExtra(ToInetConstants.EXTRA_SOCKS_PROXY_PORT, -1)
                    val http = intent.getIntExtra(ToInetConstants.EXTRA_HTTP_PROXY_PORT, -1)
                    if (socks > 0) {
                        portSocks = socks
                        portHttp = http
                    }
                }

                ACTION_VPN_PROFILE_CHANGED -> {
                    connectViewModel.triggerRefreshMenuList()
                }
            }
        }
    }

    private fun promptDeviceAuthenticationIfRequired() {
        if (!Prefs.requireDeviceAuthentication)
            return

        if (!ToInetApp.shouldRequestAuthentication)
            return

        // if app was closed, we should re-request password upon
        // re-open, even if we've gotten it already
        ToInetApp.shouldRequestAuthentication = false

        if (ToInetApp.isAuthenticationPromptOpenLegacyFlag)
            return

        ToInetApp.isAuthenticationPromptOpenLegacyFlag = true

        rootLayout?.visibility = View.INVISIBLE
        DeviceAuthenticationPrompt.openPrompt(this, object :
            BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errorMsg: CharSequence) {
                ToInetApp.isAuthenticationPromptOpenLegacyFlag = false
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                    ToInetApp.resetLockFlags()
                    finish() // user presses back, just close
                } else if (errorCode == BiometricPrompt.ERROR_HW_UNAVAILABLE) {
                    // we set this flag when ToInet *can't* authenticate, ie no password or unsupported device
                    showToast(errorMsg) // String set in RequirePasswordPrompt.kt
                    rootLayout?.visibility = View.VISIBLE
                }
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                ToInetApp.shouldRequestAuthentication = false
                ToInetApp.isAuthenticationPromptOpenLegacyFlag = false
                rootLayout?.visibility = View.VISIBLE
            }

            override fun onAuthenticationFailed() {
                ToInetApp.resetLockFlags()
                finish()
            }
        })
    }

    companion object {
        private const val KEY_TOR_STATUS = "key_tor_status"

        // Make sure this is only shown once per app-start, not on every device rotation.
        private var rootDetectionShown = false

        fun resolveEffectiveStatus(
            context: Context,
            currentKnownStatus: String?,
            isViewModelStarting: Boolean = false
        ): String {
            if (Prefs.isGlobalVpnEnabled) {
                return when {
                    AppVpnService.isConnected -> TorService.STATUS_ON
                    isViewModelStarting ||
                        (AppVpnService.activeService != null && currentKnownStatus == TorService.STATUS_STARTING) ->
                        TorService.STATUS_STARTING
                    else -> TorService.STATUS_OFF
                }
            }

            if (context.isAnyStandaloneServiceRunning()) {
                return TorService.STATUS_ON
            }

            val hasPendingStandalone = isViewModelStarting || (
                currentKnownStatus == TorService.STATUS_STARTING && hasAnyLiveStandaloneService(context)
                )

            return if (hasPendingStandalone) TorService.STATUS_STARTING else TorService.STATUS_OFF
        }

        private fun hasAnyLiveStandaloneService(context: Context): Boolean {
            return ByeDpiProxyService.status != ServiceStatus.Disconnected ||
                EasyVpnService.activeService != null ||
                OperaProxyService.activeService != null ||
                SvpnService.activeService != null ||
                TgwsService.activeService != null ||
                (Prefs.isProviderActive("tor") && context.isServiceRunningInPackage(ToInetService::class.java))
        }
    }

    fun showLog() {
        if (!logBottomSheet.isAdded) {
            logBottomSheet.show(supportFragmentManager, ToInetActivity::class.java.simpleName)
        }
    }
}
