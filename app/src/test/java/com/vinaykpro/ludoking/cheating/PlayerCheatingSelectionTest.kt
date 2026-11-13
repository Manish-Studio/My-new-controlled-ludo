package com.vinaykpro.ludoking.cheating

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for the updated Player Cheating Selection feature:
 * - All checkboxes OFF by default and locked while match is active
 * - Activation ONLY via active human player's own default token PNG beside the dice
 * - Non-selected players, wrong-position token PNGs, bots, and arrow taps cannot activate ability
 * - > 3-second dice hold (SystemClock.elapsedRealtime monotonic clock) requests SIX LOCK and triggers at most ONE roll
 * - Short tap (<= 3000ms) never triggers SIX LOCK and uses quadrant / pending 5-lock rules
 * - Token tap locks 5 (does not roll immediately), then dice tap rolls 5
 * - SIX LOCK priority over pending 5 and corners, consumed only after applied
 * - Session-safe match end reset and turn isolation
 */
class PlayerCheatingSelectionTest {

    private lateinit var manager: PlayerCheatingManager
    private lateinit var holdTracker: DiceHoldTracker
    private var fakeElapsedMs: Long = 100_000L

    @Before
    fun setUp() {
        manager = PlayerCheatingManager.getInstance()
        fakeElapsedMs = 100_000L
        val clock = PlayerCheatingManager.TimeSource { fakeElapsedMs }
        manager.setTimeSource(clock)
        holdTracker = DiceHoldTracker(clock)
        TurnSixRuleValidator.getInstance().resetMatch()
        manager.onMatchEnded()
    }

