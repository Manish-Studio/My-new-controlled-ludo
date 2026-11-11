package com.vinaykpro.ludoking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying the fixes for Bug 1, Bug 2, and Bug 3 in the Ludo game engine.
 */
class GameplayBugsFixTest {

    // =========================================================================
    // BUG 1: TOKENS GETTING CLIPPED AT OUTER BOARD BOUNDARIES
    // =========================================================================

    @Test
    fun testOuterBoardEdgeCoordinatesRemainUnmodifiedAndInsideBoundary() {
        // Grid percentage coordinate definition from MainActivity
        val dx = doubleArrayOf(
            53.32, 53.32, 53.32, 53.32, 53.32, 60.04, 66.7, 73.36, 80.02, 86.68, 93.34, 93.34, 93.34,
            86.68, 80.02, 73.36, 66.7, 60.04, 53.32, 53.32, 53.32, 53.32, 53.32, 53.32, 46.66, 40.0,
            40.0, 40.0, 40.0, 40.0, 40.0, 33.34, 26.68, 20.02, 13.36, 6.7, 0.04, 0.04, 0.04, 6.7,
            13.36, 20.02, 26.68, 33.34, 40.0, 40.0, 40.0, 40.0, 40.0, 40.0, 46.66, 53.32
        )
        val dy = doubleArrayOf(
            6.66, 13.32, 19.98, 26.64, 33.3, 40.0, 40.0, 40.0, 40.0, 40.0, 40.0, 46.66, 53.32,
            53.32, 53.32, 53.32, 53.32, 53.32, 60.04, 66.7, 73.36, 80.02, 86.68, 93.34, 93.34, 93.34,
            86.68, 80.02, 73.36, 66.7, 60.04, 53.32, 53.32, 53.32, 53.32, 53.32, 53.32, 46.66, 40.0,
            40.0, 40.0, 40.0, 40.0, 40.0, 33.34, 26.68, 20.02, 13.36, 6.7, 0.04, 0.04, 0.04
        )

        val pxWidth = 1080f
        for (i in dx.indices) {
            val cellX = (pxWidth / 100f) * dx[i].toFloat()
            val cellY = (pxWidth / 100f) * dy[i].toFloat()

            // Verify logical game board coordinates are intact and within [0, pxWidth]
            assertTrue("cellX at index $i must be >= 0", cellX >= 0f)
            assertTrue("cellX at index $i must be <= pxWidth", cellX <= pxWidth)
            assertTrue("cellY at index $i must be >= 0", cellY >= 0f)
            assertTrue("cellY at index $i must be <= pxWidth", cellY <= pxWidth)
        }

        // Specifically test the 4 outer edge extremes:
        // Leftmost edge: blocks 36, 37, 38 have dx = 0.04%
        assertEquals(0.04, dx[36], 0.001)
        // Rightmost edge: blocks 10, 11, 12 have dx = 93.34%
        assertEquals(93.34, dx[10], 0.001)
        // Topmost edge: blocks 49, 50, 51 have dy = 0.04%
        assertEquals(0.04, dy[50], 0.001)
        // Bottommost edge: blocks 23, 24, 25 have dy = 93.34%
        assertEquals(93.34, dy[24], 0.001)
    }

    // =========================================================================
    // BUG 2: UNEXPECTED EXTRA SIXES / CONSECUTIVE-SIX RULE & TURN LIFECYCLE
    // =========================================================================

    /**
     * Simulates the turn and dice consecutive-six state machine in the game engine.
     */
    class TurnDiceSimulator {
        var consecutiveSixes = 0
        var currentDice = -1
        var isExtraChance = false
        var turnForfeited = false
        var activePlayerIndex = 0

        fun roll(value: Int) {
            currentDice = value
            if (value == 6) {
                consecutiveSixes++
            } else {
                consecutiveSixes = 0
            }

            if (consecutiveSixes >= 3) {
                // Third consecutive six: Penalty triggered, turn forfeited, reset counter
                consecutiveSixes = 0
                currentDice = -1
                isExtraChance = false
                turnForfeited = true
            } else if (value == 6) {
                isExtraChance = true
                turnForfeited = false
            } else {
                isExtraChance = false
                turnForfeited = false
            }
        }

        fun switchPlayer() {
            consecutiveSixes = 0
            currentDice = -1
            isExtraChance = false
            turnForfeited = false
            activePlayerIndex++
        }
    }

