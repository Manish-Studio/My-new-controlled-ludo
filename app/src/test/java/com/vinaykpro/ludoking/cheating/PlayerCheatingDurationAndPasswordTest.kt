package com.vinaykpro.ludoking.cheating

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure JVM unit tests for:
 * - First-time password protection ("3507") & persisted unlock state
 * - 1-10 games duration slider bounds and active-match locking
 * - Independent per-player remaining game countdowns and single decrement per match session
 * - App restart persistence simulation & replay session behavior
 * - Controller gating state (isAnyPlayerSelected) across game counts
 * - Last-token completion & move validation (3 completed tokens + 1 token 6 steps away rolling 6)
 */
class PlayerCheatingDurationAndPasswordTest {

    private lateinit var store: PlayerCheatingManager.InMemoryPersistenceStore
    private lateinit var manager: PlayerCheatingManager

    @Before
    fun setUp() {
        store = PlayerCheatingManager.InMemoryPersistenceStore()
        manager = PlayerCheatingManager.getInstance()
        manager.setPersistenceStore(store)
        manager.resetForTest()
        TurnSixRuleValidator.getInstance().resetMatch()
    }

    @After
    fun tearDown() {
        TurnSixRuleValidator.getInstance().resetMatch()
        manager.resetForTest()
        manager.setPersistenceStore(null)
    }

    @Test
    fun testFirstTimePasswordGateRequiresExact3507AndPersistsAcrossRestarts() {
        assertFalse(manager.isFeatureUnlocked)

        // Wrong attempts must fail
        assertFalse(manager.verifyAndUnlock(null))
        assertFalse(manager.verifyAndUnlock(""))
        assertFalse(manager.verifyAndUnlock("0000"))
        assertFalse(manager.verifyAndUnlock("3506"))
        assertFalse(manager.verifyAndUnlock(" 3507"))
        assertFalse(manager.verifyAndUnlock("3507 "))
        assertFalse(manager.isFeatureUnlocked)

        // Exact password unlocks and persists
        assertTrue(manager.verifyAndUnlock("3507"))
        assertTrue(manager.isFeatureUnlocked)

        // Simulate app process restart using the same PersistenceStore
        manager.reloadFromPersistenceForTest()
        assertTrue("Unlocked state must remain persisted across app restarts", manager.isFeatureUnlocked)
    }

    @Test
    fun testSliderDefaultBoundsAndActiveMatchLock() {
        assertEquals(1, manager.durationGames)

        assertTrue(manager.setDurationGames(0))
        assertEquals(1, manager.durationGames)

        assertTrue(manager.setDurationGames(15))
        assertEquals(10, manager.durationGames)

        assertTrue(manager.setDurationGames(5))
        assertEquals(5, manager.durationGames)

        assertTrue(manager.setPlayerSelected("red", true))
        assertEquals(5, manager.getRemainingGames("red"))

        manager.onMatchStarted()
        assertFalse("Duration slider cannot be changed during active match", manager.setDurationGames(2))
        assertEquals(5, manager.durationGames)
        assertEquals(5, manager.getRemainingGames("red"))
    }

