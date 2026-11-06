package com.nova5.xboxcontrol

import java.util.concurrent.ConcurrentHashMap

/**
 * Latest controller state, written by the UI thread (MainActivity) and read by
 * the teleop thread. Stick axes use Android convention: up = negative Y.
 */
class PadState {
    @Volatile var lx = 0f
    @Volatile var ly = 0f
    @Volatile var rx = 0f
    @Volatile var ry = 0f
    @Volatile var lt = 0f   // 0 at rest, 1 fully pulled
    @Volatile var rt = 0f
    @Volatile var hatX = 0f // right = +1
    @Volatile var hatY = 0f // Android: up = -1, down = +1

    /** Currently held gamepad key codes (KeyEvent.KEYCODE_BUTTON_*, DPAD_*). */
    val keys: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** Zero everything. Called on pause / controller removal so any jog stops. */
    fun reset() {
        lx = 0f; ly = 0f; rx = 0f; ry = 0f
        lt = 0f; rt = 0f; hatX = 0f; hatY = 0f
        keys.clear()
    }
}
