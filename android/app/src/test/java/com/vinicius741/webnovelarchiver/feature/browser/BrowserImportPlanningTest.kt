package com.vinicius741.webnovelarchiver.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserImportPlanningTest {
    @Test
    fun importUrlAcceptsCustomTabActionUrl() {
        assertEquals(
            "https://www.royalroad.com/fiction/123/story",
            BrowserImportPlanning.importUrl(
                BrowserImportPlanning.ACTION_IMPORT_CURRENT_URL,
                " https://www.royalroad.com/fiction/123/story ",
            ),
        )
    }

    @Test
    fun importUrlRejectsOtherActionsAndMissingUrls() {
        assertNull(BrowserImportPlanning.importUrl(IntentActions.VIEW, "https://www.royalroad.com/fiction/123/story"))
        assertNull(BrowserImportPlanning.importUrl(BrowserImportPlanning.ACTION_IMPORT_CURRENT_URL, null))
        assertNull(BrowserImportPlanning.importUrl(BrowserImportPlanning.ACTION_IMPORT_CURRENT_URL, "  "))
    }

    private object IntentActions {
        const val VIEW = "android.intent.action.VIEW"
    }
}
