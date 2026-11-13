package com.vinaykpro.ludoking.cheating;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Manages the Player Cheating Selection feature state, gestures, priority dispatching,
 * and Controller blocking rules across the Ludo game lifecycle.
 *
 * All checkboxes and state are OFF by default, reset upon match end or app restart,
 * and strictly isolated per-player during gameplay.
 */
public class PlayerCheatingManager {

    private static final String TAG = "PlayerCheatingManager";
    public static final long DICE_LONG_PRESS_THRESHOLD_MS = 3000L;

    public interface TimeSource {
        long elapsedRealtime();
    }

    private static final TimeSource SYSTEM_ELAPSED_TIME_SOURCE = SystemClock::elapsedRealtime;

    // Singleton instance
    private static final PlayerCheatingManager INSTANCE = new PlayerCheatingManager();

    public static PlayerCheatingManager getInstance() {
        return INSTANCE;
    }

    /**
     * Per-player state tracking
     */
    public static class PlayerState {
        public final String color; // "red", "green", "yellow", "blue"
        public boolean isAbilityEnabled = false;
        public boolean isSixLockPending = false;
        public boolean isFiveLockPending = false;

        public PlayerState(String color) {
            this.color = color;
        }

        public void resetForNewTurn() {
            // Per-turn transient ability state resets when the active turn changes
            isAbilityEnabled = false;
            isSixLockPending = false;
            isFiveLockPending = false;
        }

        public void resetFull() {
            isAbilityEnabled = false;
            isSixLockPending = false;
            isFiveLockPending = false;
        }
    }

    // Selected colors from Settings (ephemeral in memory; never saved to disk, resets on match end / restart)
    private final Set<String> selectedColors = Collections.synchronizedSet(new HashSet<>());

    // Per-player live state
    private final Map<String, PlayerState> playerStates = Collections.synchronizedMap(new HashMap<>());

    // Active match flag and session counter (prevents delayed onDestroy from clobbering a new match)
    private volatile boolean isMatchActive = false;
    private long matchSessionId = 0L;

    // Monotonic elapsed clock source (defaults to Android SystemClock.elapsedRealtime())
    private volatile TimeSource timeSource = SYSTEM_ELAPSED_TIME_SOURCE;

    private PlayerCheatingManager() {
        initStates();
    }

    private void initStates() {
        playerStates.clear();
        playerStates.put("red", new PlayerState("red"));
        playerStates.put("green", new PlayerState("green"));
        playerStates.put("yellow", new PlayerState("yellow"));
        playerStates.put("blue", new PlayerState("blue"));
    }

    public synchronized void setTimeSource(@Nullable TimeSource customTimeSource) {
        this.timeSource = customTimeSource != null ? customTimeSource : SYSTEM_ELAPSED_TIME_SOURCE;
    }

    public synchronized void resetTimeSource() {
        this.timeSource = SYSTEM_ELAPSED_TIME_SOURCE;
    }

    private String normalizeColor(@Nullable String color) {
        if (color == null) return "";
        return color.trim().toLowerCase(Locale.US);
    }

    // --- Settings Configuration API ---

    public boolean isPlayerSelected(@Nullable String color) {
        return selectedColors.contains(normalizeColor(color));
    }

    /**
     * Enables or disables cheating selection for a color before a match starts.
     * Modifications are blocked while a match is active to avoid mid-match state or networking conflicts.
     *
     * @return true if the selection was updated, false if ignored (invalid color or match active)
     */
    public synchronized boolean setPlayerSelected(@Nullable String color, boolean selected) {
        if (isMatchActive) {
            logW(TAG, "Blocked setPlayerSelected(" + color + ", " + selected + ") while match is active.");
            return false;
        }
        String c = normalizeColor(color);
        if (c.isEmpty()) return false;
        if (selected) {
            selectedColors.add(c);
        } else {
            selectedColors.remove(c);
        }
        logD(TAG, "Player " + c + " selected=" + selected + ", activeSelections=" + selectedColors);
        return true;
    }

    public boolean isAnyPlayerSelected() {
        return !selectedColors.isEmpty();
    }

    public Set<String> getSelectedColors() {
        synchronized (selectedColors) {
            return new HashSet<>(selectedColors);
        }
    }

    public boolean isMatchActive() {
        return isMatchActive;
    }

    public synchronized long getMatchSessionId() {
        return matchSessionId;
    }

    public synchronized void onMatchStarted() {
        matchSessionId++;
        isMatchActive = true;
        for (PlayerState s : playerStates.values()) {
            s.resetFull();
        }
        logI(TAG, "Match started (session=" + matchSessionId + "). Selected players: " + selectedColors);
    }

    /**
     * Ends the match only if the provided session ID matches the currently active match session.
     * Prevents asynchronous onDestroy() of an old Activity from resetting a newly started replay match.
     */
    public synchronized boolean onMatchEndedIfSession(long sessionId) {
        if (sessionId == this.matchSessionId && isMatchActive) {
            onMatchEnded();
            return true;
        }
        return false;
    }

