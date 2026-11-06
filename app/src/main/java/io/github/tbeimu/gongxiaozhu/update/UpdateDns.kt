package io.github.tbeimu.gongxiaozhu.update

import java.net.InetAddress

/** Resolution must suspend without blocking and release its query when cancelled. */
fun interface UpdateDns {
    suspend fun lookup(hostname: String): List<InetAddress>
}
