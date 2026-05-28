package ru.toinet.android.byedpi.services

import ru.toinet.android.byedpi.data.AppStatus
import ru.toinet.android.byedpi.data.Mode

var appStatus = AppStatus.Halted to Mode.VPN
    private set

fun setStatus(status: AppStatus, mode: Mode) {
    appStatus = status to mode
}