    /**
     * Resets all special states, pending locks, and all four Settings checkboxes to OFF.
     * Called on any actual match-end or exit path.
     */
    public synchronized void onMatchEnded() {
        isMatchActive = false;
        selectedColors.clear();
        for (PlayerState s : playerStates.values()) {
            s.resetFull();
        }
        logI(TAG, "Match ended. Cleared all cheating states and reset checkboxes to OFF.");
    }

    // --- In-Game Gestures API ---

    @Nullable
    public PlayerState getPlayerState(@Nullable String color) {
        return playerStates.get(normalizeColor(color));
    }

    /**
     * Default token PNG tap handler (tLeftImgColor / tRightImgColor / bLeftImgColor / bRightImgColor beside dice).
     * Enables the special ability ONLY when the active human player taps their OWN token PNG
     * and their color was enabled in Player Cheating Selection.
     * Does NOT trigger a dice roll or move any board token.
     */
    public synchronized boolean onDefaultTokenPngTapped(
            int tappedPosition,
            int activePlayerPosition,
            @Nullable String activePlayerColor,
            boolean isActivePlayerBot
    ) {
        if (isActivePlayerBot || tappedPosition <= 0 || tappedPosition != activePlayerPosition) {
            return false;
        }
        String color = normalizeColor(activePlayerColor);
        if (!selectedColors.contains(color)) {
            return false;
        }
        PlayerState state = playerStates.get(color);
        if (state == null) {
            return false;
        }
        if (!state.isAbilityEnabled) {
            state.isAbilityEnabled = true;
            logI(TAG, "Player " + color + " ability ENABLED via default token PNG tap (pos=" + tappedPosition + ").");
        }
        return true;
    }

    /**
     * Checks if the active player currently has ability enabled and the global turn validator
     * has not yet reached the two-six-per-turn cap.
     */
    public synchronized boolean canRequestSixLock(@Nullable String activePlayerColor) {
        String color = normalizeColor(activePlayerColor);
        if (!selectedColors.contains(color)) return false;
        PlayerState state = playerStates.get(color);
        if (state == null || !state.isAbilityEnabled || state.isSixLockPending) {
            return false;
        }
        return !TurnSixRuleValidator.getInstance().isSixLimitReachedForTurn(color);
    }

    /**
     * Requests a pending SIX LOCK when the active human player holds the dice touch area
     * continuously for MORE THAN 3 seconds (> 3000ms) while their ability is enabled.
     * Enforces the global TurnSixRuleValidator cap (max 2 sixes per turn across all sources).
     * Priority 1: Overrides any pending FIVE LOCK.
     */
    public synchronized boolean onDiceLongPressed(
            @Nullable String activePlayerColor,
            boolean isActivePlayerBot,
            long holdDurationMs
    ) {
        if (isActivePlayerBot || holdDurationMs <= DICE_LONG_PRESS_THRESHOLD_MS) {
            return false;
        }
        String color = normalizeColor(activePlayerColor);
        if (!selectedColors.contains(color)) {
            return false;
        }
        PlayerState state = playerStates.get(color);
        if (state == null || !state.isAbilityEnabled) {
            return false;
        }
        if (state.isSixLockPending) {
            logI(TAG, "Dice long-press ignored for " + color + ": six lock already pending.");
            return false;
        }
        if (TurnSixRuleValidator.getInstance().isSixLimitReachedForTurn(color)) {
            logI(TAG, "Dice long-press ignored for " + color + ": global turn six limit (2) already reached.");
            return false;
        }
        state.isSixLockPending = true;
        state.isFiveLockPending = false;
        logI(TAG, "Player " + color + " requested SIX LOCK via dice long-press (" + holdDurationMs + "ms).");
        return true;
    }

    /**
     * Token gesture handler when ability mode is enabled (before dice roll).
     * Single tap on ANY board token locks 5 for the next dice roll without rolling immediately or moving any token.
     * Ignored if SIX LOCK is already pending (Priority 1).
     *
     * @param activePlayerColor The active player's color.
     * @return true if five-lock was set, false otherwise.
     */
    public synchronized boolean onTokenTappedForFive(@Nullable String activePlayerColor) {
        String color = normalizeColor(activePlayerColor);
        if (!selectedColors.contains(color)) {
            return false;
        }

        PlayerState state = playerStates.get(color);
        if (state == null || !state.isAbilityEnabled) {
            return false;
        }

        // Priority 1: Pending SIX LOCK must not be overwritten by numbers 1-5
        if (state.isSixLockPending) {
            logI(TAG, "Token tap ignored for " + color + ": SIX LOCK is pending.");
            return false;
        }

        state.isFiveLockPending = true;
        logI(TAG, "Player " + color + " locked 5 via token tap. Waiting for dice tap.");
        return true;
    }

    public synchronized boolean isFiveLockPendingForPlayer(@Nullable String activePlayerColor) {
        String color = normalizeColor(activePlayerColor);
        if (!selectedColors.contains(color)) return false;
        PlayerState state = playerStates.get(color);
        return state != null && state.isAbilityEnabled && state.isFiveLockPending;
    }

