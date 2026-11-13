package com.vinaykpro.ludoking.cheating

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests verifying the global Strict Maximum of Two Sixes Per Turn rule
 * across ALL roll sources (RANDOM, CONTROLLER, ABILITY, OTHER), turn-change reset semantics,
 * duplicate callback protection, and asynchronous stale-turn Controller rejection.
 */
class TurnSixRuleValidatorTest {

    private lateinit var validator: TurnSixRuleValidator

    @Before
    fun setUp() {
        validator = TurnSixRuleValidator.getInstance()
        validator.resetMatch()
    }

    @After
    fun tearDown() {
        validator.resetMatch()
    }

    @Test
    fun testMaxTwoSixesPerTurnFromRandomRolls() {
        val turnId = validator.onTurnChanged("red", 1)

        // Roll 1: 6 -> accepted (count = 1)
        val r1 = validator.validateAndAcceptRoll(
            validator.beginRollEvent(), turnId, "red", 1, 6, TurnSixRuleValidator.RollSource.RANDOM, null
        )
        assertTrue(r1.accepted)
        assertEquals(6, r1.finalValue)
        assertEquals(1, r1.sixCountAfterRoll)

        // Roll 2: 6 -> accepted (count = 2)
        val r2 = validator.validateAndAcceptRoll(
            validator.beginRollEvent(), turnId, "red", 1, 6, TurnSixRuleValidator.RollSource.RANDOM, null
        )
        assertTrue(r2.accepted)
        assertEquals(6, r2.finalValue)
        assertEquals(2, r2.sixCountAfterRoll)

        // Roll 3: 6 -> REJECTED with turn forfeit
        val r3 = validator.validateAndAcceptRoll(
            validator.beginRollEvent(), turnId, "red", 1, 6, TurnSixRuleValidator.RollSource.RANDOM, null
        )
        assertFalse("Third six must never be accepted", r3.accepted)
        assertTrue("Third six must forfeit the current turn", r3.shouldForfeitTurn)
        assertEquals(TurnSixRuleValidator.RejectionReason.THIRD_SIX_FORFEIT, r3.reason)
        assertEquals(-1, r3.finalValue)
    }

    @Test
    fun testThirdSixRejectedEvenWhenMixedAcrossRandomControllerAndAbility() {
        val turnId = validator.onTurnChanged("red", 1)

        // 1st six via RANDOM
        val r1 = validator.validateAndAcceptRoll(
            validator.beginRollEvent(), turnId, "red", 1, 6, TurnSixRuleValidator.RollSource.RANDOM, null
        )
        assertTrue(r1.accepted)

        // 2nd six via ABILITY long-press
        val r2 = validator.validateAndAcceptRoll(
            validator.beginRollEvent(), turnId, "red", 1, 6, TurnSixRuleValidator.RollSource.ABILITY, null
        )
        assertTrue(r2.accepted)
        assertEquals(2, validator.sixCountInCurrentTurn)

        // 3rd six via CONTROLLER must STILL be rejected and forfeit turn
        val r3Controller = validator.validateAndAcceptRoll(
            validator.beginRollEvent(), turnId, "red", 1, 6, TurnSixRuleValidator.RollSource.CONTROLLER, "ctrl_req_3"
        )
        assertFalse(r3Controller.accepted)
        assertTrue(r3Controller.shouldForfeitTurn)
        assertEquals(TurnSixRuleValidator.RejectionReason.THIRD_SIX_FORFEIT, r3Controller.reason)

        // And a subsequent 3rd six attempt via ABILITY also forfeits
        val r3Ability = validator.validateAndAcceptRoll(
            validator.beginRollEvent(), turnId, "red", 1, 6, TurnSixRuleValidator.RollSource.ABILITY, null
        )
        assertFalse(r3Ability.accepted)
        assertTrue(r3Ability.shouldForfeitTurn)
        assertEquals(TurnSixRuleValidator.RejectionReason.THIRD_SIX_FORFEIT, r3Ability.reason)
    }

