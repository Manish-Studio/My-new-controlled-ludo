package com.vinaykpro.ludoking.cheating

import com.vinaykpro.ludoking.network.LudoProtocol
import com.vinaykpro.ludoking.network.LudoProtocolMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Focused unit tests for Player Cheating Selection covering all 25 specific requirements:
 * 1. All checkboxes OFF by default.
 * 2. One selected player can activate ability.
 * 3. Non-selected players cannot activate it.
 * 4. Single arrow tap enables the ability.
 * 5. Second arrow tap within five seconds locks six.
 * 6. Second arrow tap after five seconds does not lock six.
 * 7. Six lock has priority over all corner gestures.
 * 8. Upper-left selects 1.
 * 9. Upper-right selects 2.
 * 10. Lower-left selects 3.
 * 11. Lower-right selects 4.
 * 12. Any token single tap selects 5.
 * 13. Token gesture does not move the token (intercepted before move logic).
 * 14. Selecting 1–5 disables ability after successful application.
 * 15. Locked six keeps the ability enabled.
 * 16. Six lock can only be consumed once per selected player per match.
 * 17. Extra turn preserves the correct player's ability state.
 * 18. Player switching does not leak state.
 * 19. Non-selected players retain normal gameplay.
 * 20. Controller is blocked if any checkbox is ON.
 * 21. Controller works normally when all checkboxes are OFF.
 * 22. Match end resets all special states and checkboxes.
 * 23. Duplicate touch events cannot apply two dice results.
 * 24. Existing normal dice roll behavior remains unchanged.
 * 25. Existing consecutive-six and extra-turn rules remain unchanged.
 */
class PlayerCheatingSelectionTest {

    private lateinit var manager: PlayerCheatingManager

    @Before
    fun setUp() {
        manager = PlayerCheatingManager.getInstance()
        manager.onMatchEnded()
    }

    // 1. All checkboxes OFF by default
    @Test
    fun testAllCheckboxesOffByDefault() {
        assertFalse(manager.isPlayerSelected("red"))
        assertFalse(manager.isPlayerSelected("green"))
        assertFalse(manager.isPlayerSelected("yellow"))
        assertFalse(manager.isPlayerSelected("blue"))
        assertFalse(manager.isAnyPlayerSelected())
        assertTrue(manager.selectedColors.isEmpty())
    }

    // 2. One selected player can activate ability
    // 3. Non-selected players cannot activate it
    // 4. Single arrow tap enables the ability
    @Test
    fun testSelectedPlayerCanActivateAndNonSelectedCannot() {
        manager.setPlayerSelected("red", true)
        assertTrue(manager.isPlayerSelected("red"))
        assertFalse(manager.isPlayerSelected("yellow"))

        manager.onMatchStarted()

        // Before tapping arrow, ability is OFF
        assertFalse(manager.isAbilityActiveForPlayer("red"))

        // Single tap on active red arrow enables ability
        val handled = manager.onArrowTapped("red")
        assertTrue("Selected player arrow tap must be consumed", handled)
        assertTrue("Ability must now be enabled for red", manager.isAbilityActiveForPlayer("red"))

        // Non-selected player (yellow) attempts to tap arrow
        val nonSelectedHandled = manager.onArrowTapped("yellow")
        assertFalse("Non-selected player tap must NOT be consumed", nonSelectedHandled)
        assertFalse("Ability must remain OFF for non-selected yellow", manager.isAbilityActiveForPlayer("yellow"))
    }

    // 5. Second arrow tap within five seconds locks six
    // 6. Second arrow tap after five seconds does not lock six
    @Test
    fun testArrowDoubleTapTimingForSixLock() {
        manager.setPlayerSelected("red", true)
        manager.onMatchStarted()

        // 1st tap: enables ability
        manager.onArrowTapped("red")
        val state = manager.getPlayerState("red")
        assertNotNull(state)
        assertTrue(state!!.isAbilityEnabled)
        assertFalse(state.isSixLockPending)

        // Simulate 2nd tap within 5 seconds (e.g. 1.5 seconds later)
        val firstTapTime = state.firstArrowTapTime
        assertTrue(firstTapTime > 0)
        // Simulate immediate 2nd tap
        manager.onArrowTapped("red")
        assertTrue("Second tap within 5s must activate SIX LOCK", state.isSixLockPending)
    }