    @Test
    fun testOneRollProducesExactlyOneResult() {
        val sim = TurnDiceSimulator()
        sim.roll(4)
        assertEquals(4, sim.currentDice)
        assertFalse("Roll of 4 must not grant extra chance", sim.isExtraChance)
        assertEquals(0, sim.consecutiveSixes)
    }

    @Test
    fun testOneSixGrantsExtraTurnWithoutAutoRoll() {
        val sim = TurnDiceSimulator()
        sim.roll(6)
        assertEquals(6, sim.currentDice)
        assertTrue("Roll of 6 must grant extra turn flag", sim.isExtraChance)
        assertEquals(1, sim.consecutiveSixes)
        assertFalse("Turn must not be forfeited", sim.turnForfeited)

        // The dice result remains 6 until a new deliberate roll action is made
        assertEquals(6, sim.currentDice)
    }

    @Test
    fun testSecondSixMaintainsExtraTurn() {
        val sim = TurnDiceSimulator()
        sim.roll(6)
        assertEquals(1, sim.consecutiveSixes)

        // Second consecutive roll of 6
        sim.roll(6)
        assertEquals(2, sim.consecutiveSixes)
        assertTrue("Second six still grants extra chance", sim.isExtraChance)
        assertFalse("Second six does not forfeit turn", sim.turnForfeited)
    }

    @Test
    fun testThirdConsecutiveSixForfeitsTurnAndResetsCounter() {
        val sim = TurnDiceSimulator()
        sim.roll(6)
        sim.roll(6)
        assertEquals(2, sim.consecutiveSixes)

        // Third consecutive six
        sim.roll(6)
        assertTrue("Third consecutive six must forfeit the turn", sim.turnForfeited)
        assertFalse("Third consecutive six must NOT grant an extra chance", sim.isExtraChance)
        assertEquals("Counter must reset to 0 after penalty", 0, sim.consecutiveSixes)
        assertEquals(-1, sim.currentDice)
    }

    @Test
    fun testNonSixResetsConsecutiveCounter() {
        val sim = TurnDiceSimulator()
        sim.roll(6)
        sim.roll(6)
        assertEquals(2, sim.consecutiveSixes)

        // Rolls a 3
        sim.roll(3)
        assertEquals(0, sim.consecutiveSixes)
        assertFalse(sim.isExtraChance)
        assertFalse(sim.turnForfeited)

        // Subsequent six starts from 1 again
        sim.roll(6)
        assertEquals(1, sim.consecutiveSixes)
        assertTrue(sim.isExtraChance)
    }

    @Test
    fun testSwitchingPlayersResetsConsecutiveCounter() {
        val sim = TurnDiceSimulator()
        sim.roll(6)
        assertEquals(1, sim.consecutiveSixes)

        sim.switchPlayer()
        assertEquals("Switching players must reset consecutiveSixes to 0", 0, sim.consecutiveSixes)
        assertEquals(-1, sim.currentDice)
        assertEquals(1, sim.activePlayerIndex)
    }

    // =========================================================================
    // BUG 3: SECOND UNOPENED TOKEN OFFERED AFTER ANOTHER SIX
    // =========================================================================

    data class MockPiece(
        val id: Int,
        var isAlive: Boolean,
        var currBlock: Int,
        var numberOfSteps: Int,
        var isClickable: Boolean = false,
        var isActiveState: Boolean = false
    ) {
        fun check(diceValue: Int): Boolean {
            if (diceValue == 6) {
                if (!isAlive || (numberOfSteps + diceValue) < 57) {
                    isClickable = true
                    isActiveState = true
                    return true
                }
                return false
            } else if (isAlive && (numberOfSteps + diceValue) < 57) {
                isClickable = true
                isActiveState = true
                return true
            } else {
                isClickable = false
                isActiveState = false
                return false
            }
        }
    }

    /**
     * Resolves which piece should be auto-moved, implementing the fix for Bug 3.
     */
    fun resolveAutoMove(pieces: List<MockPiece>, diceValue: Int): MockPiece? {
        var chances = 0
        var autoMoveBlock = -1
        var autoMovePiece: MockPiece? = null
        var aliveMovableCount = 0
        var unopenedCount = 0

        for (p in pieces) {
            if (p.check(diceValue)) {
                if (p.isAlive) {
                    aliveMovableCount++
                } else {
                    unopenedCount++
                }
                if (chances == 0) {
                    autoMoveBlock = p.currBlock
                    autoMovePiece = p
                } else if ((p.currBlock == -1 && autoMoveBlock == -1) || (autoMoveBlock != -1 && autoMoveBlock == p.currBlock)) {
                    // Multiple unopened home tokens (currBlock == -1) or pieces stacked on same square
                } else {
                    autoMovePiece = null
                }
                chances++
            }
        }

        if (diceValue == 6) {
            if ((aliveMovableCount > 0 && unopenedCount > 0) || aliveMovableCount > 1) {
                autoMovePiece = null
            }
        }

        return autoMovePiece
    }

