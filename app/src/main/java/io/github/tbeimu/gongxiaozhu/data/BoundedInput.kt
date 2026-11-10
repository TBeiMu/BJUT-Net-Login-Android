package io.github.tbeimu.gongxiaozhu.data

import java.io.InputStream
import java.io.ByteArrayOutputStream

internal fun InputStream.readBounded(maximum: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= maximum)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
