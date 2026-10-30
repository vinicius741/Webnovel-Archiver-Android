package com.vinicius741.webnovelarchiver.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.AppRouteCodec

/** Encoded [AppRoute] a notification tap opens (see [openAppPendingIntent]). */
private const val EXTRA_OPEN_ROUTE = "com.vinicius741.webnovelarchiver.OPEN_ROUTE"

/**
 * Notification tap action. Single-top + clear-top reuse the running [MainActivity] (a plain intent
 * would stack a second copy of the app, whose Back lands in the first), and [route], when given,
 * opens on top of whatever the user had open. [requestCode] must be unique per notification:
 * PendingIntents differing only in extras are otherwise the same one, and the last route wins.
 */
internal fun Context.openAppPendingIntent(
    requestCode: Int,
    route: AppRoute? = null,
): PendingIntent =
    PendingIntent.getActivity(
        this,
        requestCode,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { route?.let { putExtra(EXTRA_OPEN_ROUTE, AppRouteCodec.encode(it)) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

/** The route a notification tap asked for, if any. */
internal fun Intent.openRoute(): AppRoute? = getStringExtra(EXTRA_OPEN_ROUTE)?.let(AppRouteCodec::decode)
