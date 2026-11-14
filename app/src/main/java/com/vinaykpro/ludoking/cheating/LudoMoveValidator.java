package com.vinaykpro.ludoking.cheating;

/**
 * Pure Java move-legality validator for Ludo tokens, matching MainActivity.Piece step rules.
 *
 * Step geometry across all 4 board colors:
 * - makeAlive() places a token on startPosition with numberOfSteps = 0.
 * - Traversing the outer ring to the player's colored entry cell (endPosition) takes 50 steps.
 * - Entering the 6-cell home column (winnerBlocks[0..5]) and reaching final home takes 6 more steps
 *   (numberOfSteps == 56, currWinnerBlock == 6).
 */
public final class LudoMoveValidator {

    public static final int FINAL_HOME_STEP_COUNT = 56;
    public static final int HOME_COLUMN_ENTRY_STEPS = 50;

    private LudoMoveValidator() {}

    /**
     * Evaluates whether a token can legally act on the rolled dice value.
     *
     * @param isAlive                Whether the token has exited its starting base.
     * @param numberOfSteps          Steps already taken since leaving startPosition (0..56).
     * @param diceValue              Rolled dice value (1..6).
     * @param hasCompletedItsPurpose Whether the token has already reached final home.
     * @param isThisPlayerWon        Whether the owning player has already finished/won.
     * @return true if the token is legally movable (or openable when !isAlive && diceValue == 6).
     */
    public static boolean isValidPieceMove(
            boolean isAlive,
            int numberOfSteps,
            int diceValue,
            boolean hasCompletedItsPurpose,
            boolean isThisPlayerWon
    ) {
        if (hasCompletedItsPurpose || isThisPlayerWon) {
            return false;
        }
        if (diceValue < 1 || diceValue > 6) {
            return false;
        }
        if (!isAlive) {
            return diceValue == 6;
        }
        return (numberOfSteps + diceValue) <= FINAL_HOME_STEP_COUNT;
    }
}