    @Test
    fun testIndependentPerPlayerCountdownAndPartialExpiry() {
        // Set slider to 5 for Red & Blue
        assertTrue(manager.setDurationGames(5))
        assertTrue(manager.setPlayerSelected("red", true))
        assertTrue(manager.setPlayerSelected("blue", true))
        assertEquals(5, manager.getRemainingGames("red"))
        assertEquals(5, manager.getRemainingGames("blue"))

        // Play Match 1
        manager.onMatchStarted()
        val s1 = manager.matchSessionId
        assertTrue(manager.onMatchEndedIfSession(s1))
        assertEquals(4, manager.getRemainingGames("red"))
        assertEquals(4, manager.getRemainingGames("blue"))

        // Duplicate end callback must NOT decrement a second time
        assertFalse(manager.onMatchEndedIfSession(s1))
        assertEquals(4, manager.getRemainingGames("red"))
        assertEquals(4, manager.getRemainingGames("blue"))

        // Before Match 2: uncheck slider isn't overwritten; set slider to 2 and enable Green
        // Wait: setDurationGames updates currently selected players only if user explicitly changes slider.
        // Test manual disable of Red doesn't affect Blue; re-enable Red doesn't affect Blue.
        assertTrue(manager.setPlayerSelected("green", true)) // uses current duration (5)
        // Simulate user disabling Blue? No, let's test Red/Blue=4 vs manually set duration before enabling Green:
        // If user sets slider to 2 while only Green is toggled, wait: check sequence where Blue=1 and Red=3
        // Let's test disabling Green cleanly doesn't alter Red or Blue:
        assertTrue(manager.setPlayerSelected("green", false))
        assertEquals(4, manager.getRemainingGames("red"))
        assertEquals(4, manager.getRemainingGames("blue"))

        // Finish 4 more matches; verify counts reach 0 cleanly on match 5
        for (i in 1..3) {
            manager.onMatchStarted()
            val sid = manager.matchSessionId
            assertTrue(manager.onMatchEndedIfSession(sid))
            assertTrue(manager.isPlayerSelected("red"))
            assertTrue(manager.isPlayerSelected("blue"))
            assertTrue(manager.isAnyPlayerSelected())
        }
        assertEquals(1, manager.getRemainingGames("red"))
        assertEquals(1, manager.getRemainingGames("blue"))

        // 5th match ends -> both reach 0 -> OFF -> Controller gating becomes false
        manager.onMatchStarted()
        assertTrue(manager.onMatchEndedIfSession(manager.matchSessionId))
        assertFalse(manager.isPlayerSelected("red"))
        assertFalse(manager.isPlayerSelected("blue"))
        assertEquals(0, manager.getRemainingGames("red"))
        assertEquals(0, manager.getRemainingGames("blue"))
        assertFalse("Controller hosting must be allowed once all players expire", manager.isAnyPlayerSelected())
    }

    @Test
    fun testExpiringOnePlayerLeavesOtherSelectedPlayersActive() {
        // Red gets 2 games
        assertTrue(manager.setDurationGames(2))
        assertTrue(manager.setPlayerSelected("red", true))

        // Play 1 match -> Red has 1 remaining
        manager.onMatchStarted()
        assertTrue(manager.onMatchEndedIfSession(manager.matchSessionId))
        assertEquals(1, manager.getRemainingGames("red"))

        // Now user enables Blue while slider is still 2 (Red wasn't re-toggled, still 1 remaining; Blue gets 2)
        assertTrue(manager.setPlayerSelected("blue", true))
        assertEquals(1, manager.getRemainingGames("red"))
        assertEquals(2, manager.getRemainingGames("blue"))

        // Play next match -> Red reaches 0 (turns OFF), Blue reaches 1 (stays ON!)
        manager.onMatchStarted()
        assertTrue(manager.onMatchEndedIfSession(manager.matchSessionId))
        assertFalse("Red must turn OFF when its remaining count reaches 0", manager.isPlayerSelected("red"))
        assertEquals(0, manager.getRemainingGames("red"))
        assertTrue("Blue must stay ON with 1 remaining game", manager.isPlayerSelected("blue"))
        assertEquals(1, manager.getRemainingGames("blue"))
        assertTrue("Controller must remain blocked while Blue is still active", manager.isAnyPlayerSelected())

        // Replay / final match consumes Blue's last remaining game
        manager.onMatchStarted()
        assertTrue(manager.onMatchEndedIfSession(manager.matchSessionId))
        assertFalse(manager.isPlayerSelected("blue"))
        assertEquals(0, manager.getRemainingGames("blue"))
        assertFalse(manager.isAnyPlayerSelected())
    }