    /**
     * Checks if the active player has their special ability enabled.
     */
    public boolean isAbilityActiveForPlayer(@Nullable String activePlayerColor) {
        String color = normalizeColor(activePlayerColor);
        if (!selectedColors.contains(color)) return false;
        PlayerState state = playerStates.get(color);
        return state != null && state.isAbilityEnabled;
    }

    /**
     * Quadrant touch handler on dice when ability is active.
     * @param normalizedX Touch X divided by view width (0.0 .. 1.0)
     * @param normalizedY Touch Y divided by view height (0.0 .. 1.0)
     * @return Selected number (1..4) based on quadrant:
     *         Upper-left = 1, Upper-right = 2, Lower-left = 3, Lower-right = 4.
     */
    public int getQuadrantNumber(float normalizedX, float normalizedY) {
        boolean isLeft = normalizedX < 0.5f;
        boolean isTop = normalizedY < 0.5f;

        if (isTop && isLeft) {
            return 1; // Upper-left
        } else if (isTop) {
            return 2; // Upper-right
        } else if (isLeft) {
            return 3; // Lower-left
        } else {
            return 4; // Lower-right
        }
    }

    /**
     * Resolves the dice number to produce, taking priority rules into account:
     * 1. Pending SIX LOCK (highest priority -> returns 6)
     * 2. Pending FIVE LOCK from token tap (-> returns 5)
     * 3. Dice quadrant gesture (1..4) or explicit gesture (5)
     * 4. Normal roll (null)
     *
     * @param activePlayerColor The active player's color
     * @param gestureNumber The number selected by dice quadrant (1..4) or explicit 5, or null
     * @return Forced number to roll (1..6) or null if normal dice roll should proceed.
     */
    public synchronized Integer resolveRollNumber(@Nullable String activePlayerColor, @Nullable Integer gestureNumber) {
        String color = normalizeColor(activePlayerColor);
        if (!selectedColors.contains(color)) {
            return null; // Not a selected cheating player
        }

        PlayerState state = playerStates.get(color);
        if (state == null || !state.isAbilityEnabled) {
            return null; // Ability is OFF
        }

        // Priority 1: Pending SIX LOCK
        if (state.isSixLockPending) {
            logI(TAG, "Resolving roll for " + color + ": SIX LOCK has highest priority (6).");
            return 6;
        }

        // Priority 2: Pending FIVE LOCK (from token tap, awaits dice tap)
        if (state.isFiveLockPending) {
            logI(TAG, "Resolving roll for " + color + ": Pending FIVE LOCK (5).");
            return 5;
        }

        // Priority 3: Quadrant/gesture number (1..5)
        if (gestureNumber != null && gestureNumber >= 1 && gestureNumber <= 5) {
            logI(TAG, "Resolving roll for " + color + ": Gesture selection (" + gestureNumber + ").");
            return gestureNumber;
        }

        // No forced selection
        return null;
    }

    /**
     * Called by game engine ONLY AFTER the rolled number has been successfully accepted and applied to the dice.
     * Updates ability states:
     * - If result is 6 through SIX LOCK: clear pending flag and keep ability ON for the extra turn.
     *   (The global two-sixes-per-turn cap is tracked centrally by TurnSixRuleValidator.)
     * - If result is 1..5 (or natural roll): disable ability, clear transient states.
     */
    public synchronized void onRollResultApplied(@Nullable String activePlayerColor, int rolledValue) {
        String color = normalizeColor(activePlayerColor);
        if (!selectedColors.contains(color)) return;

        PlayerState state = playerStates.get(color);
        if (state == null) return;

        if (rolledValue == 6 && state.isSixLockPending) {
            state.isSixLockPending = false;
            state.isFiveLockPending = false;
            // Keep ability ON for extra turn granted by six
            state.isAbilityEnabled = true;
            logI(TAG, "Player " + color + " rolled locked 6. Ability remains ON for extra turn.");
        } else {
            // Results 1..5 (or natural roll): automatically disable ability
            state.isAbilityEnabled = false;
            state.isSixLockPending = false;
            state.isFiveLockPending = false;
            logI(TAG, "Player " + color + " rolled " + rolledValue + ". Ability automatically DISABLED.");
        }
    }

    /**
     * Called when turns rotate to another player via switchPlayers().
     * Clears transient ability/pending states for the new turn.
     * Note: Extra turns on rolling a 6 do NOT invoke onPlayerTurnChanged().
     */
    public synchronized void onPlayerTurnChanged(@Nullable String nextPlayerColor) {
        for (PlayerState s : playerStates.values()) {
            s.resetForNewTurn();
        }
    }

    private static void logD(String tag, String msg) {
        // System.out.println(tag + ": " + msg);
    }
    private static void logI(String tag, String msg) {
        // System.out.println(tag + ": " + msg);
    }
    private static void logW(String tag, String msg) {
        // System.out.println(tag + ": " + msg);
    }
    private long getCurrentTimeMs() {
        return timeSource.elapsedRealtime();
    }

}

