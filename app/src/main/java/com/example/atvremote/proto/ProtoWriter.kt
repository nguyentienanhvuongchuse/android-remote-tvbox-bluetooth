package com.example.atvremote.proto

object ProtoWriter {

    fun varint(value: Long): ByteArray {
        val out = ArrayList<Byte>()
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) {
                out.add((b or 0x80).toByte())
            } else {
                out.add(b.toByte())
                break
            }
        }
        return out.toByteArray()
    }

    private fun tag(fieldNumber: Int, wireType: Int): ByteArray =
        varint(((fieldNumber.toLong() shl 3) or wireType.toLong()))

    fun varintField(fieldNumber: Int, value: Long): ByteArray =
        tag(fieldNumber, 0) + varint(value)

    fun lengthDelimitedField(fieldNumber: Int, content: ByteArray): ByteArray =
        tag(fieldNumber, 2) + varint(content.size.toLong()) + content

    fun stringField(fieldNumber: Int, value: String): ByteArray =
        lengthDelimitedField(fieldNumber, value.toByteArray(Charsets.UTF_8))

    fun bytesField(fieldNumber: Int, value: ByteArray): ByteArray =
        lengthDelimitedField(fieldNumber, value)

    fun framed(body: ByteArray): ByteArray = varint(body.size.toLong()) + body
}
