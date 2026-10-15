package ru.toinet.android.vpn.services

import ru.toinet.android.vpn.data.AppStatus
import ru.toinet.android.vpn.data.Mode

var appStatus = AppStatus.Halted to Mode.VPN
    private set

fun setStatus(status: AppStatus, mode: Mode) {
    appStatus = status to mode
}

