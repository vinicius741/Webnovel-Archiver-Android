package com.vinicius741.webnovelarchiver.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubProgressTest {
    @Test
    fun storesTypedVolumeProgress() {
        assertEquals(EpubProgress(completed = 2, total = 5), EpubProgress(2, 5))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsProgressOutsideTotal() {
        EpubProgress(completed = 6, total = 5)
    }
}