    @Test
    fun testControllerOnlyThreeSixesInSameTurnRejected() {
        val turnId = validator.onTurnChanged("blue", 4)

        val c1 = validator.validateAndAcceptRoll(
            validator.beginRollEvent(), turnId, "blue", 4, 6, TurnSixRuleValidator.RollSource.CONTROLLER, "req_1"
        )
        assertTrue(c1.accepted)

        val c2 = validator.validateAndAcceptRoll(
            validator.beginRollEvent(), turnId, "blue", 4, 6, TurnSixRuleValidator.RollSource.CONTROLLER, "req_2"
        )
        assertTrue(c2.accepted)

        val c3 = validator.validateAndAcceptRoll(
            validator.beginRollEvent(), turnId, "blue", 4, 6, TurnSixRuleValidator.RollSource.CONTROLLER, "req_3"
        )
        assertFalse(c3.accepted)
        assertTrue(c3.shouldForfeitTurn)
        assertEquals(TurnSixRuleValidator.RejectionReason.THIRD_SIX_FORFEIT, c3.reason)
    }

    @Test
    fun testCounterNotResetOnNonSixExtraTurnsOnlyResetsOnPlayerChange() {
        val turnRed = validator.onTurnChanged("red", 1)

        // Six -> 1
        assertTrue(
            validator.validateAndAcceptRoll(
                validator.beginRollEvent(), turnRed, "red", 1, 6, TurnSixRuleValidator.RollSource.RANDOM, null
            ).accepted
        )
        // Non-six roll (e.g. 4 that captures a token and grants extra turn without changing player)
        val nonSix = validator.validateAndAcceptRoll(
            validator.beginRollEvent(), turnRed, "red", 1, 4, TurnSixRuleValidator.RollSource.RANDOM, null
        )
        assertTrue(nonSix.accepted)
        assertEquals("Non-six roll in same turn must NOT reset six counter", 1, validator.sixCountInCurrentTurn)

        // Second six -> 2
        assertTrue(
            validator.validateAndAcceptRoll(
                validator.beginRollEvent(), turnRed, "red", 1, 6, TurnSixRuleValidator.RollSource.RANDOM, null
            ).accepted
        )
        // Another non-six (e.g. 2 reaching home & granting extra turn) -> count still 2
        assertTrue(
            validator.validateAndAcceptRoll(
                validator.beginRollEvent(), turnRed, "red", 1, 2, TurnSixRuleValidator.RollSource.RANDOM, null
            ).accepted
        )
        assertEquals(2, validator.sixCountInCurrentTurn)

        // Third six in the same turn -> forfeited
        val thirdSix = validator.validateAndAcceptRoll(
            validator.beginRollEvent(), turnRed, "red", 1, 6, TurnSixRuleValidator.RollSource.ABILITY, null
        )
        assertFalse(thirdSix.accepted)
        assertTrue(thirdSix.shouldForfeitTurn)

        // Now turn rotates to green -> counter resets to 0
        val turnGreen = validator.onTurnChanged("green", 2)
        assertEquals(0, validator.sixCountInCurrentTurn)

        val greenFirstSix = validator.validateAndAcceptRoll(
            validator.beginRollEvent(), turnGreen, "green", 2, 6, TurnSixRuleValidator.RollSource.RANDOM, null
        )
        assertTrue(greenFirstSix.accepted)
        assertEquals(1, greenFirstSix.sixCountAfterRoll)
    }

    @Test
    fun testStaleAsyncControllerOrTurnCallbackCannotApplyToDifferentTurn() {
        val turnRed = validator.onTurnChanged("red", 1)
        // Turn switches to yellow before stale red event completes
        val turnYellow = validator.onTurnChanged("yellow", 3)

        // Stale event bound to turnRed must be rejected without touching yellow's six counter
        val staleByTurnId = validator.validateAndAcceptRoll(
            validator.beginRollEvent(), turnRed, "red", 1, 6, TurnSixRuleValidator.RollSource.CONTROLLER, "stale_req"
        )
        assertFalse(staleByTurnId.accepted)
        assertFalse("Stale turn callback must not forfeit the new player's turn", staleByTurnId.shouldForfeitTurn)
        assertEquals(TurnSixRuleValidator.RejectionReason.STALE_TURN, staleByTurnId.reason)
        assertEquals(0, validator.sixCountInCurrentTurn)

        // Even with turnYellow ID, mismatched player/channel is rejected
        val wrongPlayer = validator.validateAndAcceptRoll(
            validator.beginRollEvent(), turnYellow, "red", 1, 6, TurnSixRuleValidator.RollSource.CONTROLLER, "wrong_player_req"
        )
        assertFalse(wrongPlayer.accepted)
        assertEquals(TurnSixRuleValidator.RejectionReason.STALE_TURN, wrongPlayer.reason)
        assertEquals(0, validator.sixCountInCurrentTurn)
    }

