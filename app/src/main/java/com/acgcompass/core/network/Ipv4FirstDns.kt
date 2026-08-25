package com.acgcompass.core.network

import java.net.Inet4Address
import java.net.InetAddress
import okhttp3.Dns

/**
 * 解析结果里把 IPv4 地址排在前面的 [Dns]。
 *
 * OkHttp 4.x 按解析顺序逐个尝试地址，没有浏览器那种 IPv4/IPv6 并行抢先（Happy Eyeballs）。
 * 当域名同时有 A 与 AAAA 记录（VNDB 的 `api.vndb.org` / `t.vndb.org` 即如此）而本机 IPv6 出口
 * 不通或很慢时，每次新建连接都要先等满连接超时才回落到 IPv4——表现为「网页很快、应用里很慢」。
 * 这里只调整顺序、不丢弃地址：IPv4 失败仍会继续尝试 IPv6，纯 IPv6 网络不受影响。
 */
class Ipv4FirstDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> = // sortedBy 是稳定排序：同一族内保持系统解析器给出的顺序。
        delegate.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
}
