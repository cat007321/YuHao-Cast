package com.kimi.casttv.dlna

import java.nio.charset.Charset
import java.util.Date

/**
 * 苹果二进制 plist（bplist00）最小解析器。
 * 覆盖 AirPlay /play 请求体所需的类型：int/real/bool/data/ascii/unicode string/array/dict。
 * 解析失败返回 null（调用方按不支持处理）。
 */
object Bplist {

    fun parse(bytes: ByteArray): Any? = runCatching { Parser(bytes).parseTop() }.getOrNull()

    private class Parser(private val data: ByteArray) {
        private var offsetTableOffset = 0L
        private var offsetIntSize = 0
        private var objectRefSize = 0
        private var numObjects = 0L
        private var topObject = 0L

        init {
            require(data.size > 40)
            require(data[0] == 'b'.code.toByte() && data[1] == 'p'.code.toByte())
            val trailer = data.size - 32
            offsetIntSize = data[trailer + 6].toInt()
            objectRefSize = data[trailer + 7].toInt()
            numObjects = readLong(trailer + 8, 8)
            topObject = readLong(trailer + 16, 8)
            offsetTableOffset = readLong(trailer + 24, 8)
            require(topObject < numObjects && offsetTableOffset > 0)
        }

        fun parseTop(): Any? = parseObject(topObject)

        private fun readLong(off: Int, len: Int): Long {
            var v = 0L
            for (i in 0 until len) v = (v shl 8) or (data[off + i].toLong() and 0xFF)
            return v
        }

        private fun objectOffset(index: Long): Int {
            val off = (offsetTableOffset + index * offsetIntSize).toInt()
            return readLong(off, offsetIntSize).toInt()
        }

        /** 返回 (长度, 长度前缀消耗字节数) */
        private fun readLength(p: Int, nibble: Int): Pair<Long, Int> =
            if (nibble < 0x0F) nibble.toLong() to 0
            else {
                val size = 1 shl (data[p].toInt() and 0x0F)
                readLong(p + 1, size) to (1 + size)
            }

        private fun readRef(p: Int, idx: Int): Long = readLong(p + idx * objectRefSize, objectRefSize)

        private fun parseObject(index: Long): Any? {
            var p = objectOffset(index)
            val marker = data[p++].toInt() and 0xFF
            val kind = marker shr 4
            val nibble = marker and 0x0F
            return when (kind) {
                0x0 -> when (nibble) { 0x8 -> false; 0x9 -> true; else -> null }
                0x1 -> readLong(p, 1 shl nibble)
                0x2 -> {
                    val size = 1 shl nibble
                    val bits = readLong(p, size)
                    if (size == 4) java.lang.Float.intBitsToFloat(bits.toInt()).toDouble()
                    else java.lang.Double.longBitsToDouble(bits)
                }
                0x3 -> Date((978307200L + java.lang.Double.longBitsToDouble(readLong(p, 8)).toLong()) * 1000)
                0x4 -> {
                    val (len, consumed) = readLength(p, nibble); p += consumed
                    data.copyOfRange(p, (p + len.toInt()).coerceAtMost(data.size))
                }
                0x5 -> {
                    val (len, consumed) = readLength(p, nibble); p += consumed
                    String(data, p, len.toInt(), Charsets.US_ASCII)
                }
                0x6 -> {
                    val (len, consumed) = readLength(p, nibble); p += consumed
                    String(data, p, len.toInt() * 2, Charset.forName("UTF-16BE"))
                }
                0x8 -> readLong(p, nibble + 1)
                0xA -> {
                    val (count, consumed) = readLength(p, nibble); p += consumed
                    (0 until count.toInt()).map { parseObject(readRef(p, it)) }
                }
                0xD -> {
                    val (count, consumed) = readLength(p, nibble); p += consumed
                    val n = count.toInt()
                    val keys = (0 until n).map { parseObject(readRef(p, it)) }
                    val values = (0 until n).map { parseObject(readRef(p, n + it)) }
                    (0 until n).associate { (keys[it] ?: "key$it").toString() to values[it] }
                }
                else -> null
            }
        }
    }
}
