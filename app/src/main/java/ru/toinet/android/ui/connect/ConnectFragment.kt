package ru.toinet.android.ui.connect

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import ru.toinet.android.util.ArchHelper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import ru.toinet.android.ToInetActivity
import ru.toinet.android.R
import ru.toinet.android.util.sendIntentToService
import ru.toinet.android.databinding.FragmentConnectBinding
import ru.toinet.android.service.ToInetConstants
import ru.toinet.android.service.ToInetService
import ru.toinet.android.service.circumvention.Transport
import ru.toinet.android.util.Prefs
import org.torproject.jni.TorService

private const val DEFAULT_THROTTLE_INTERVAL = 4000L

class ConnectFragment : Fragment(),
    ExitNodeBottomSheet.ExitNodeSelectedCallback {

    private lateinit var binding: FragmentConnectBinding

    val viewModel: ConnectViewModel by activityViewModels()

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            attemptToStartTorPowerUserMode()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (state == ConnectUiState.NoInternet)
                        binding.btnConnectCard.visibility = View.GONE
                    else binding.btnConnectCard.visibility = View.VISIBLE
                    when (state) {
                        is ConnectUiState.NoInternet -> doLayoutNoInternet()
                        is ConnectUiState.Off -> doLayoutOff()
                        is ConnectUiState.Starting -> {
                            doLayoutStarting(requireContext())
                            if (state.bootstrapPercent != null) {
                                binding.progressBar.isIndeterminate = false
                                binding.progressBar.progress = state.bootstrapPercent
                            } else {
                                binding.progressBar.isIndeterminate = true
                            }
                        }

                        is ConnectUiState.On -> {
                            lastState = TorService.ACTION_START
                            doLayoutOn(requireContext())
                        }

                        is ConnectUiState.Stopping -> {}
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is ConnectEvent.StartTorAndVpn -> attemptToStartTor()
                        is ConnectEvent.RefreshMenuList -> if (isAdded) refreshMenuList(requireContext())
                    }
                }
            }
        }

        binding.btnConnectCard.setOnClickListener {
            val isChecked = lastState == TorService.ACTION_START
            if (!isChecked) {
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
        
        binding.btnProvider.setOnClickListener {
            ProxifierRulesBottomSheet().show(requireActivity().supportFragmentManager, ProxifierRulesBottomSheet.TAG)
        }
        
        binding.btnSettings.setOnClickListener {
            SettingsBottomSheet().show(childFragmentManager, SettingsBottomSheet.TAG)
        }

        if (ArchHelper.isX86Family) {
            binding.tvArchWarning.visibility = View.VISIBLE
            binding.tvArchWarning.text = if (ArchHelper.isX86_64) {
                "x86-64: Не все функции поддерживаются. Для ПК используйте версию для Windows:\nhttps://github.com/AlexeyRF/ToInet"
            } else {
                "x86: Данная архитектура не поддерживает 100% функций, неподдерживаемые функции убраны из списка"
            }
            binding.tvArchWarning.setOnClickListener {
                if (ArchHelper.isX86_64) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/AlexeyRF/ToInet")))
                    } catch (_: Exception) {}
                }
            }
        }
        
        refreshMenuList(requireContext())
    }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = FragmentConnectBinding.inflate(inflater, container, false)
        syncConnectionState(requireContext())
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        syncConnectionState(requireContext())
        refreshMenuList(requireContext())
    }

    private fun syncConnectionState(context: Context) {
        val act = activity as? ToInetActivity
        val isViewModelStarting = viewModel.uiState.value is ConnectUiState.Starting
        val effectiveStatus = ToInetActivity.resolveEffectiveStatus(
            context,
            act?.previousReceivedTorStatus,
            isViewModelStarting
        )
        act?.previousReceivedTorStatus = effectiveStatus
        if (effectiveStatus != TorService.STATUS_STARTING || !isViewModelStarting) {
            viewModel.updateState(context, effectiveStatus)
        }
    }

    fun stopTorAndVpn() {
        (activity as? ToInetActivity)?.previousReceivedTorStatus = TorService.STATUS_OFF
        viewModel.updateState(requireContext(), TorService.STATUS_OFF)
        setState(TorService.ACTION_STOP)
        doLayoutOff()
    }

    fun attemptToStartTorPowerUserMode() {
        val alarmManager =
            requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val isVpnReady = Prefs.isGlobalVpnEnabled && VpnService.prepare(requireContext()) == null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms() && !isVpnReady) {
            PowerUserForegroundPermDialog().createTransactionAndShow(requireActivity())
            return // user can try again after granting permission
        }
        (activity as? ToInetActivity)?.previousReceivedTorStatus = TorService.STATUS_STARTING
        viewModel.updateState(requireContext(), TorService.STATUS_STARTING)
        doLayoutStarting(requireContext())
        setState(TorService.ACTION_START)
    }

    fun attemptToStartTor() {
        val provider = Prefs.vpnProvider
        val isProviderActive = Prefs.isProviderActive(provider)
        continueStartTor(provider, isProviderActive)
    }

    private fun continueStartTor(provider: String, isProviderActive: Boolean) {
        if (Prefs.isGlobalVpnEnabled && !isProviderActive) {
            Toast.makeText(context, "Внимание: выбранный провайдер VPN ($provider) не активен!", Toast.LENGTH_LONG).show()
        }

        if (Prefs.isGlobalVpnEnabled) {
            val intent = VpnService.prepare(requireContext())
            if (intent != null) {
                vpnPrepareLauncher.launch(intent)
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
        binding.btnProvider.text = if (Prefs.vpnSeparateNetworkProfiles) {
            if (Prefs.currentVpnNetworkType == Prefs.VPN_PROFILE_WIFI) {
                "VPN Правила (Wi-Fi)"
            } else {
                "VPN Правила (Моб.)"
            }
        } else {
            "VPN Правила"
        }
    }


    private fun doLayoutNoInternet() {
        binding.progressBar.visibility = View.INVISIBLE
        binding.tvTitle.text = getString(R.string.no_internet_title)
        
        binding.btnConnectCard.setCardBackgroundColor(android.graphics.Color.LTGRAY)
        binding.ivPowerIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.DKGRAY)
    }

    fun doLayoutOn(context: Context) {
        binding.progressBar.visibility = View.INVISIBLE
        binding.tvTitle.text = context.getString(R.string.connected_title)
        
        binding.btnConnectCard.setCardBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
        binding.ivPowerIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
    }

    fun doLayoutOff() {
        lastState = TorService.ACTION_STOP
        binding.progressBar.visibility = View.INVISIBLE
        binding.tvTitle.text = getString(R.string.secure_your_connection_title)
        
        binding.btnConnectCard.setCardBackgroundColor(android.graphics.Color.parseColor("#424242"))
        binding.ivPowerIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
    }

    fun doLayoutStarting(context: Context) {
        with(binding.progressBar) {
            visibility = View.VISIBLE
            // LinearProgressIndicator doesn't need progress reset if it's indeterminate
        }
        binding.tvTitle.text = context.getString(R.string.trying_to_connect_title)
    }

    override fun onExitNodeSelected(countryCode: String) {

        //tor format expects "{" for country code
        Prefs.exitNodes = "{$countryCode}"

        requireContext().sendIntentToService(
            Intent(requireActivity(), ToInetService::class.java)
                .setAction(ToInetConstants.CMD_SET_EXIT).putExtra("exit", countryCode),
            isForeground = false
        )

        refreshMenuList(requireContext())
    }
}
