package com.automusicshorts.acquisitionsmoke

import android.content.Context

/** Device variant has no adb manifest import behavior. */
internal object QaConnection {
    fun refreshPairing(context: Context): Boolean = false
}
