package com.vinicius741.webnovelarchiver.feature.browser

object BrowserImportPlanning {
    const val ACTION_IMPORT_CURRENT_URL =
        "com.vinicius741.webnovelarchiver.action.IMPORT_BROWSER_URL"
    const val IMPORT_REQUEST_CODE = 741

    /** Only accepts URLs delivered by our explicit Custom Tab import action. */
    fun importUrl(
        action: String?,
        dataString: String?,
    ): String? =
        dataString
            ?.takeIf { action == ACTION_IMPORT_CURRENT_URL }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
