package com.vinicius741.webnovelarchiver.epub

import org.junit.Test

class EpubProgressTest {
    @Test(expected = IllegalArgumentException::class)
    fun rejectsProgressOutsideTotal() {
        EpubProgress(completed = 6, total = 5)
    }
}
