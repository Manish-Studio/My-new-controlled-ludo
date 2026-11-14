package com.vinaykpro.ludoking.cheating;

import android.content.Context;
import android.content.SharedPreferences;
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
 * Manages the Player Cheating Selection feature state, password access gate,
 * persistent per-player game-duration tracking (1-10 games), gestures,
 * priority dispatching, and Controller blocking rules across the Ludo game lifecycle.
 */
public class PlayerCheatingManager {

    private static final String TAG = "PlayerCheatingManager";
    public static final long DICE_LONG_PRESS_THRESHOLD_MS = 3000L;

    public static final int MIN_DURATION_GAMES = 1;
    public static final int MAX_DURATION_GAMES = 10;
    public static final int DEFAULT_DURATION_GAMES = 1;

    private static final String FEATURE_ACCESS_CODE = "3507";
    private static final String PREFS_NAME = "LudoModUser";
    private static final String KEY_UNLOCKED = "cheat_section_unlocked";
    private static final String KEY_DURATION_GAMES = "cheat_duration_games";
    private static final String KEY_SELECTED_PREFIX = "cheat_selected_";
    private static final String KEY_REMAINING_PREFIX = "cheat_remaining_";

    private static final String[] ALL_COLORS = {"red", "green", "yellow", "blue"};

    public interface TimeSource {
        long elapsedRealtime();
    }

    /**
     * Abstraction over local preference storage so Android runtime uses SharedPreferences
     * ("LudoModUser") and JVM unit tests can simulate persistence across app restarts.
     */
    public interface PersistenceStore {
        boolean getBoolean(@NonNull String key, boolean defaultValue);
        int getInt(@NonNull String key, int defaultValue);
        void putBoolean(@NonNull String key, boolean value);
        void putInt(@NonNull String key, int value);
    }

    public static final class InMemoryPersistenceStore implements PersistenceStore {
        private final Map<String, Boolean> booleans = new HashMap<>();
        private final Map<String, Integer> ints = new HashMap<>();

        @Override
        public synchronized boolean getBoolean(@NonNull String key, boolean defaultValue) {
            Boolean v = booleans.get(key);
            return v != null ? v : defaultValue;
        }

        @Override
        public synchronized int getInt(@NonNull String key, int defaultValue) {
            Integer v = ints.get(key);
            return v != null ? v : defaultValue;
        }

        @Override
        public synchronized void putBoolean(@NonNull String key, boolean value) {
            booleans.put(key, value);
        }

        @Override
        public synchronized void putInt(@NonNull String key, int value) {
            ints.put(key, value);
        }
    }

    private static final class SharedPreferencesPersistenceStore implements PersistenceStore {
        private final SharedPreferences prefs;

        SharedPreferencesPersistenceStore(@NonNull Context context) {
            this.prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }

        @Override
        public boolean getBoolean(@NonNull String key, boolean defaultValue) {
            return prefs.getBoolean(key, defaultValue);
        }

        @Override
        public int getInt(@NonNull String key, int defaultValue) {
            return prefs.getInt(key, defaultValue);
        }

        @Override
        public void putBoolean(@NonNull String key, boolean value) {
            prefs.edit().putBoolean(key, value).apply();
        }

        @Override
        public void putInt(@NonNull String key, int value) {
            prefs.edit().putInt(key, value).apply();
        }
    }

    private static final TimeSource SYSTEM_ELAPSED_TIME_SOURCE = SystemClock::elapsedRealtime;

    // Singleton instance
    private static final PlayerCheatingManager INSTANCE = new PlayerCheatingManager();

    public static PlayerCheatingManager getInstance() {
        return INSTANCE;
    }

