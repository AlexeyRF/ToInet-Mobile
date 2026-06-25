package ru.toinet.android.ui.connect

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.freehaven.tor.control.TorControlCommands
import ru.toinet.android.OrbotActivity
import ru.toinet.android.R
import ru.toinet.android.util.sendIntentToService
import ru.toinet.android.databinding.FragmentConnectBinding
import ru.toinet.android.service.OrbotConstants
import ru.toinet.android.service.OrbotService
import ru.toinet.android.service.circumvention.Transport
import ru.toinet.android.util.Prefs
import ru.toinet.android.ui.OrbotMenuAction
import org.torproject.jni.TorService

private const val DEFAULT_THROTTLE_INTERVAL = 4000L

class ConnectFragment : Fragment(),
    ExitNodeBottomSheet.ExitNodeSelectedCallback {

    private lateinit var binding: FragmentConnectBinding

    val viewModel: ConnectViewModel by activityViewModels()

    private val lastStatus: String
        get() = (activity as? OrbotActivity)?.previousReceivedTorStatus ?: ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (state == ConnectUiState.NoInternet)
                        binding.switchConnect.visibility = View.GONE
                    else binding.switchConnect.visibility = View.VISIBLE
                    when (state) {
                        is ConnectUiState.NoInternet -> doLayoutNoInternet()
                        is ConnectUiState.Off -> doLayoutOff()
                        is ConnectUiState.Starting -> {
                            binding.switchConnect.isChecked = true
                            doLayoutStarting(requireContext())
                            state.bootstrapPercent?.let {
                                binding.progressBar.progress = it
                            }
                        }

                        is ConnectUiState.On -> {
                            binding.switchConnect.isChecked = true
                            lastState = TorService.ACTION_START
                            doLayoutOn(requireContext())
                        }

                        is ConnectUiState.Stopping -> {}
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is ConnectEvent.StartTorAndVpn -> attemptToStartTor()
                    is ConnectEvent.RefreshMenuList -> refreshMenuList(requireContext())
                }
            }
        }

        binding.switchConnect.setOnClickListener {
            it.isEnabled = false
            it.alpha = 0.38f
            binding.switchConnect.text = context?.getString(R.string.loading)
            it.postDelayed({
                it.isEnabled = true
                it.alpha = 1f
                binding.switchConnect.text = context?.getString(R.string.connect)
            }, DEFAULT_THROTTLE_INTERVAL)
        }
        binding.switchConnect.setOnCheckedChangeListener { _, value ->
            if (value) {
                // display msg if optional outbound proxy config is invalid
                if (Prefs.outboundProxy.second != null) {
                    Toast.makeText(
                        activity,
                        getString(R.string.invalid_outbound_proxy_config),
                        Toast.LENGTH_LONG
                    ).show()
                }
                attemptToStartTor()
            } else {
                stopTorAndVpn()
            }
        }
        refreshMenuList(requireContext())
    }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = FragmentConnectBinding.inflate(inflater, container, false)
        viewModel.updateState(requireContext(), lastStatus)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        viewModel.updateState(requireContext(), lastStatus)
    }

    fun stopTorAndVpn() {
        doLayoutOff()
        setState(TorService.ACTION_STOP)
    }

    private fun stopAnimations() {
    }

    private fun sendNewnymSignal() {
        requireContext().sendIntentToService(TorControlCommands.SIGNAL_NEWNYM)

        lifecycleScope.launch(Dispatchers.Main) {
            delay(600)
        }
    }

    fun attemptToStartTorPowerUserMode() {
        val alarmManager =
            requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            PowerUserForegroundPermDialog().createTransactionAndShow(requireActivity())
            return // user can try again after granting permission
        }
        doLayoutStarting(requireContext())
        setState(TorService.ACTION_START)
    }

    fun attemptToStartTor() {
        val provider = Prefs.vpnProvider
        val isProviderActive = when(provider) {
            "byedpi" -> Prefs.byedpiEnabled
            "tor" -> Prefs.torEnabled
            "tgws" -> Prefs.tgwsEnabled
            "rehab" -> Prefs.rehabilitatorEnabled
            "turnproxy" -> Prefs.turnProxyEnabled
            "fakevpn" -> true
            else -> false
        }
        if (Prefs.isGlobalVpnEnabled && !isProviderActive) {
            Toast.makeText(context, "Внимание: выбранный провайдер VPN ($provider) не активен!", Toast.LENGTH_LONG).show()
        }

        if (Prefs.isGlobalVpnEnabled) {
            val intent = VpnService.prepare(requireContext())
            if (intent != null) {
                requireActivity().startActivityForResult(intent, OrbotActivity.REQUEST_CODE_VPN)
                return
            }
        }
        attemptToStartTorPowerUserMode()
    }

    var lastState: String? = null

    @Synchronized
    fun setState(newState: String) {
        if (lastState != newState) {
            requireContext().sendIntentToService(newState)
            lastState = newState
        }
    }

    fun refreshMenuList(context: Context) {
        val listItems = arrayListOf(
            OrbotMenuAction(
                0, // No specific string resource, we'll use statusString
                R.drawable.ic_settings_gear,
                statusString = if (Prefs.torEnabled) "Конфигурация Tor (мосты и прокси)" else "Конфигурация Tor: ВЫКЛ"
            ) {
                TorConfigBottomSheet().show(
                    requireActivity().supportFragmentManager,
                    TorConfigBottomSheet.TAG
                )
            },
            OrbotMenuAction(
                0,
                R.drawable.ic_vpn_key,
                statusString = "VPN: ${if (Prefs.isGlobalVpnEnabled) "Вкл" else "Выкл"}\n(${Prefs.vpnProvider})"
            ) {
                VpnBottomSheet().show(
                    requireActivity().supportFragmentManager,
                    VpnBottomSheet.TAG
                )
            },
            OrbotMenuAction(
                0,
                R.drawable.ic_server,
                statusString = "ByeDPI: ${if (Prefs.byedpiEnabled) "ВКЛ" else "ВЫКЛ"}\n(прокси)"
            ) {
                ByeDpiBottomSheet().show(
                    requireActivity().supportFragmentManager,
                    ByeDpiBottomSheet.TAG
                )
            },
            OrbotMenuAction(
                0,
                R.drawable.ic_send_airplane,
                statusString = "TGWS: ${if (Prefs.tgwsEnabled) "ВКЛ" else "ВЫКЛ"}\n(порт, маппинги)"
            ) {
                TgwsBottomSheet().show(
                    requireActivity().supportFragmentManager,
                    TgwsBottomSheet.TAG
                )
            },
            OrbotMenuAction(
                0,
                R.drawable.ic_add_plus,
                statusString = "SocksRehabilitator: ${if (Prefs.rehabilitatorEnabled) "Вкл" else "Выкл"}\n(SOCKS5 через ByeDPI)"
            ) {
                RehabilitatorBottomSheet().show(
                    requireActivity().supportFragmentManager,
                    RehabilitatorBottomSheet.TAG
                )
            },
            OrbotMenuAction(
                0,
                R.drawable.ic_flight_off,
                statusString = "TurnProxy: ${if (Prefs.turnProxyEnabled) "Вкл" else "Выкл"}\n(Обход по TURN)"
            ) {
                TurnProxyBottomSheet().show(
                    requireActivity().supportFragmentManager,
                    TurnProxyBottomSheet.TAG
                )
            },
            OrbotMenuAction(R.string.btn_change_exit, 0) {
                ExitNodeBottomSheet().show(
                    requireActivity().supportFragmentManager,
                    "ExitNodeBottomSheet"
                )
            }
        )
        binding.lvConnected.adapter = ConnectMenuActionAdapter(context, listItems)
    }


    private fun doLayoutNoInternet() {

        stopAnimations()

        binding.progressBar.visibility = View.INVISIBLE
        binding.tvTitle.text = getString(R.string.no_internet_title)

        binding.lvConnected.visibility = View.VISIBLE
    }

    fun doLayoutOn(context: Context) {
        if (Prefs.smartConnect) {
            Prefs.smartConnect = false
            refreshMenuList(context)
        }
        binding.progressBar.visibility = View.INVISIBLE
        binding.tvTitle.text = context.getString(R.string.connected_title)
        binding.lvConnected.visibility = View.VISIBLE

        refreshMenuList(context)

    }

    fun doLayoutOff() {
        refreshMenuList(requireContext())
        stopAnimations()
        binding.progressBar.visibility = View.INVISIBLE
        binding.lvConnected.visibility = View.VISIBLE
        binding.tvTitle.text = getString(R.string.secure_your_connection_title)
    }

    fun doLayoutStarting(context: Context) {
        with(binding.progressBar) {
            progress = 0
            visibility = View.VISIBLE
        }

        val animHover = AnimationUtils.loadAnimation(context, R.anim.hover)
        animHover.repeatMode = Animation.REVERSE
        animHover.start()

        val animShadow = AnimationUtils.loadAnimation(context, R.anim.shadow)
        animShadow.repeatMode = Animation.REVERSE
        animShadow.start()
        binding.tvTitle.text = context.getString(R.string.trying_to_connect_title)
    }


    private fun openConfigureTorConnection() {
        ConfigConnectionBottomSheet()
            .show(requireActivity().supportFragmentManager, "ConfigConnectionBttmSheet") // Используем строковое значение вместо TAG
    }

    override fun onExitNodeSelected(countryCode: String) {

        //tor format expects "{" for country code
        Prefs.exitNodes = "{$countryCode}"

        requireContext().sendIntentToService(
            Intent(requireActivity(), OrbotService::class.java)
                .setAction(OrbotConstants.CMD_SET_EXIT).putExtra("exit", countryCode)
        )

        refreshMenuList(requireContext())
    }
}