package com.example.atvremote.net

interface RemoteTransport {
    fun sendKey(keyCode: Int, direction: Int = Direction.SHORT)

    fun close()

    var onDisconnect: (() -> Unit)?
}
