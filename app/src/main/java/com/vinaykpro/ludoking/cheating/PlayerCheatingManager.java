package com.vinaykpro.ludoking.cheating;




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
    private static final long DOUBLE_TAP_WINDOW_MS = 5000L;

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
        public long firstArrowTapTime = 0L;
        public boolean isSixLockPending = false;
        public boolean isSixLockConsumed = false;

        public PlayerState(String color) {
            this.color = color;
        }

        public void resetForNewTurn() {
            // Ability state is preserved across extra turns (if 6 was rolled),
            // but if turn rotates normally to another player, non-preserved state resets.
            firstArrowTapTime = 0L;
        }

        public void resetFull() {
            isAbilityEnabled = false;
            firstArrowTapTime = 0L;
            isSixLockPending = false;
            isSixLockConsumed = false;
        }
    }

    // Selected colors from Settings (ephemeral in memory; never saved to disk, resets on match end / restart)
    private final Set<String> selectedColors = Collections.synchronizedSet(new HashSet<>());

    // Per-player live state
    private final Map<String, PlayerState> playerStates = Collections.synchronizedMap(new HashMap<>());

    // Active match flag
    private volatile boolean isMatchActive = false;

    // Pending forced roll result (null if normal roll)
    private volatile Integer pendingForcedRoll = null;

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

    private String normalizeColor(@Nullable String color) {
        if (color == null) return "";
        return color.trim().toLowerCase(Locale.US);
    }

    // --- Settings Configuration API ---

    public boolean isPlayerSelected(@Nullable String color) {
        return selectedColors.contains(normalizeColor(color));
    }

    public synchronized void setPlayerSelected(@Nullable String color, boolean selected) {
        String c = normalizeColor(color);
        if (c.isEmpty()) return;
        if (selected) {
            selectedColors.add(c);
        } else {
            selectedColors.remove(c);
        }
        logD(TAG, "Player " + c + " selected=" + selected + ", activeSelections=" + selectedColors);
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

    public void onMatchStarted() {
        isMatchActive = true;
        pendingForcedRoll = null;
        for (PlayerState s : playerStates.values()) {
            s.resetFull();
        }
        logI(TAG, "Match started. Selected players: " + selectedColors);
    }

    /**
     * Resets all special states, pending six locks, and all four Settings checkboxes to OFF.
     * Called on any match-end path (victory, exit, restart).
     */
    public synchronized void onMatchEnded() {
        isMatchActive = false;
        pendingForcedRoll = null;
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
     * Arrow gesture handler for the active player.
     * @param activePlayerColor The active player's color.
     * @return true if an arrow tap was handled/consumed for an active selected player.
     */
    public synchronized boolean onArrowTapped(@Nullable String activePlayerColor) {
        String color = normalizeColor(activePlayerColor);
        if (!selectedColors.contains(color)) {
            // Player was not selected in Settings; normal gameplay
            return false;
        }

        PlayerState state = playerStates.get(color);
        if (state == null) return false;

        long now = getCurrentTimeMs();

        if (!state.isAbilityEnabled) {
            // First tap: immediately enable ability and start 5-second double-tap detection window
            state.isAbilityEnabled = true;
            state.firstArrowTapTime = now;
            logI(TAG, "Player " + color + " ability ENABLED via arrow tap. Double-tap window started.");
            return true;
        } else {
            // Ability already enabled. Check if this is a second tap within 5 seconds
            long elapsed = now - state.firstArrowTapTime;
            if (elapsed <= DOUBLE_TAP_WINDOW_MS && state.firstArrowTapTime > 0) {
                // Second tap within 5 seconds
                if (!state.isSixLockConsumed && !state.isSixLockPending) {
                    state.isSixLockPending = true;
                    // Reset tap timer so subsequent taps don't re-trigger
                    state.firstArrowTapTime = 0L;
                    logI(TAG, "Player " + color + " activated SIX LOCK!");
                } else {
                    logI(TAG, "Player " + color + " second tap within 5s ignored: six lock already consumed or pending.");
                }
            } else {
                // Second tap occurred after 5 seconds: keep ability enabled, do not lock six
                logI(TAG, "Player " + color + " arrow tapped after 5s window (" + elapsed + "ms). Ability remains enabled.");
            }
            return true;
        }
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
     * 2. Targeted quadrant gesture (1..4) or Token tap gesture (5)
     * 3. Normal roll (null)
     *
     * @param activePlayerColor The active player's color
     * @param gestureNumber The number selected by dice quadrant (1..4) or token tap (5), or null if regular tap
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

        // Priority 2: Gesture number (1..5)
        if (gestureNumber != null && gestureNumber >= 1 && gestureNumber <= 5) {
            logI(TAG, "Resolving roll for " + color + ": Gesture selection (" + gestureNumber + ").");
            return gestureNumber;
        }

        // No forced selection
        return null;
    }

    /**
     * Called by game engine after the rolled number has been successfully applied to the dice.
     * Updates ability states according to Rule 8:
     * - If result is 6 through SIX LOCK: consume six lock, mark permanently used, keep ability ON for extra turn.
     * - If result is 1..5: disable ability, clear states.
     */
    public synchronized void onRollResultApplied(@Nullable String activePlayerColor, int rolledValue) {
        String color = normalizeColor(activePlayerColor);
        if (!selectedColors.contains(color)) return;

        PlayerState state = playerStates.get(color);
        if (state == null) return;

        if (rolledValue == 6 && state.isSixLockPending) {
            state.isSixLockPending = false;
            state.isSixLockConsumed = true;
            // Keep ability ON for extra turn granted by six
            state.isAbilityEnabled = true;
            state.firstArrowTapTime = 0L;
            logI(TAG, "Player " + color + " rolled locked 6. Six-lock consumed permanently. Ability remains ON for extra turn.");
        } else {
            // Results 1..5 (or natural roll): automatically disable ability
            state.isAbilityEnabled = false;
            state.isSixLockPending = false;
            state.firstArrowTapTime = 0L;
            logI(TAG, "Player " + color + " rolled " + rolledValue + ". Ability automatically DISABLED.");
        }
    }

    /**
     * Called when turns switch. Ensures clean state isolation between players.
     */
    public synchronized void onPlayerTurnChanged(@Nullable String nextPlayerColor) {
        // Cancel stale timers on all players
        for (PlayerState s : playerStates.values()) {
            s.firstArrowTapTime = 0L;
        }
        pendingForcedRoll = null;
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
    private static long getCurrentTimeMs() {
        return System.currentTimeMillis();
    }

}
