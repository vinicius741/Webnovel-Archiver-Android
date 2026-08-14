package com.vinicius741.webnovelarchiver.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DevRestorePlanningTest {
    @get:Rule
    val cache = TemporaryFolder()

    @Test
    fun nullOrBlankExtraResolvesToNull() {
        assertNull(DevRestorePlanning.resolveSandboxZipPath(cache.root, null))
        assertNull(DevRestorePlanning.resolveSandboxZipPath(cache.root, ""))
        assertNull(DevRestorePlanning.resolveSandboxZipPath(cache.root, "   "))
    }

    @Test
    fun absolutePathsAreRejected() {
        assertNull(DevRestorePlanning.resolveSandboxZipPath(cache.root, "/data/local/tmp/evil.zip"))
    }

    @Test
    fun traversalOutsideCacheIsRejected() {
        assertNull(DevRestorePlanning.resolveSandboxZipPath(cache.root, "../other.zip"))
        assertNull(DevRestorePlanning.resolveSandboxZipPath(cache.root, "nested/../../escape.zip"))
    }

    @Test
    fun relativeNamesResolveInsideCache() {
        val resolved = DevRestorePlanning.resolveSandboxZipPath(cache.root, "dev_restore_source.zip")
        assertNotNull(resolved)
        assertEquals(File(cache.root, "dev_restore_source.zip").canonicalPath, resolved!!.canonicalPath)

        val nested = DevRestorePlanning.resolveSandboxZipPath(cache.root, "dev/nested backup.zip")
        assertNotNull(nested)
        assertEquals(
            File(cache.root, "dev").canonicalPath + File.separator + "nested backup.zip",
            nested!!.canonicalPath,
        )
    }

    @Test
    fun cacheDirItselfIsRejected() {
        assertNull(DevRestorePlanning.resolveSandboxZipPath(cache.root, "."))
    }
}
