@file:Suppress("FunctionName", "FunctionName", "FunctionName")

package ru.toinet.android.service

@Suppress("LocalVariableName")
object TProxyService {
    const val VIRTUAL_GATEWAY_IPV4: String = "198.18.0.1"
    const val VIRTUAL_GATEWAY_IPV6: String = "fc00::1"
    const val FAKE_DNS: String = "198.18.0.2"

    const val TASK_SIZE: Int = 81920
    const val TUNNEL_MTU: Int = 8500
}