    @After
    fun tearDown() {
        TurnSixRuleValidator.getInstance().resetMatch()
        manager.onMatchEnded()
        manager.resetTimeSource()
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

    // 2. Active human player enables ability ONLY by tapping their OWN default token PNG beside dice
    @Test
    fun testDefaultTokenPngTapActivatesOnlySelectedActiveHumanPlayer() {
        assertTrue(manager.setPlayerSelected("red", true))
        manager.onMatchStarted()

        // Before tapping own token PNG beside dice, ability is OFF
        assertFalse(manager.isAbilityActiveForPlayer("red"))

        // Tapping another player's token PNG position (pos 2 != red's pos 3) must NOT enable ability
        assertFalse(
            "Other player's token PNG must not enable red's ability",
            manager.onDefaultTokenPngTapped(2, 3, "red", false)
        )
        assertFalse(manager.isAbilityActiveForPlayer("red"))

        // Tapping own token PNG as a bot must NOT enable ability
        assertFalse(
            "Bot player must not enable ability via token PNG",
            manager.onDefaultTokenPngTapped(3, 3, "red", true)
        )
        assertFalse(manager.isAbilityActiveForPlayer("red"))

        // Active human player tapping own default token PNG (pos 3 == 3) enables ability
        assertTrue(
            "Selected active human tapping own token PNG must enable ability",
            manager.onDefaultTokenPngTapped(3, 3, "red", false)
        )
        assertTrue(manager.isAbilityActiveForPlayer("red"))

        // Non-selected player (yellow at pos 2) tapping own token PNG cannot enable ability
        assertFalse(
            "Non-selected player tapping own token PNG must NOT enable ability",
            manager.onDefaultTokenPngTapped(2, 2, "yellow", false)
        )
        assertFalse(manager.isAbilityActiveForPlayer("yellow"))
    }

    // 3. Verify legacy arrow-tap method was removed cleanly
    @Test
    fun testArrowTapMethodRemovedFromManager() {
        val methods = PlayerCheatingManager::class.java.declaredMethods.map { it.name }
        assertFalse("onArrowTapped must be completely removed", methods.contains("onArrowTapped"))
    }

    // 4. Holding dice > 3s via monotonic clock triggers SIX LOCK once and suppresses trailing click
    @Test
    fun testDiceLongPressOverThreeSecondsTriggersSixLockOnce() {
        manager.setPlayerSelected("red", true)
        manager.onMatchStarted()
        assertTrue(manager.onDefaultTokenPngTapped(3, 3, "red", false))

        // Press down inside 100x100 dice bounds
        val token = holdTracker.onTouchDown(20f, 20f, 100, 100)

        // At exactly 3000ms (not > 3000ms), timeout must NOT fire long press
        fakeElapsedMs += 3000L
        assertEquals(
            DiceHoldTracker.TimeoutOutcome.NEED_MORE_WAIT,
            holdTracker.onLongPressTimeout(token)
        )
        assertFalse(manager.getPlayerState("red")!!.isSixLockPending)

        // At 3001ms (> 3000ms), timeout fires TRIGGER_LONG_PRESS
        fakeElapsedMs += 1L
        assertEquals(
            DiceHoldTracker.TimeoutOutcome.TRIGGER_LONG_PRESS,
            holdTracker.onLongPressTimeout(token)
        )
        assertTrue(
            manager.onDiceLongPressed("red", false, DiceHoldTracker.LONG_PRESS_DURATION_MS + 1L)
        )
        assertTrue(manager.getPlayerState("red")!!.isSixLockPending)
        assertEquals(6, manager.resolveRollNumber("red", 1))

        // Releasing finger after timeout MUST NOT trigger a second roll or click callback
        val upResult = holdTracker.onTouchUp(20f, 20f, 100, 100)
        assertEquals(DiceHoldTracker.TouchUpOutcome.NONE, upResult.outcome)
        assertTrue(
            "Trailing OnClickListener callback must be suppressed after long press",
            holdTracker.shouldSuppressClickCallback()
        )
    }

    // 5. Normal short tap (<= 3000ms) does NOT lock 6 and resolves quadrant normally
    @Test
    fun testShortTapAtOrUnderThreeSecondsUsesNormalQuadrantSelection() {
        manager.setPlayerSelected("green", true)
        manager.onMatchStarted()
        assertTrue(manager.onDefaultTokenPngTapped(1, 1, "green", false))

        holdTracker.onTouchDown(75f, 20f, 100, 100)
        fakeElapsedMs += 3000L // Boundary: <= 3000ms is NOT a long press
        val upResult = holdTracker.onTouchUp(75f, 20f, 100, 100)

        assertEquals(DiceHoldTracker.TouchUpOutcome.SHORT_TAP, upResult.outcome)
        assertFalse(holdTracker.shouldSuppressClickCallback())
        assertFalse(manager.onDiceLongPressed("green", false, upResult.holdDurationMs))
        assertFalse(manager.getPlayerState("green")!!.isSixLockPending)

        val quadrant = manager.getQuadrantNumber(upResult.touchX / 100f, upResult.touchY / 100f)
        assertEquals(2, quadrant)
        assertEquals(2, manager.resolveRollNumber("green", quadrant))
    }

    // 6. Moving finger out of dice bounds invalidates continuous hold for long press
    @Test
    fun testMovingOutsideBoundsCancelsLongPressHold() {
        manager.setPlayerSelected("red", true)
        manager.onMatchStarted()
        assertTrue(manager.onDefaultTokenPngTapped(3, 3, "red", false))

        val token = holdTracker.onTouchDown(50f, 50f, 100, 100)
        fakeElapsedMs += 1500L
        holdTracker.onTouchMove(150f, 50f, 100, 100) // outside bounds

        fakeElapsedMs += 2000L
        assertEquals(DiceHoldTracker.TimeoutOutcome.IGNORED, holdTracker.onLongPressTimeout(token))
        assertEquals(DiceHoldTracker.TouchUpOutcome.NONE, holdTracker.onTouchUp(150f, 50f, 100, 100).outcome)
        assertFalse(manager.getPlayerState("red")!!.isSixLockPending)
    }

    // 7. Six lock priority over corner gestures and pending token-5 lock
    @Test
    fun testSixLockPriorityOverCornerGesturesAndFiveLock() {
        manager.setPlayerSelected("green", true)
        manager.onMatchStarted()
        assertTrue(manager.onDefaultTokenPngTapped(1, 1, "green", false))
        assertTrue(manager.onTokenTappedForFive("green"))
        assertTrue(manager.isFiveLockPendingForPlayer("green"))

        // Long press requests SIX LOCK, overriding pending FIVE LOCK
        assertTrue(manager.onDiceLongPressed("green", false, 3500L))
        val state = manager.getPlayerState("green")!!
        assertTrue(state.isSixLockPending)
        assertFalse("Pending 5 must be cleared when SIX LOCK activates", manager.isFiveLockPendingForPlayer("green"))

        // Token tap while SIX LOCK is pending must be ignored
        assertFalse("Token tap must be ignored while SIX LOCK is pending", manager.onTokenTappedForFive("green"))

        // Corners 1..5 all resolve to 6
        for (corner in 1..5) {
            assertEquals("SIX LOCK must have highest priority over gesture $corner", 6, manager.resolveRollNumber("green", corner))
        }
    }

    // 8. Four dice quadrants
    @Test
    fun testFourQuadrantCalculations() {
        assertEquals(1, manager.getQuadrantNumber(0.1f, 0.1f))
        assertEquals(1, manager.getQuadrantNumber(0.49f, 0.49f))
        assertEquals(2, manager.getQuadrantNumber(0.5f, 0.1f))
        assertEquals(2, manager.getQuadrantNumber(0.9f, 0.49f))
        assertEquals(3, manager.getQuadrantNumber(0.1f, 0.5f))
        assertEquals(3, manager.getQuadrantNumber(0.49f, 0.9f))
        assertEquals(4, manager.getQuadrantNumber(0.5f, 0.5f))
        assertEquals(4, manager.getQuadrantNumber(0.99f, 0.99f))
    }

    // 9. Board token single tap locks 5 (does not roll immediately), then dice tap rolls 5 and disables ability
    @Test
    fun testBoardTokenTapLocksFiveAndDiceTapProducesFive() {
        manager.setPlayerSelected("red", true)
        manager.onMatchStarted()
        assertTrue(manager.onDefaultTokenPngTapped(3, 3, "red", false))

        assertTrue(manager.onTokenTappedForFive("red"))
        assertTrue(manager.isFiveLockPendingForPlayer("red"))
        assertTrue(manager.isAbilityActiveForPlayer("red"))

        for (quadrant in 1..4) {
            assertEquals(5, manager.resolveRollNumber("red", quadrant))
        }

        manager.onRollResultApplied("red", 5)
        assertFalse(manager.isFiveLockPendingForPlayer("red"))
        assertFalse(manager.isAbilityActiveForPlayer("red"))
    }

    @Test
    fun testBoardTokenTapDoesNothingWhenAbilityIsOff() {
        manager.setPlayerSelected("red", true)
        manager.onMatchStarted()
        assertFalse(manager.isAbilityActiveForPlayer("red"))
        assertFalse(manager.onTokenTappedForFive("red"))
        assertFalse(manager.isFiveLockPendingForPlayer("red"))
    }

    // 10. Long-press six-lock allows up to TWO sixes per turn (no once-per-match limit) and resets on next genuine turn
    @Test
    fun testOncePerMatchRestrictionRemovedFromPlayerState() {
        val fieldNames = PlayerCheatingManager.PlayerState::class.java.declaredFields.map { it.name }
        assertFalse("Obsolete once-per-match isSixLockConsumed must be removed", fieldNames.contains("isSixLockConsumed"))
    }

    @Test
    fun testLongPressCanProduceTwoSixesInSameTurnAndResetsOnNextGenuineTurn() {
        val validator = TurnSixRuleValidator.getInstance()
        manager.setPlayerSelected("red", true)
        manager.onMatchStarted()
        val turn1 = validator.onTurnChanged("red", 1)

        // Enable ability via default token PNG beside dice
        assertTrue(manager.onDefaultTokenPngTapped(3, 3, "red", false))

        // Roll 1 in Turn 1: Long press (> 3s) produces 1st six
        assertTrue(manager.canRequestSixLock("red"))
        assertTrue(manager.onDiceLongPressed("red", false, 3200L))
        val resolved1 = manager.resolveRollNumber("red", 1)
        assertEquals(6, resolved1)
        val v1 = validator.validateAndAcceptRoll(
            validator.beginRollEvent(), turn1, "red", 1, resolved1!!, TurnSixRuleValidator.RollSource.ABILITY, null
        )
        assertTrue(v1.accepted)
        assertEquals(1, v1.sixCountAfterRoll)
        manager.onRollResultApplied("red", 6)
        assertTrue("Ability stays enabled for extra turn on 1st six", manager.isAbilityActiveForPlayer("red"))
        assertFalse(manager.getPlayerState("red")!!.isSixLockPending)

        // Roll 2 in Turn 1 (extra roll, same turn): Long press (> 3s) produces 2nd six
        assertTrue("2nd long-press six in same turn must be allowed", manager.canRequestSixLock("red"))
        assertTrue(manager.onDiceLongPressed("red", false, 3500L))
        val resolved2 = manager.resolveRollNumber("red", 2)
        assertEquals(6, resolved2)
        val v2 = validator.validateAndAcceptRoll(
            validator.beginRollEvent(), turn1, "red", 1, resolved2!!, TurnSixRuleValidator.RollSource.ABILITY, null
        )
        assertTrue(v2.accepted)
        assertEquals(2, v2.sixCountAfterRoll)
        manager.onRollResultApplied("red", 6)
        assertTrue("Ability stays enabled for extra turn on 2nd six", manager.isAbilityActiveForPlayer("red"))

        // Roll 3 in Turn 1: After TWO sixes accepted in this turn, a 3rd long-press MUST NOT lock another six
        assertFalse("3rd long-press in same turn must be blocked", manager.canRequestSixLock("red"))
        assertFalse("3rd long-press in same turn must return false", manager.onDiceLongPressed("red", false, 3500L))
        assertFalse(manager.getPlayerState("red")!!.isSixLockPending)
        // Normal quadrant 1..4 still works without producing a third 6
        assertEquals(3, manager.resolveRollNumber("red", 3))
        manager.onRollResultApplied("red", 3)
        assertFalse(manager.isAbilityActiveForPlayer("red"))

        // Turn rotates to green, then back to red's next genuine turn -> long-press limit resets
        validator.onTurnChanged("green", 2)
        manager.onPlayerTurnChanged("green")
        val turnRedNext = validator.onTurnChanged("red", 1)
        manager.onPlayerTurnChanged("red")

        assertEquals(0, validator.sixCountInCurrentTurn)
        assertTrue(manager.onDefaultTokenPngTapped(3, 3, "red", false))
        assertTrue("Next genuine turn must reset long-press six eligibility", manager.canRequestSixLock("red"))
        assertTrue(manager.onDiceLongPressed("red", false, 3100L))
        val nextTurnResolved = manager.resolveRollNumber("red", null)
        assertEquals(6, nextTurnResolved)
        val vNext = validator.validateAndAcceptRoll(
            validator.beginRollEvent(), turnRedNext, "red", 1, nextTurnResolved!!, TurnSixRuleValidator.RollSource.ABILITY, null
        )
        assertTrue(vNext.accepted)
        assertEquals(1, vNext.sixCountAfterRoll)
    }

    @Test
    fun testLongPressBlockedAfterTwoSixesFromRandomAndControllerInSameTurn() {
        val validator = TurnSixRuleValidator.getInstance()
        manager.setPlayerSelected("red", true)
        manager.onMatchStarted()
        val turnId = validator.onTurnChanged("red", 1)

        // 1st six via random dice
        assertTrue(
            validator.validateAndAcceptRoll(
                validator.beginRollEvent(), turnId, "red", 1, 6, TurnSixRuleValidator.RollSource.RANDOM, null
            ).accepted
        )
        // 2nd six via Controller
        assertTrue(
            validator.validateAndAcceptRoll(
                validator.beginRollEvent(), turnId, "red", 1, 6, TurnSixRuleValidator.RollSource.CONTROLLER, "ctrl_2"
            ).accepted
        )
        assertEquals(2, validator.sixCountInCurrentTurn)

        // Red enables ability via token PNG and tries long-press -> MUST NOT produce a 3rd six
        assertTrue(manager.onDefaultTokenPngTapped(3, 3, "red", false))
        assertFalse(manager.canRequestSixLock("red"))
        assertFalse(
            "Long-press must not lock a 3rd six after random + Controller sixes",
            manager.onDiceLongPressed("red", false, 3400L)
        )
        assertNull(manager.resolveRollNumber("red", null))
    }

    // 11. Player switching does not leak state; non-selected players retain normal gameplay
    @Test
    fun testPlayerSwitchingDoesNotLeakState() {
        manager.setPlayerSelected("red", true)
        manager.onMatchStarted()

        assertTrue(manager.onDefaultTokenPngTapped(3, 3, "red", false))
        assertTrue(manager.onTokenTappedForFive("red"))

        manager.onPlayerTurnChanged("green")

        assertFalse(manager.isAbilityActiveForPlayer("green"))
        assertNull(manager.resolveRollNumber("green", 2))
        assertFalse(manager.isAbilityActiveForPlayer("red"))
        assertFalse(manager.isFiveLockPendingForPlayer("red"))
    }

    // 12. Controller blocking, active match Settings lock, and session-safe match-end reset
    @Test
    fun testControllerBlockingActiveMatchLockAndMatchEndReset() {
        assertFalse(manager.isAnyPlayerSelected())
        assertTrue(manager.setPlayerSelected("red", true))
        assertTrue(manager.isAnyPlayerSelected())
        assertTrue(manager.setPlayerSelected("red", false))
        assertFalse(manager.isAnyPlayerSelected())

        assertTrue(manager.setPlayerSelected("red", true))
        assertTrue(manager.setPlayerSelected("blue", true))
        manager.onMatchStarted()
        val session1 = manager.matchSessionId

        assertFalse(manager.setPlayerSelected("green", true))
        assertFalse(manager.setPlayerSelected("red", false))

        assertTrue(manager.onDefaultTokenPngTapped(3, 3, "red", false))
        assertTrue(manager.onDiceLongPressed("red", false, 3100L))

        assertTrue(manager.onMatchEndedIfSession(session1))
        assertFalse(manager.isMatchActive)
        assertFalse(manager.isAnyPlayerSelected())
        val redState = manager.getPlayerState("red")!!
        assertFalse(redState.isAbilityEnabled)
        assertFalse(redState.isSixLockPending)
        assertFalse(redState.isFiveLockPending)

        manager.setPlayerSelected("green", true)
        manager.onMatchStarted()
        val session2 = manager.matchSessionId
        assertFalse(manager.onMatchEndedIfSession(session1))
        assertTrue(manager.isMatchActive)
        assertTrue(manager.isPlayerSelected("green"))
        assertTrue(manager.onMatchEndedIfSession(session2))
    }

    @Test
    fun testNormalRollWhenAbilityIsOff() {
        manager.setPlayerSelected("yellow", true)
        manager.onMatchStarted()
        assertFalse(manager.isAbilityActiveForPlayer("yellow"))
        assertNull(manager.resolveRollNumber("yellow", 2))
    }
}
