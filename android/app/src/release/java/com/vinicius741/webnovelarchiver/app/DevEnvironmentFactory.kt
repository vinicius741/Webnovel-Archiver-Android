package com.vinicius741.webnovelarchiver.app

import android.content.Context

internal object DevEnvironmentFactory {
    fun requested(context: Context): Boolean = false

    fun create(context: Context): DevEnvironment? = null
}
