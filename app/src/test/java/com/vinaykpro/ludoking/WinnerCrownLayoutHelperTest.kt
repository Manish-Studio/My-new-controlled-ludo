package com.vinaykpro.ludoking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests verifying exact mathematical centering of winning crown views
 * inside all 4 corner player boxes across phones, tablets, and different aspect ratios.
 */
class WinnerCrownLayoutHelperTest {

    @Test
    fun testAllFourCrownSlotsCenteredAcrossScreenSizes() {
        val boardSizes = listOf(720, 1080, 1440, 1600)

        for (size in boardSizes) {
            val expectedBoxSize = Math.round(size * 0.40f)

            // Slot 1: Bottom-Left
            val bottomLeft = WinnerCrownLayoutHelper.computeCrownGeometry(size, size, 1)
            assertEquals(expectedBoxSize, bottomLeft.boxSize)
            assertEquals(0, bottomLeft.boxLeft)
            assertEquals(size - expectedBoxSize, bottomLeft.boxTop)
            assertEquals(expectedBoxSize / 2f, bottomLeft.crownCenterX, 0.01f)
            assertEquals((size - expectedBoxSize) + expectedBoxSize / 2f, bottomLeft.crownCenterY, 0.01f)

            // Slot 2: Top-Left
            val topLeft = WinnerCrownLayoutHelper.computeCrownGeometry(size, size, 2)
            assertEquals(expectedBoxSize, topLeft.boxSize)
            assertEquals(0, topLeft.boxLeft)
            assertEquals(0, topLeft.boxTop)
            assertEquals(expectedBoxSize / 2f, topLeft.crownCenterX, 0.01f)
            assertEquals(expectedBoxSize / 2f, topLeft.crownCenterY, 0.01f)

            // Slot 3: Top-Right
            val topRight = WinnerCrownLayoutHelper.computeCrownGeometry(size, size, 3)
            assertEquals(expectedBoxSize, topRight.boxSize)
            assertEquals(size - expectedBoxSize, topRight.boxLeft)
            assertEquals(0, topRight.boxTop)
            assertEquals((size - expectedBoxSize) + expectedBoxSize / 2f, topRight.crownCenterX, 0.01f)
            assertEquals(expectedBoxSize / 2f, topRight.crownCenterY, 0.01f)

            // Slot 4: Bottom-Right
            val bottomRight = WinnerCrownLayoutHelper.computeCrownGeometry(size, size, 4)
            assertEquals(expectedBoxSize, bottomRight.boxSize)
            assertEquals(size - expectedBoxSize, bottomRight.boxLeft)
            assertEquals(size - expectedBoxSize, bottomRight.boxTop)
            assertEquals((size - expectedBoxSize) + expectedBoxSize / 2f, bottomRight.crownCenterX, 0.01f)
            assertEquals((size - expectedBoxSize) + expectedBoxSize / 2f, bottomRight.crownCenterY, 0.01f)

            // Left and right crowns must never overlap, and top/bottom crowns must never overlap
            assertTrue(topLeft.boxLeft + topLeft.boxSize < topRight.boxLeft)
            assertTrue(topLeft.boxTop + topLeft.boxSize < bottomLeft.boxTop)
        }
    }

    @Test
    fun testCrownGeometryHandlesNonSquareOrientationGracefully() {
        val geom = WinnerCrownLayoutHelper.computeCrownGeometry(1920, 1080, 4)
        val expectedBoxSize = Math.round(1080 * 0.40f)
        assertEquals(expectedBoxSize, geom.boxSize)
        assertEquals(1920 - expectedBoxSize, geom.boxLeft)
        assertEquals(1080 - expectedBoxSize, geom.boxTop)
        assertEquals(geom.boxLeft + expectedBoxSize / 2f, geom.crownCenterX, 0.01f)
        assertEquals(geom.boxTop + expectedBoxSize / 2f, geom.crownCenterY, 0.01f)
    }
}
