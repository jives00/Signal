package com.signal.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class DesignCanvasTest {
    @Test
    fun oneDesignUnitIsOne1080pPixel() {
        assertEquals(1f, designDensity(1920).density)
    }

    @Test
    fun a4kScreenDoublesTheUnitWithoutAddingRoom() {
        assertEquals(2f, designDensity(3840).density)
    }

    @Test
    fun theDevHarnessDefaultWindowScalesDown() {
        assertEquals(1280f / 1920f, designDensity(1280).density)
    }

    @Test
    fun fontScaleIsIgnoredSoTextFollowsTheCanvas() {
        assertEquals(1f, designDensity(3840).fontScale)
    }
}
