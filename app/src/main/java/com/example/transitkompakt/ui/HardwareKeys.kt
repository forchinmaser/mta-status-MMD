package com.example.transitkompakt.ui

import android.util.Log
import android.view.KeyEvent

/**
 * Hardware-key paging for the stop list.
 *
 * The Kompakt's keypad map lives in the MuditaOS kernel tree
 * (mudita/MuditaOS-K-Kernel-opensource — MediaTek keypad driver + device tree),
 * not in any public Android SDK constant. Rather than guess, this accepts every
 * key a candy-bar / E Ink handset plausibly uses for paging and logs anything
 * else it sees under the tag below.
 *
 * To pin the real codes: run the app, open a route, press the key you want, then
 *
 *     adb logcat -s KompaktKeys
 *
 * ...and add the reported keyCode to PAGE_DOWN_KEYS / PAGE_UP_KEYS.
 * (`adb shell getevent -l` shows the raw kernel scancodes too.)
 */
object HardwareKeys {

    private const val TAG = "KompaktKeys"

    private val PAGE_DOWN_KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_SOFT_RIGHT,
        KeyEvent.KEYCODE_9
    )

    private val PAGE_UP_KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_SOFT_LEFT,
        KeyEvent.KEYCODE_3
    )

    /** Set by whichever paged list is on screen; null everywhere else. */
    var onPage: ((Int) -> Unit)? = null

    fun handle(keyCode: Int, event: KeyEvent?): Boolean {
        val pager = onPage
        val dir = when (keyCode) {
            in PAGE_DOWN_KEYS -> 1
            in PAGE_UP_KEYS -> -1
            else -> {
                Log.d(
                    TAG,
                    "unmapped keyCode=$keyCode (${KeyEvent.keyCodeToString(keyCode)}) " +
                        "scanCode=${event?.scanCode}"
                )
                return false
            }
        }
        if (pager == null) return false
        pager(dir)
        return true // consumed: no volume UI, no system beep, no extra panel redraw
    }
}