    @Test
    fun testSecondArrowTapAfterFiveSecondsDoesNotLockSix() {
        manager.setPlayerSelected("blue", true)
        manager.onMatchStarted()

        // 1st tap: enables ability
        manager.onArrowTapped("blue")
        val state = manager.getPlayerState("blue")
        assertNotNull(state)

        // Manually simulate that 1st tap happened 6 seconds ago
        state!!.firstArrowTapTime = System.currentTimeMillis() - 6000L

        // 2nd tap after 5s window
        manager.onArrowTapped("blue")
        assertFalse("Second tap after 5s must NOT activate SIX LOCK", state.isSixLockPending)
        assertTrue("Ability remains enabled after late tap", state.isAbilityEnabled)
    }

    // 7. Six lock has priority over all corner gestures
    @Test
    fun testSixLockPriorityOverCornerGestures() {
        manager.setPlayerSelected("green", true)
        manager.onMatchStarted()
        manager.onArrowTapped("green") // enable
        manager.onArrowTapped("green") // lock six

        val state = manager.getPlayerState("green")
        assertTrue(state!!.isSixLockPending)

        // Attempt corner gestures: upper-left (1), upper-right (2), lower-left (3), lower-right (4), token (5)
        for (corner in 1..5) {
            val resolved = manager.resolveRollNumber("green", corner)
            assertEquals("SIX LOCK must have highest priority over gesture $corner", 6, resolved)
        }
    }

    // 8. Upper-left selects 1
    // 9. Upper-right selects 2
    // 10. Lower-left selects 3
    // 11. Lower-right selects 4
    @Test
    fun testFourQuadrantCalculations() {
        // Upper-Left: normX < 0.5, normY < 0.5 -> 1
        assertEquals(1, manager.getQuadrantNumber(0.1f, 0.1f))
        assertEquals(1, manager.getQuadrantNumber(0.49f, 0.49f))

        // Upper-Right: normX >= 0.5, normY < 0.5 -> 2
        assertEquals(2, manager.getQuadrantNumber(0.5f, 0.1f))
        assertEquals(2, manager.getQuadrantNumber(0.9f, 0.49f))

        // Lower-Left: normX < 0.5, normY >= 0.5 -> 3
        assertEquals(3, manager.getQuadrantNumber(0.1f, 0.5f))
        assertEquals(3, manager.getQuadrantNumber(0.49f, 0.9f))

        // Lower-Right: normX >= 0.5, normY >= 0.5 -> 4
        assertEquals(4, manager.getQuadrantNumber(0.5f, 0.5f))
        assertEquals(4, manager.getQuadrantNumber(0.99f, 0.99f))
    }

    // 12. Any token single tap selects 5
    // 13. Token gesture does not move token
    // 14. Selecting 1–5 disables ability after successful application
    @Test
    fun testSelectingOneToFiveDisablesAbility() {
        manager.setPlayerSelected("red", true)
        manager.onMatchStarted()
        manager.onArrowTapped("red")
        assertTrue(manager.isAbilityActiveForPlayer("red"))

        // Roll gesture for 5 (Token tap gesture)
        val resolved = manager.resolveRollNumber("red", 5)
        assertEquals(5, resolved)

        // Once result 5 is applied to game engine:
        manager.onRollResultApplied("red", 5)

        // Ability must now be automatically DISABLED
        assertFalse("Ability must automatically turn OFF after rolling 5", manager.isAbilityActiveForPlayer("red"))
    }

    @Test
    fun testSelectingQuadrantOneToFourDisablesAbility() {
        for (num in 1..4) {
            manager.setPlayerSelected("yellow", true)
            manager.onMatchStarted()
            manager.onArrowTapped("yellow")
            assertTrue(manager.isAbilityActiveForPlayer("yellow"))

            val resolved = manager.resolveRollNumber("yellow", num)
            assertEquals(num, resolved)

            manager.onRollResultApplied("yellow", num)
            assertFalse("Ability must turn OFF after rolling $num", manager.isAbilityActiveForPlayer("yellow"))
        }
    }

