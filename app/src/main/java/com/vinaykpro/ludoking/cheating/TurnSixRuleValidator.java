package com.vinaykpro.ludoking.cheating;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Centralized global validator enforcing the strict maximum of TWO sixes per turn
 * across every dice-result source (normal random, Controller commands, ability long-press,
 * or any other forced-number path).
 *
 * Guarantees:
 * - A third 6 in the same turn is NEVER accepted, never displayed, never moves a token,
 *   and forfeits the active turn.
 * - The per-turn six counter resets ONLY when the active turn genuinely changes to another player.
 * - Extra turns, captures, token movements, and non-six rolls within the same turn do NOT reset the counter.
 * - Stale turn callbacks and duplicate roll/controller events are rejected without double-counting.
 */
public class TurnSixRuleValidator {

    public static final int MAX_SIXES_PER_TURN = 2;

    public enum RollSource {
        RANDOM,
        CONTROLLER,
        ABILITY,
        OTHER
    }

    public enum RejectionReason {
        NONE,
        THIRD_SIX_FORFEIT,
        STALE_TURN,
        DUPLICATE_EVENT,
        INVALID_VALUE
    }

    public static final class ValidationResult {
        public final boolean accepted;
        public final int finalValue;
        public final boolean shouldForfeitTurn;
        @NonNull
        public final RejectionReason reason;
        public final int sixCountAfterRoll;
        public final long turnId;

        private ValidationResult(
                boolean accepted,
                int finalValue,
                boolean shouldForfeitTurn,
                @NonNull RejectionReason reason,
                int sixCountAfterRoll,
                long turnId
        ) {
            this.accepted = accepted;
            this.finalValue = finalValue;
            this.shouldForfeitTurn = shouldForfeitTurn;
            this.reason = reason;
            this.sixCountAfterRoll = sixCountAfterRoll;
            this.turnId = turnId;
        }
    }

    private static final TurnSixRuleValidator INSTANCE = new TurnSixRuleValidator();

    public static TurnSixRuleValidator getInstance() {
        return INSTANCE;
    }

    private long currentTurnId = 0L;
    @Nullable
    private String currentPlayerColor = null;
    private int currentChannel = -1;
    private int sixCountInCurrentTurn = 0;
    private long nextRollEventId = 1L;
    private final Set<Long> processedRollEventIds = new HashSet<>();
    private final Set<String> processedControllerRequestIds = new HashSet<>();

    /**
     * Resets all match-scoped turn state when a new match starts or ends.
     */
    public synchronized void resetMatch() {
        currentTurnId = 0L;
        currentPlayerColor = null;
        currentChannel = -1;
        sixCountInCurrentTurn = 0;
        processedRollEventIds.clear();
        processedControllerRequestIds.clear();
    }

    /**
     * Called ONLY when the active turn genuinely transitions to the next player via switchPlayers().
     * Must NOT be called on extra turns, captures, or home completions for the same player.
     *
     * @return The new monotonic turn ID.
     */
    public synchronized long onTurnChanged(@Nullable String nextPlayerColor, int nextChannel) {
        currentTurnId++;
        currentPlayerColor = normalizeColor(nextPlayerColor);
        currentChannel = nextChannel;
        sixCountInCurrentTurn = 0;
        processedRollEventIds.clear();
        return currentTurnId;
    }

    /**
     * Allocates a unique monotonic roll event ID to prevent duplicate callbacks from
     * counting or applying the same dice roll twice.
     */
    public synchronized long beginRollEvent() {
        return nextRollEventId++;
    }

    public synchronized long getCurrentTurnId() {
        return currentTurnId;
    }

    @Nullable
    public synchronized String getCurrentPlayerColor() {
        return currentPlayerColor;
    }

    public synchronized int getCurrentChannel() {
        return currentChannel;
    }

    public synchronized int getSixCountInCurrentTurn() {
        return sixCountInCurrentTurn;
    }

    /**
     * Single source of truth used by ability gesture checks so they never maintain
     * a separate six counter that could disagree with the global turn counter.
     */
    public synchronized boolean isSixLimitReachedForTurn(@Nullable String playerColor) {
        if (currentTurnId <= 0L) {
            return false;
        }
        if (currentPlayerColor != null && !currentPlayerColor.isEmpty() && playerColor != null) {
            String norm = normalizeColor(playerColor);
            if (!norm.isEmpty() && !currentPlayerColor.equals(norm)) {
                return false;
            }
        }
        return sixCountInCurrentTurn >= MAX_SIXES_PER_TURN;
    }

    /**
     * Validates and atomically records a candidate dice result BEFORE it is displayed or committed.
     */
    @NonNull
    public synchronized ValidationResult validateAndAcceptRoll(
            long rollEventId,
            long expectedTurnId,
            @Nullable String playerColor,
            int channel,
            int candidateValue,
            @NonNull RollSource source,
            @Nullable String controllerRequestId
    ) {
        // 1. Prevent duplicate roll callbacks from counting/applying twice
        if (rollEventId <= 0L || !processedRollEventIds.add(rollEventId)) {
            return new ValidationResult(false, -1, false, RejectionReason.DUPLICATE_EVENT, sixCountInCurrentTurn, currentTurnId);
        }

        // 2. Reject stale results intended for an earlier turn or different player/channel
        String normPlayer = normalizeColor(playerColor);
        if (currentTurnId <= 0L || expectedTurnId != currentTurnId) {
            return new ValidationResult(false, -1, false, RejectionReason.STALE_TURN, sixCountInCurrentTurn, currentTurnId);
        }
        if (currentPlayerColor != null && !currentPlayerColor.isEmpty() && !currentPlayerColor.equals(normPlayer)) {
            return new ValidationResult(false, -1, false, RejectionReason.STALE_TURN, sixCountInCurrentTurn, currentTurnId);
        }
        if (currentChannel > 0 && channel > 0 && currentChannel != channel) {
            return new ValidationResult(false, -1, false, RejectionReason.STALE_TURN, sixCountInCurrentTurn, currentTurnId);
        }

        // 3. Prevent duplicate Controller requestId replay/callbacks
        if (controllerRequestId != null && !controllerRequestId.trim().isEmpty()) {
            if (!processedControllerRequestIds.add(controllerRequestId.trim())) {
                return new ValidationResult(false, -1, false, RejectionReason.DUPLICATE_EVENT, sixCountInCurrentTurn, currentTurnId);
            }
        }

        // 4. Value bounds validation
        if (candidateValue < 1 || candidateValue > 6) {
            return new ValidationResult(false, -1, false, RejectionReason.INVALID_VALUE, sixCountInCurrentTurn, currentTurnId);
        }

        // 5. Global strict two-six-per-turn cap across all sources
        if (candidateValue == 6) {
            if (sixCountInCurrentTurn >= MAX_SIXES_PER_TURN) {
                return new ValidationResult(false, -1, true, RejectionReason.THIRD_SIX_FORFEIT, sixCountInCurrentTurn, currentTurnId);
            }
            sixCountInCurrentTurn++;
            return new ValidationResult(true, 6, false, RejectionReason.NONE, sixCountInCurrentTurn, currentTurnId);
        }

        // Non-six values (1..5) are accepted without resetting sixCountInCurrentTurn;
        // sixCountInCurrentTurn resets only when onTurnChanged() switches to another player.
        return new ValidationResult(true, candidateValue, false, RejectionReason.NONE, sixCountInCurrentTurn, currentTurnId);
    }

    @NonNull
    private static String normalizeColor(@Nullable String color) {
        return color == null ? "" : color.trim().toLowerCase(Locale.US);
    }
}
