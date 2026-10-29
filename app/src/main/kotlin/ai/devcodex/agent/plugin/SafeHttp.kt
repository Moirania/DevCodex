package ai.devcodex.agent.plugin

import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * SSRF 防护：在每一跳（含重定向）实际建立连接后检查目标 IP，
 * 拒绝回环、本机、链路本地、内网、CGNAT 与组播地址。
 * 检查连接后的真实地址，因此 DNS 重绑定与 IP 字面量同样会被拦截。
 */
object SafeHttp {
    fun wrap(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .addNetworkInterceptor { chain ->
            val addr = chain.connection()?.route()?.socketAddress?.address
            if (addr != null && isBlocked(addr)) {
                throw IOException("已拒绝访问本机或内网地址")
            }
            chain.proceed(chain.request())
        }
        .build()

    fun isBlocked(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
        val b = a.address
        return when (a) {
            is Inet4Address -> {
                val o0 = b[0].toInt() and 0xFF
                val o1 = b[1].toInt() and 0xFF
                // 100.64.0.0/10 CGNAT；0.0.0.0/8
                (o0 == 100 && o1 in 64..127) || o0 == 0
            }
            is Inet6Address -> (b[0].toInt() and 0xFE) == 0xFC // fc00::/7 唯一本地地址
            else -> false
        }
    }
}