    @Test
    fun testDuplicateRollEventAndDuplicateControllerRequestRejectedWithoutDoubleCounting() {
        val turnId = validator.onTurnChanged("yellow", 3)
        val eventId = validator.beginRollEvent()

        val first = validator.validateAndAcceptRoll(
            eventId, turnId, "yellow", 3, 6, TurnSixRuleValidator.RollSource.CONTROLLER, "dup_ctrl_1"
        )
        assertTrue(first.accepted)
        assertEquals(1, validator.sixCountInCurrentTurn)

        // Duplicate roll event callback -> must be rejected and NOT increment counter to 2
        val dupEvent = validator.validateAndAcceptRoll(
            eventId, turnId, "yellow", 3, 6, TurnSixRuleValidator.RollSource.CONTROLLER, "other_req"
        )
        assertFalse(dupEvent.accepted)
        assertEquals(TurnSixRuleValidator.RejectionReason.DUPLICATE_EVENT, dupEvent.reason)
        assertEquals(1, validator.sixCountInCurrentTurn)

        // Duplicate Controller requestId on a new rollEventId -> must also be rejected without double-counting
        val dupRequest = validator.validateAndAcceptRoll(
            validator.beginRollEvent(), turnId, "yellow", 3, 6, TurnSixRuleValidator.RollSource.CONTROLLER, "dup_ctrl_1"
        )
        assertFalse(dupRequest.accepted)
        assertEquals(TurnSixRuleValidator.RejectionReason.DUPLICATE_EVENT, dupRequest.reason)
        assertEquals(1, validator.sixCountInCurrentTurn)
    }

    @Test
    fun testGlobalLimitHelperSynchronizesAbilityWithRandomAndControllerAndForfeitsThirdSix() {
        val turnRed = validator.onTurnChanged("red", 1)
        assertFalse(validator.isSixLimitReachedForTurn("red"))

        // 1st six: RANDOM
        assertTrue(
            validator.validateAndAcceptRoll(
                validator.beginRollEvent(), turnRed, "red", 1, 6, TurnSixRuleValidator.RollSource.RANDOM, null
            ).accepted
        )
        assertFalse(validator.isSixLimitReachedForTurn("red"))

        // 2nd six: CONTROLLER
        assertTrue(
            validator.validateAndAcceptRoll(
                validator.beginRollEvent(), turnRed, "red", 1, 6, TurnSixRuleValidator.RollSource.CONTROLLER, "ctrl_mix_2"
            ).accepted
        )
        assertTrue("Global limit helper must now report limit reached for red", validator.isSixLimitReachedForTurn("red"))

        // Any 3rd six from ABILITY (or any source) forfeits turn cleanly without incrementing beyond 2
        val forfeit = validator.validateAndAcceptRoll(
            validator.beginRollEvent(), turnRed, "red", 1, 6, TurnSixRuleValidator.RollSource.ABILITY, null
        )
        assertFalse(forfeit.accepted)
        assertTrue(forfeit.shouldForfeitTurn)
        assertEquals(TurnSixRuleValidator.RejectionReason.THIRD_SIX_FORFEIT, forfeit.reason)
        assertEquals(2, validator.sixCountInCurrentTurn)

        // Genuine turn rotation resets limit
        validator.onTurnChanged("green", 2)
        assertFalse(validator.isSixLimitReachedForTurn("green"))
        assertEquals(0, validator.sixCountInCurrentTurn)
    }
}
