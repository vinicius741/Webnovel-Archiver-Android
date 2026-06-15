package com.vinicius741.webnovelarchiver.ui

import android.content.Context

/* ------------------------------------------------------------------ */
/* Density + type-scale helpers                                       */
/* ------------------------------------------------------------------ */

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
fun Context.sp(value: Int): Float = value * resources.displayMetrics.scaledDensity

/**
 * Spacing scale (G1). Prefer these named tokens over raw `dp(n)` for the common gaps so paddings
 * stay consistent across screens. Existing call sites still pass raw ints; migrate incrementally.
 *   Spacing.XS=4, Spacing.SM=8, Spacing.MD=12, Spacing.LG=16, Spacing.XL=24
 *
 * Named `Spacing` (not `Space`) to avoid clashing with `android.widget.Space`.
 */
object Spacing {
    const val XS = 4
    const val SM = 8
    const val MD = 12
    const val LG = 16
    const val XL = 24
}

/** Back-compat alias; prefer `Spacing`. Kept so older call sites keep compiling during the migration. */
object Space {
    const val XS = Spacing.XS
    const val SM = Spacing.SM
    const val MD = Spacing.MD
    const val LG = Spacing.LG
    const val XL = Spacing.XL
}

/** MD3 type scale tokens used across the app. */
enum class Type {
    HEADLINE, TITLE_LARGE, TITLE_MEDIUM, TITLE_SMALL,
    BODY_LARGE, BODY_MEDIUM, BODY_SMALL,
    LABEL_LARGE, LABEL_MEDIUM, LABEL_SMALL, CAPTION,
}

// Internal (not private) so the view builders in other files of this package can read the
// type scale. Kotlin `private` at top level is file-private, which would hide these once the
// UI builders were split out of Ui.kt.
internal fun Type.size(): Float = when (this) {
    Type.HEADLINE -> 24f
    Type.TITLE_LARGE -> 22f
    Type.TITLE_MEDIUM -> 16f
    Type.TITLE_SMALL -> 14f
    Type.BODY_LARGE -> 16f
    Type.BODY_MEDIUM -> 14f
    Type.BODY_SMALL -> 12f
    Type.LABEL_LARGE -> 14f
    Type.LABEL_MEDIUM -> 12f
    Type.LABEL_SMALL -> 11f
    Type.CAPTION -> 11f
}

internal fun Type.bold(): Boolean = when (this) {
    Type.HEADLINE, Type.TITLE_LARGE, Type.TITLE_MEDIUM, Type.TITLE_SMALL,
    Type.LABEL_LARGE, Type.LABEL_MEDIUM -> true
    else -> false
}