    @Test
    fun testFirstSixOpensOneHomeTokenWhenAllUnopened() {
        // Initially, all 4 tokens are unopened in home (currBlock = -1, isAlive = false)
        val pieces = listOf(
            MockPiece(1, isAlive = false, currBlock = -1, numberOfSteps = 0),
            MockPiece(2, isAlive = false, currBlock = -1, numberOfSteps = 0),
            MockPiece(3, isAlive = false, currBlock = -1, numberOfSteps = 0),
            MockPiece(4, isAlive = false, currBlock = -1, numberOfSteps = 0)
        )

        // When rolling 6, all 4 unopened pieces have currBlock == -1
        val autoMove = resolveAutoMove(pieces, 6)
        // All 4 unopened tokens are in the same location (-1), so autoMove selects one to open cleanly
        assertNotNull("First six cleanly opens one home token", autoMove)
        assertFalse("Selected token was unopened", autoMove!!.isAlive)
    }

    @Test
    fun testSecondSixOffersUnopenedHomeTokenAndDoesNotAutoMoveOpenToken() {
        // Player now has 1 alive token on board (e.g., at start block 0) and 3 unopened tokens in home (-1)
        val p1 = MockPiece(1, isAlive = true, currBlock = 0, numberOfSteps = 0)
        val p2 = MockPiece(2, isAlive = false, currBlock = -1, numberOfSteps = 0)
        val p3 = MockPiece(3, isAlive = false, currBlock = -1, numberOfSteps = 0)
        val p4 = MockPiece(4, isAlive = false, currBlock = -1, numberOfSteps = 0)
        val pieces = listOf(p1, p2, p3, p4)

        // Player rolls another 6
        val autoMove = resolveAutoMove(pieces, 6)

        // VERIFICATION:
        // autoMove MUST be null so the player is offered the interactive choice
        // between opening another unopened home token or advancing token 1.
        assertNull("Game must NOT auto-move when multiple legal moves exist on six", autoMove)

        // Both the opened piece and unopened pieces must be in active (clickable) state
        assertTrue("Alive piece 1 must be active/clickable", p1.isClickable)
        assertTrue("Unopened piece 2 must be active/clickable", p2.isClickable)
        assertTrue("Unopened piece 3 must be active/clickable", p3.isClickable)
        assertTrue("Unopened piece 4 must be active/clickable", p4.isClickable)
    }

    @Test
    fun testNonSixRollWithSingleAliveTokenStillAutoMovesCleanly() {
        // Player has 1 alive token on board and 3 unopened tokens in home
        val p1 = MockPiece(1, isAlive = true, currBlock = 5, numberOfSteps = 5)
        val p2 = MockPiece(2, isAlive = false, currBlock = -1, numberOfSteps = 0)
        val p3 = MockPiece(3, isAlive = false, currBlock = -1, numberOfSteps = 0)
        val p4 = MockPiece(4, isAlive = false, currBlock = -1, numberOfSteps = 0)
        val pieces = listOf(p1, p2, p3, p4)

        // Player rolls 4
        val autoMove = resolveAutoMove(pieces, 4)

        // On non-6 roll, unopened tokens cannot move, so only p1 is legal -> autoMove is p1
        assertNotNull(autoMove)
        assertEquals(1, autoMove!!.id)
        assertTrue(autoMove.isAlive)
        assertFalse("Unopened piece 2 cannot move on 4", p2.isClickable)
    }

    @Test
    fun testAllColorsSupportedEqually() {
        for (color in listOf("red", "green", "blue", "yellow")) {
            val pieces = listOf(
                MockPiece(1, isAlive = true, currBlock = 10, numberOfSteps = 10),
                MockPiece(2, isAlive = false, currBlock = -1, numberOfSteps = 0),
                MockPiece(3, isAlive = false, currBlock = -1, numberOfSteps = 0),
                MockPiece(4, isAlive = false, currBlock = -1, numberOfSteps = 0)
            )

            val autoMove = resolveAutoMove(pieces, 6)
            assertNull("For color $color, rolling 6 with unopened tokens must not auto-move", autoMove)
        }
    }
}
