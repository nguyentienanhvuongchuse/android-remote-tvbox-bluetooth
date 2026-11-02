package com.example.atvremote.proto

import java.io.InputStream

object ProtoReader {

    fun readVarint(input: InputStream): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = input.read()
            if (b == -1) throw java.io.EOFException("Stream closed while reading varint")
            result = result or ((b.toLong() and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return result
    }

    fun readFramedMessage(input: InputStream): ByteArray {
        val len = readVarint(input).toInt()
        val buf = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val n = input.read(buf, offset, len - offset)
            if (n == -1) throw java.io.EOFException("Stream closed while reading message body")
            offset += n
        }
        return buf
    }

    data class Field(val number: Int, val wireType: Int, val varintValue: Long, val bytes: ByteArray?)

    fun parseFields(body: ByteArray): List<Field> {
        val fields = ArrayList<Field>()
        var i = 0
        fun readVarintAt(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = body[i++].toInt() and 0xFF
                result = result or ((b.toLong() and 0x7F) shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
            }
            return result
        }
        while (i < body.size) {
            val key = readVarintAt()
            val fieldNumber = (key shr 3).toInt()
            val wireType = (key and 0x7).toInt()
            when (wireType) {
                0 -> {
                    val v = readVarintAt()
                    fields.add(Field(fieldNumber, wireType, v, null))
                }
                2 -> {
                    val len = readVarintAt().toInt()
                    val bytes = body.copyOfRange(i, i + len)
                    i += len
                    fields.add(Field(fieldNumber, wireType, 0, bytes))
                }
                else -> {
                    return fields
                }
            }
        }
        return fields
    }
}
