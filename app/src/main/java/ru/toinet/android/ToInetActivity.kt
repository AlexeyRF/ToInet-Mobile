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
import ru.toinet.android.service.ToInetConstants
import ru.toinet.android.ui.connect.ConnectViewModel
import ru.toinet.android.ui.connect.RequestPostNotificationPermission
import ru.toinet.android.ui.core.BaseActivity
import ru.toinet.android.ui.core.DeviceAuthenticationPrompt
import ru.toinet.android.ui.more.LogBottomSheet
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.sendIntentToService
import ru.toinet.android.util.showToast
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

        // programmatically set title to "ToInet" since camo mode will overwrite it here from manifest
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
            addAction(ru.toinet.android.turnproxy.TurnProxyService.ACTION_CAPTCHA_REQUIRED)
            addAction(ru.toinet.android.turnproxy.TurnProxyService.ACTION_CAPTCHA_FINISHED)
        }

        ContextCompat.registerReceiver(this, toinetServiceBroadcastReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED)

        requestNotificationPermission()

        Prefs.initWeeklyWorker(this)

        if (Prefs.tgwsEnabled) {
            ru.toinet.android.tgws.TgwsService.start(
                this,
                Prefs.tgwsHost,
                Prefs.tgwsPort,
                Prefs.tgwsDcMappings,
                Prefs.tgwsSecret,
                Prefs.tgwsFakeTls,
                false
            )
        }

        if (!rootDetectionShown && Prefs.detectRoot() && RootBeer(this).isRooted) {
            //we found indication of root
            applicationContext.showToast(getString(R.string.root_warning))

            rootDetectionShown = true
        }

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

    override fun onStart() {
        super.onStart()
        promptDeviceAuthenticationIfRequired()
    }

    override fun onResume() {
        super.onResume()
        sendIntentToService(ToInetConstants.CMD_ACTIVE)
    }

    override fun onDestroy() {
        super.onDestroy()

        unregisterReceiver(toinetServiceBroadcastReceiver)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_VPN && resultCode == RESULT_OK) {
            connectViewModel.triggerStartTorAndVpn()
        }
    }

    private val toinetServiceBroadcastReceiver = object : BroadcastReceiver() {
        @SuppressLint("SetTextI18n")
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(TorService.EXTRA_STATUS)
            when (intent?.action) {
                ToInetConstants.LOCAL_ACTION_STATUS -> {
                    if (status != previousReceivedTorStatus) {
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
                    if (http > 0 && socks > 0) {
                        portSocks = socks
                        portHttp = http
                    }
                }

                ru.toinet.android.turnproxy.TurnProxyService.ACTION_CAPTCHA_REQUIRED -> {
                    val url = intent.getStringExtra(ru.toinet.android.turnproxy.TurnProxyService.EXTRA_URL) ?: return
                    showCaptchaDialog(url)
                }

                ru.toinet.android.turnproxy.TurnProxyService.ACTION_CAPTCHA_FINISHED -> {
                    captchaDialog?.dismiss()
                    captchaDialog = null
                }
            }
        }
    }

    private var captchaDialog: androidx.appcompat.app.AlertDialog? = null

    @SuppressLint("SetJavaScriptEnabled")
    private fun showCaptchaDialog(url: String) {
        if (captchaDialog?.isShowing == true) return

        val webView = android.webkit.WebView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            }
            webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                    val scheme = request?.url?.scheme?.lowercase()
                    return scheme != "http" && scheme != "https"
                }
            }
            loadUrl(url)
        }

        val title = "Требуется капча"
        val subtitle = "Пожалуйста, решите капчу для продолжения"

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val titleView = android.widget.TextView(this@ToInetActivity).apply {
                text = "$title\n$subtitle"
                setPadding(48, 48, 48, 24)
                textSize = 16f
                setTextColor(android.graphics.Color.BLACK)
            }
            addView(titleView)
            addView(webView)
        }

        captchaDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(container)
            .setPositiveButton("Закрыть") { dialog, _ ->
                dialog.dismiss()
                captchaDialog = null
            }
            .setOnDismissListener {
                webView.destroy()
            }
            .setCancelable(false)
            .show()
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
        const val REQUEST_CODE_VPN = 1234

        // Make sure this is only shown once per app-start, not on every device rotation.
        private var rootDetectionShown = false
    }

    fun showLog() {
        if (!logBottomSheet.isAdded) {
            logBottomSheet.show(supportFragmentManager, ToInetActivity::class.java.simpleName)
        }
    }
}
