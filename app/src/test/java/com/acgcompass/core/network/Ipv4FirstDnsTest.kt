package com.acgcompass.core.network

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import java.net.InetAddress
import okhttp3.Dns

class Ipv4FirstDnsTest : StringSpec({
    val v6a = InetAddress.getByName("2001:db8::1")
    val v6b = InetAddress.getByName("2001:db8::2")
    val v4a = InetAddress.getByName("192.0.2.1")
    val v4b = InetAddress.getByName("192.0.2.2")

    fun dnsReturning(vararg addresses: InetAddress) = Ipv4FirstDns(
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = addresses.toList()
        },
    )

    "IPv4 addresses are tried first and nothing is dropped" {
        dnsReturning(v6a, v4a, v6b, v4b).lookup("api.vndb.org") shouldContainExactly listOf(v4a, v4b, v6a, v6b)
    }

    "IPv6-only results are kept as they are" {
        dnsReturning(v6a, v6b).lookup("example.org") shouldContainExactly listOf(v6a, v6b)
    }
})