    /**
     * Binds Android SharedPreferences persistence and loads saved unlock/duration/player state.
     * Safe to call multiple times from LudoApplication, HomeActivity, and MainActivity.
     */
    public static void init(@NonNull Context context) {
        INSTANCE.attachSharedPreferencesIfNeeded(context);
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

    private volatile PersistenceStore persistenceStore = new InMemoryPersistenceStore();
    private boolean sharedPrefsBound = false;

    private boolean isFeatureUnlocked = false;
    private int durationGames = DEFAULT_DURATION_GAMES;

    // Selected colors and independent remaining game counts per player
    private final Set<String> selectedColors = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Integer> remainingGamesByColor = Collections.synchronizedMap(new HashMap<>());

    // Players participating in the currently active match session (decremented independently once on match end)
    private final Set<String> activeSessionColors = new HashSet<>();

    // Per-player live turn state
    private final Map<String, PlayerState> playerStates = Collections.synchronizedMap(new HashMap<>());

    // Active match flag and session counter (prevents duplicate decrements or stale onDestroy clobbering replay)
    private volatile boolean isMatchActive = false;
    private boolean hasConsumedActiveSession = false;
    private long matchSessionId = 0L;

    // Monotonic elapsed clock source (defaults to Android SystemClock.elapsedRealtime())
    private volatile TimeSource timeSource = SYSTEM_ELAPSED_TIME_SOURCE;

    private PlayerCheatingManager() {
        initStates();
    }

    private void initStates() {
        playerStates.clear();
        remainingGamesByColor.clear();
        for (String color : ALL_COLORS) {
            playerStates.put(color, new PlayerState(color));
            remainingGamesByColor.put(color, 0);
        }
    }

    private synchronized void attachSharedPreferencesIfNeeded(@NonNull Context context) {
        if (sharedPrefsBound) {
            return;
        }
        persistenceStore = new SharedPreferencesPersistenceStore(context);
        sharedPrefsBound = true;
        loadFromPersistenceInternal();
    }

    public synchronized void setPersistenceStore(@Nullable PersistenceStore customStore) {
        this.persistenceStore = customStore != null ? customStore : new InMemoryPersistenceStore();
        this.sharedPrefsBound = false;
        loadFromPersistenceInternal();
    }

    public synchronized void reloadFromPersistenceForTest() {
        isMatchActive = false;
        hasConsumedActiveSession = false;
        activeSessionColors.clear();
        for (PlayerState s : playerStates.values()) {
            s.resetFull();
        }
        loadFromPersistenceInternal();
    }

    public synchronized void resetForTest() {
        isMatchActive = false;
        hasConsumedActiveSession = false;
        matchSessionId = 0L;
        isFeatureUnlocked = false;
        durationGames = DEFAULT_DURATION_GAMES;
        selectedColors.clear();
        activeSessionColors.clear();
        for (String color : ALL_COLORS) {
            remainingGamesByColor.put(color, 0);
            PlayerState state = playerStates.get(color);
            if (state != null) state.resetFull();
        }
        persistUnlocked(false);
        persistDurationGames(DEFAULT_DURATION_GAMES);
        for (String color : ALL_COLORS) {
            persistPlayerState(color, false, 0);
        }
    }

    private void loadFromPersistenceInternal() {
        isFeatureUnlocked = persistenceStore.getBoolean(KEY_UNLOCKED, false);
        durationGames = clampDuration(persistenceStore.getInt(KEY_DURATION_GAMES, DEFAULT_DURATION_GAMES));
        selectedColors.clear();
        for (String color : ALL_COLORS) {
            boolean savedSelected = persistenceStore.getBoolean(KEY_SELECTED_PREFIX + color, false);
            int savedRemaining = Math.max(0, persistenceStore.getInt(KEY_REMAINING_PREFIX + color, 0));
            if (savedSelected && savedRemaining > 0) {
                selectedColors.add(color);
                remainingGamesByColor.put(color, savedRemaining);
            } else {
                remainingGamesByColor.put(color, 0);
                if (savedSelected || savedRemaining != 0) {
                    persistPlayerState(color, false, 0);
                }
            }
        }
    }

    private void persistUnlocked(boolean unlocked) {
        persistenceStore.putBoolean(KEY_UNLOCKED, unlocked);
    }

    private void persistDurationGames(int games) {
        persistenceStore.putInt(KEY_DURATION_GAMES, games);
    }

    private void persistPlayerState(@NonNull String color, boolean selected, int remainingGames) {
        persistenceStore.putBoolean(KEY_SELECTED_PREFIX + color, selected);
        persistenceStore.putInt(KEY_REMAINING_PREFIX + color, Math.max(0, remainingGames));
    }

    private static int clampDuration(int games) {
        if (games < MIN_DURATION_GAMES) return MIN_DURATION_GAMES;
        if (games > MAX_DURATION_GAMES) return MAX_DURATION_GAMES;
        return games;
    }

    private static boolean isSupportedColor(@NonNull String normalizedColor) {
        for (String c : ALL_COLORS) {
            if (c.equals(normalizedColor)) return true;
        }
        return false;
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

    // --- Password Access Gate API ---

    public synchronized boolean isFeatureUnlocked() {
        return isFeatureUnlocked;
    }

    /**
     * Verifies the first-time access password without exposing or logging the secret or attempt.
     */
    public synchronized boolean verifyAndUnlock(@Nullable String enteredPassword) {
        if (FEATURE_ACCESS_CODE.equals(enteredPassword)) {
            isFeatureUnlocked = true;
            persistUnlocked(true);
            return true;
        }
        return false;
    }

    // --- Duration & Settings Configuration API ---

    public synchronized int getDurationGames() {
        return durationGames;
    }

    /**
     * Updates the 1-10 game duration slider value and applies it to currently enabled players.
     * Blocked while a match is active.
     */
    public synchronized boolean setDurationGames(int games) {
        if (isMatchActive) {
            return false;
        }
        int clamped = clampDuration(games);
        durationGames = clamped;
        persistDurationGames(clamped);
        for (String color : ALL_COLORS) {
            if (selectedColors.contains(color)) {
                remainingGamesByColor.put(color, clamped);
                persistPlayerState(color, true, clamped);
            }
        }
        return true;
    }

    public synchronized int getRemainingGames(@Nullable String color) {
        String c = normalizeColor(color);
        if (!selectedColors.contains(c)) {
            return 0;
        }
        Integer rem = remainingGamesByColor.get(c);
        return rem != null ? Math.max(0, rem) : 0;
    }

    public synchronized boolean isPlayerSelected(@Nullable String color) {
        return selectedColors.contains(normalizeColor(color));
    }

    /**
     * Enables or disables cheating selection for a color before a match starts.
     * When enabled, initializes that player's remaining match count from the current slider duration.
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
        if (!isSupportedColor(c)) return false;
        if (selected) {
            selectedColors.add(c);
            remainingGamesByColor.put(c, durationGames);
            persistPlayerState(c, true, durationGames);
        } else {
            selectedColors.remove(c);
            remainingGamesByColor.put(c, 0);
            persistPlayerState(c, false, 0);
        }
        logD(TAG, "Player " + c + " selected=" + selected + ", activeSelections=" + selectedColors);
        return true;
    }

    public synchronized boolean isAnyPlayerSelected() {
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

    public synchronized boolean isSessionActive(long sessionId) {
        return isMatchActive && sessionId == this.matchSessionId && !hasConsumedActiveSession;
    }

    public synchronized void onMatchStarted() {
        matchSessionId++;
        isMatchActive = true;
        hasConsumedActiveSession = false;
        activeSessionColors.clear();
        for (String color : ALL_COLORS) {
            if (selectedColors.contains(color)) {
                int rem = getRemainingGames(color);
                if (rem <= 0) {
                    rem = durationGames;
                    remainingGamesByColor.put(color, rem);
                    persistPlayerState(color, true, rem);
                }
                activeSessionColors.add(color);
            }
        }
        for (PlayerState s : playerStates.values()) {
            s.resetFull();
        }
        logI(TAG, "Match started (session=" + matchSessionId + "). Selected players: " + selectedColors);
    }

    /**
     * Ends the match only if the provided session ID matches the currently active match session.
     * Ensures one completed/exited match session decrements each active player's remaining count once.
     */
    public synchronized boolean onMatchEndedIfSession(long sessionId) {
        if (sessionId == this.matchSessionId && isMatchActive && !hasConsumedActiveSession) {
            hasConsumedActiveSession = true;
            isMatchActive = false;
            consumeActiveSessionAndResetTransientStates();
            return true;
        }
        return false;
    }

    /**
     * Ends an active match session (decrementing once), or resets all selections when called
     * outside an active match (e.g., legacy test setup/teardown cleanup).
     */
    public synchronized void onMatchEnded() {
        if (isMatchActive && !hasConsumedActiveSession) {
            onMatchEndedIfSession(this.matchSessionId);
            return;
        }
        isMatchActive = false;
        hasConsumedActiveSession = false;
        activeSessionColors.clear();
        selectedColors.clear();
        durationGames = DEFAULT_DURATION_GAMES;
        persistDurationGames(DEFAULT_DURATION_GAMES);
        for (String color : ALL_COLORS) {
            remainingGamesByColor.put(color, 0);
            persistPlayerState(color, false, 0);
        }
        for (PlayerState s : playerStates.values()) {
            s.resetFull();
        }
        logI(TAG, "Match ended. Cleared all cheating states and reset checkboxes to OFF.");
    }

    private void consumeActiveSessionAndResetTransientStates() {
        for (String color : activeSessionColors) {
            int current = remainingGamesByColor.containsKey(color)
                    ? Math.max(0, remainingGamesByColor.get(color))
                    : 0;
            int updated = Math.max(0, current - 1);
            if (updated == 0) {
                selectedColors.remove(color);
                remainingGamesByColor.put(color, 0);
                persistPlayerState(color, false, 0);
            } else {
                remainingGamesByColor.put(color, updated);
                persistPlayerState(color, true, updated);
            }
        }
        activeSessionColors.clear();
        for (PlayerState s : playerStates.values()) {
            s.resetFull();
        }
        logI(TAG, "Match session finished. Remaining selections: " + selectedColors);
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
    public synchronized boolean isAbilityActiveForPlayer(@Nullable String activePlayerColor) {
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
        // Intentionally silent in release/test; never logs secrets
    }
    private static void logI(String tag, String msg) {
        // Intentionally silent in release/test; never logs secrets
    }
    private static void logW(String tag, String msg) {
        // Intentionally silent in release/test; never logs secrets
    }
    private long getCurrentTimeMs() {
        return timeSource.elapsedRealtime();
    }

}
