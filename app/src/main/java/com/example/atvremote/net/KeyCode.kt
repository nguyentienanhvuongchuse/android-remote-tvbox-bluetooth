package com.example.atvremote.net

object KeyCode {
    const val HOME = 3
    const val BACK = 4

    const val NUM_0 = 7
    const val NUM_1 = 8
    const val NUM_2 = 9
    const val NUM_3 = 10
    const val NUM_4 = 11
    const val NUM_5 = 12
    const val NUM_6 = 13
    const val NUM_7 = 14
    const val NUM_8 = 15
    const val NUM_9 = 16

    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val DEL = 67
    const val ENTER = 66
    const val MENU = 82
    const val VOLUME_MUTE = 164
    const val CHANNEL_UP = 166
    const val CHANNEL_DOWN = 167
    const val SETTINGS = 176

    fun digit(d: Int): Int = NUM_0 + d
}

object Direction {
    const val START_LONG = 1
    const val END_LONG = 2
    const val SHORT = 3
}
