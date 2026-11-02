package com.example.atvremote.bt

import com.example.atvremote.net.KeyCode

object HidReport {

    const val ID_KEYBOARD = 1
    const val ID_CONSUMER = 2

    val DESCRIPTOR: ByteArray = intArrayOf(
        0x05, 0x0C,
        0x09, 0x01,
        0xA1, 0x01,
        0x85, ID_CONSUMER,
        0x19, 0x00,
        0x2A, 0xFF, 0x03,
        0x75, 0x10,
        0x95, 0x01,
        0x15, 0x00,
        0x26, 0xFF, 0x03,
        0x81, 0x00,
        0xC0,

        0x05, 0x01,
        0x09, 0x06,
        0xA1, 0x01,
        0x85, ID_KEYBOARD,
        0x05, 0x07,
        0x19, 0xE0,
        0x29, 0xE7,
        0x15, 0x00,
        0x25, 0x01,
        0x75, 0x01,
        0x95, 0x08,
        0x81, 0x02,
        0x75, 0x08,
        0x95, 0x01,
        0x15, 0x00,
        0x26, 0xFF, 0x00,
        0x05, 0x07,
        0x19, 0x00,
        0x29, 0xFF,
        0x81, 0x00,
        0xC0
    ).map { it.toByte() }.toByteArray()

    sealed class Mapped {
        data class Key(val usage: Int) : Mapped()
        data class Consumer(val usage: Int) : Mapped()
    }

    fun map(keyCode: Int): Mapped? = when (keyCode) {
        KeyCode.DPAD_UP -> Mapped.Key(0x52)
        KeyCode.DPAD_DOWN -> Mapped.Key(0x51)
        KeyCode.DPAD_LEFT -> Mapped.Key(0x50)
        KeyCode.DPAD_RIGHT -> Mapped.Key(0x4F)
        KeyCode.DPAD_CENTER, KeyCode.ENTER -> Mapped.Key(0x28)
        KeyCode.BACK -> Mapped.Consumer(0x224)
        KeyCode.HOME -> Mapped.Consumer(0x223)
        KeyCode.MENU -> Mapped.Consumer(0x040)
        KeyCode.SETTINGS -> Mapped.Consumer(0x183)

        KeyCode.VOLUME_UP -> Mapped.Consumer(0x0E9)
        KeyCode.VOLUME_DOWN -> Mapped.Consumer(0x0EA)
        KeyCode.VOLUME_MUTE -> Mapped.Consumer(0x0E2)
        KeyCode.POWER -> Mapped.Consumer(0x030)
        KeyCode.CHANNEL_UP -> Mapped.Consumer(0x09C)
        KeyCode.CHANNEL_DOWN -> Mapped.Consumer(0x09D)

        KeyCode.DEL -> Mapped.Key(0x2A)
        KeyCode.NUM_1 -> Mapped.Key(0x1E)
        KeyCode.NUM_2 -> Mapped.Key(0x1F)
        KeyCode.NUM_3 -> Mapped.Key(0x20)
        KeyCode.NUM_4 -> Mapped.Key(0x21)
        KeyCode.NUM_5 -> Mapped.Key(0x22)
        KeyCode.NUM_6 -> Mapped.Key(0x23)
        KeyCode.NUM_7 -> Mapped.Key(0x24)
        KeyCode.NUM_8 -> Mapped.Key(0x25)
        KeyCode.NUM_9 -> Mapped.Key(0x26)
        KeyCode.NUM_0 -> Mapped.Key(0x27)
        else -> null
    }

    fun keyboardReport(usage: Int): ByteArray =
        byteArrayOf(0, usage.toByte())

    fun consumerReport(usage: Int): ByteArray =
        byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0xFF).toByte())
}
