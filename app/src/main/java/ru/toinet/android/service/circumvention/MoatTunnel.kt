package ru.toinet.android.service.circumvention

import IPtProxy.IPtProxy

enum class MoatTunnel {

    TOR_PROJECT,
    GUARDIAN_PROJECT;

    val baseUrl: String
        get() = when (this) {
            TOR_PROJECT -> "https://bridges.torproject.org/moat/circumvention/"
            GUARDIAN_PROJECT -> "https://tns1.bypasscensorship.org/moat/circumvention/"
        }

    val config: String
        get() = when (this) {
            TOR_PROJECT -> "url=https://1723079976.rsc.cdn77.org;front=www.phpmyadmin.net"
            GUARDIAN_PROJECT -> "doh=https://dns.google/dns-query;pubkey=c07ae9dd7b86ded6121c3db173de048bfd4f41de38dd430e3dfaf83ec8f36a06;domain=t1.bypasscensorship.org"
        }

    private val methodName: String
        get() = when (this) {
            TOR_PROJECT -> IPtProxy.MeekLite
            GUARDIAN_PROJECT -> IPtProxy.Dnstt
        }

    val port: Int
        get() = Transport.controller.port(methodName).toInt()


    fun start() {
        Transport.controller.start(methodName, null)
    }

    fun stop() {
        Transport.controller.stop(methodName)
    }
}