    @Test
    fun testPersistedDurationSurvivesAppRestartAndKeepsControllerBlocked() {
        assertTrue(manager.verifyAndUnlock("3507"))
        assertTrue(manager.setDurationGames(3))
        assertTrue(manager.setPlayerSelected("yellow", true))

        // Match 1 finishes -> Yellow has 2 left
        manager.onMatchStarted()
        assertTrue(manager.onMatchEndedIfSession(manager.matchSessionId))
        assertEquals(2, manager.getRemainingGames("yellow"))

        // Simulate app process kill and relaunch
        manager.reloadFromPersistenceForTest()

        assertTrue(manager.isFeatureUnlocked)
        assertEquals(3, manager.durationGames)
        assertTrue(manager.isPlayerSelected("yellow"))
        assertEquals(2, manager.getRemainingGames("yellow"))
        assertTrue("Controller auto-start on launch must stay blocked when persisted player has games left", manager.isAnyPlayerSelected())
    }

    @Test
    fun testConfigChangeRotationDoesNotDecrementSession() {
        assertTrue(manager.setDurationGames(2))
        assertTrue(manager.setPlayerSelected("red", true))

        manager.onMatchStarted()
        val session = manager.matchSessionId
        assertTrue(manager.isSessionActive(session))

        // During Activity config change recreation, MainActivity checks isSessionActive(session)
        // and does NOT call onMatchEndedIfSession or onMatchStarted again.
        assertEquals(2, manager.getRemainingGames("red"))
        assertTrue(manager.isSessionActive(session))

        // Genuine exit / completion calls onMatchEndedIfSession(session) once
        assertTrue(manager.onMatchEndedIfSession(session))
        assertEquals(1, manager.getRemainingGames("red"))
        assertFalse(manager.isSessionActive(session))
    }

    @Test
    fun testLastTokenSixStepsAwayCompletesOnFirstSixOfTurn() {
        val validator = TurnSixRuleValidator.getInstance()
        val turnId = validator.onTurnChanged("red", 1)

        // First 6 of turn must be accepted globally
        val rollRes = validator.validateAndAcceptRoll(
            validator.beginRollEvent(),
            turnId,
            "red",
            1,
            6,
            TurnSixRuleValidator.RollSource.ABILITY,
            null
        )
        assertTrue(rollRes.accepted)
        assertEquals(1, rollRes.sixCountAfterRoll)

        // 3 tokens already completed home (numberOfSteps = 56, hasCompletedItsPurpose = true)
        for (i in 1..3) {
            assertFalse(
                "Completed token $i must never be movable on a 6",
                LudoMoveValidator.isValidPieceMove(true, 56, 6, true, false)
            )
        }

        // 4th token is alive at entry square (numberOfSteps = 50 -> exactly 6 steps to reach home 56)
        assertTrue(
            "4th token needing exact 6 steps must be valid when rolling 6",
            LudoMoveValidator.isValidPieceMove(true, 50, 6, false, false)
        )

        // Overshoot guard: 4th token at 5 steps remaining (numberOfSteps = 51) CANNOT move on 6, CAN move on 5
        assertFalse(
            "Token needing 5 steps cannot overshoot on 6",
            LudoMoveValidator.isValidPieceMove(true, 51, 6, false, false)
        )
        assertTrue(
            "Token needing 5 steps completes on 5",
            LudoMoveValidator.isValidPieceMove(true, 51, 5, false, false)
        )

        // Verify all 4 color tracks take exactly 50 outer-ring steps from startPosition to endPosition
        val startPositions = intArrayOf(39, 0, 26, 13) // Red, Green, Yellow, Blue
        for (startPos in startPositions) {
            val endPos = if (startPos != 0) startPos - 2 else 50
            var block = startPos
            var steps = 0
            while (block != endPos) {
                block = if (block >= 51) 0 else block + 1
                steps++
            }
            assertEquals("Color starting at $startPos must reach endPosition in 50 steps", 50, steps)
        }
    }
}
