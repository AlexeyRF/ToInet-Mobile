package ru.toinet.android.vpn.data

const val STARTED_BROADCAST = "ru.toinet.android.byedpi.STARTED"
const val STOPPED_BROADCAST = "ru.toinet.android.byedpi.STOPPED"
const val FAILED_BROADCAST = "ru.toinet.android.byedpi.FAILED"

const val SENDER = "sender"

enum class Sender(val senderName: String) {
    Proxy("Proxy"),
    VPN("VPN")
}