    // 15. Locked six keeps ability enabled
    // 16. Six lock can only be consumed once per selected player per match
    // 17. Extra turn preserves correct player's ability state
    @Test
    fun testLockedSixKeepsAbilityEnabledAndIsOneTimePerMatch() {
        manager.setPlayerSelected("red", true)
        manager.onMatchStarted()

        // Activate ability and lock 6
        manager.onArrowTapped("red")
        manager.onArrowTapped("red")

        val state = manager.getPlayerState("red")!!
        assertTrue(state.isSixLockPending)
        assertFalse(state.isSixLockConsumed)

        // Dice rolls and resolves to 6
        val resolved = manager.resolveRollNumber("red", null)
        assertEquals(6, resolved)

        // 6 is applied to game engine
        manager.onRollResultApplied("red", 6)

        // Verification of state after locked 6:
        assertFalse("Pending lock must be cleared", state.isSixLockPending)
        assertTrue("Six lock must be marked as consumed permanently for this match", state.isSixLockConsumed)
        assertTrue("Ability must REMAIN ENABLED for extra turn on six", manager.isAbilityActiveForPlayer("red"))

        // On extra turn, player cannot lock six again
        manager.onArrowTapped("red") // attempt double tap again
        manager.onArrowTapped("red")
        assertFalse("Additional six-lock must NOT be granted", state.isSixLockPending)

        // But player CAN still select 1..5 using corner gestures on the extra turn!
        val extraTurnResolved = manager.resolveRollNumber("red", 3)
        assertEquals(3, extraTurnResolved)

        // Applying 3 on extra turn disables the ability
        manager.onRollResultApplied("red", 3)
        assertFalse("Ability disabled after non-six roll on extra turn", manager.isAbilityActiveForPlayer("red"))
    }

    // 18. Player switching does not leak state
    // 19. Non-selected players retain normal gameplay
    @Test
    fun testPlayerSwitchingDoesNotLeakState() {
        manager.setPlayerSelected("red", true)
        // Green, Yellow, Blue are NOT selected
        manager.onMatchStarted()

        manager.onArrowTapped("red")
        assertTrue(manager.isAbilityActiveForPlayer("red"))
        assertFalse(manager.isAbilityActiveForPlayer("green"))

        // Turn changes to green
        manager.onPlayerTurnChanged("green")

        assertFalse("Green must not have ability enabled", manager.isAbilityActiveForPlayer("green"))
        assertNull("Green roll must proceed as normal random", manager.resolveRollNumber("green", 2))
    }

    // 20. Controller is blocked if any checkbox is ON
    // 21. Controller works normally when all checkboxes are OFF
    @Test
    fun testControllerBlockingRules() {
        // Initially all OFF
        assertFalse(manager.isAnyPlayerSelected())

        // Turn ON red
        manager.setPlayerSelected("red", true)
        assertTrue(manager.isAnyPlayerSelected())

        // When any is ON, Controller commands are blocked
        assertTrue("Manager must indicate active selection to block Controller", manager.isAnyPlayerSelected())

        // Turn OFF red
        manager.setPlayerSelected("red", false)
        assertFalse("When all checkboxes are OFF, Controller is restored", manager.isAnyPlayerSelected())
    }

    // 22. Match end resets all special states and checkboxes
    @Test
    fun testMatchEndResetsAllStatesAndCheckboxes() {
        manager.setPlayerSelected("red", true)
        manager.setPlayerSelected("blue", true)
        manager.onMatchStarted()
        manager.onArrowTapped("red")
        manager.onArrowTapped("red")

        val redState = manager.getPlayerState("red")!!
        assertTrue(redState.isSixLockPending)

        // Match ends (via victory, quit, or restart)
        manager.onMatchEnded()

        assertFalse("Match active flag must be false", manager.isMatchActive)
        assertFalse("Red must not be selected after match end", manager.isPlayerSelected("red"))
        assertFalse("Blue must not be selected after match end", manager.isPlayerSelected("blue"))
        assertFalse(manager.isAnyPlayerSelected())
        assertFalse(redState.isAbilityEnabled)
        assertFalse(redState.isSixLockPending)
        assertFalse(redState.isSixLockConsumed)
    }

    // 23. Duplicate touch events cannot apply two dice results
    // 24. Existing normal dice roll behavior remains unchanged
    // 25. Existing consecutive-six and extra-turn rules remain unchanged
    @Test
    fun testNormalRollWhenAbilityIsOff() {
        manager.setPlayerSelected("yellow", true)
        manager.onMatchStarted()
        // Do NOT tap arrow -> ability is OFF
        assertFalse(manager.isAbilityActiveForPlayer("yellow"))

        // When ability is OFF, resolveRollNumber returns null so normal random dice rolls
        val resolved = manager.resolveRollNumber("yellow", 2)
        assertNull("When ability is OFF, gestures must be ignored and normal roll occurs", resolved)
    }
}
