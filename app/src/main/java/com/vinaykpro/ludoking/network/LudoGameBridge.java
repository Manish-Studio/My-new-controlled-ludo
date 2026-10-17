package com.vinaykpro.ludoking.network;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Game Engine Bridge coordinating Ludo King's dice engine with the Controller network protocol.
 *
 * Responsibilities:
 * - Maps Ludo players (color and board position) to Controller channels (1..4) and box IDs (B1..B4).
 * - Enqueues Controller NUMBER_SELECTION commands into PendingNumberStore.
 * - Decouples dice roll preparation from result confirmation:
 *     1. prepareDiceRoll(): Polls pending selection and returns an AppliedRoll context.
 *     2. confirmApplied(): Transmits NUMBER_RESULT ONLY AFTER the dice value has actually been applied
 *        to the game state and dice visuals.
 * - Formats and transmits strict NUMBER_RESULT messages matching the Controller's correlation rules:
 *     * Authoritative requestId
 *     * Matching boxId and channel
 *     * Rolled dice value (1..6)
 *     * Status "OK"
 * - Automatically falls back to natural dice rolls (1..6) when no selection is pending for a channel.
 * - Observable listener for dice roll events and game diagnostics.
 */
public class LudoGameBridge {
    private static final String TAG = "LudoGameBridge";

    public interface DiceRollObserver {
        void onDiceRolled(int channel, @NonNull String boxId, int value, boolean wasFromController, @Nullable String requestId);
    }

    /**
     * Context representing a prepared dice roll.
     * Guarantees NUMBER_RESULT is delivered exactly once and only after the dice
     * visual and game turn state have actually been committed.
     */
    public static class AppliedRoll {
        private final int value;
        private final int channel;
        @Nullable
        private final PendingNumberStore.PendingCommand command;
        private final AtomicBoolean confirmed = new AtomicBoolean(false);

        public AppliedRoll(int value, int channel, @Nullable PendingNumberStore.PendingCommand command) {
            this.value = value;
            this.channel = channel;
            this.command = command;
        }

        public int getValue() {
            return value;
        }

        public int getChannel() {
            return channel;
        }

        public boolean isFromController() {
            return command != null;
        }

        @Nullable
        public PendingNumberStore.PendingCommand getCommand() {
            return command;
        }

        /**
         * Confirms that the dice value has actually been applied to the game and dice UI.
         * Dispatches NUMBER_RESULT to Controller and marks command completed.
         */
        public void confirmApplied() {
            if (confirmed.compareAndSet(false, true)) {
                LudoGameBridge bridge = LudoGameBridge.getInstance();
                if (command != null) {
                    bridge.getPendingNumberStore().markCompleted(command.getRequestId());
                    bridge.sendNumberResult(command, value);
                    bridge.notifyObservers(channel, command.getBoxId(), value, true, command.getRequestId());
                } else {
                    String boxId = LudoGameBridge.getBoxIdForChannel(channel);
                    bridge.notifyObservers(channel, boxId, value, false, null);
                }
            }
        }
    }

    private static volatile LudoGameBridge sInstance;

    private final Context appContext;
    private final PendingNumberStore pendingNumberStore;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<DiceRollObserver> observers = new CopyOnWriteArrayList<>();

    private volatile int currentActivePlayers = LudoProtocol.DEFAULT_BOX_COUNT;
    private volatile boolean isGameRunning = false;

    public static LudoGameBridge getInstance(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (LudoGameBridge.class) {
                if (sInstance == null) {
                    sInstance = new LudoGameBridge(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    @NonNull
    public static LudoGameBridge getInstance() {
        if (sInstance == null) {
            throw new IllegalStateException("LudoGameBridge not initialized with Context");
        }
        return sInstance;
    }

    private LudoGameBridge(@NonNull Context context) {
        this.appContext = context;
        this.pendingNumberStore = new PendingNumberStore();
    }

    @NonNull
    public PendingNumberStore getPendingNumberStore() {
        return pendingNumberStore;
    }

    // --- Channel and Box Mapping ---

    /**
     * Maps player board position (1..4) and color ("red", "green", "yellow", "blue")
     * to the corresponding Controller channel (1..4).
     *
     * Classic Board Mapping:
     * - RED    -> Bottom-Left  (Position 3) -> Channel 1 (B1)
     * - GREEN  -> Top-Left     (Position 1) -> Channel 2 (B2)
     * - YELLOW -> Top-Right    (Position 2) -> Channel 3 (B3)
     * - BLUE   -> Bottom-Right (Position 4) -> Channel 4 (B4)
     */
    public int getChannelForPlayer(int position, @Nullable String color) {
        if (color != null) {
            String c = color.trim().toLowerCase();
            switch (c) {
                case "red":
                    return 1;
                case "green":
                    return 2;
                case "yellow":
                    return 3;
                case "blue":
                    return 4;
            }
        }

        switch (position) {
            case 1: // Top-Left
                return 2; // Green
            case 2: // Top-Right
                return 3; // Yellow
            case 3: // Bottom-Left
                return 1; // Red
            case 4: // Bottom-Right
                return 4; // Blue
            default:
                return 1;
        }
    }

    @NonNull
    public static String getBoxIdForChannel(int channel) {
        return "B" + Math.max(1, Math.min(4, channel));
    }

    public static int getChannelForBoxId(@Nullable String boxId) {
        if (boxId == null) return 1;
        Integer ch = LudoProtocolMessage.parseChannelFromBoxId(boxId);
        return ch != null ? Math.max(1, Math.min(4, ch)) : 1;
    }

    // --- Controller Message Ingestion ---

    /**
     * Ingests a validated NUMBER_SELECTION message from the Controller.
     */
    public void handleNumberSelection(@Nullable String clientId, @NonNull LudoProtocolMessage message) {
        String reqId = message.getRequestId();
        if (reqId == null || reqId.trim().isEmpty()) {
            reqId = "req_" + System.currentTimeMillis();
        }

        Integer ch = message.getChannel();
        if (ch == null && message.getBoxId() != null) {
            ch = getChannelForBoxId(message.getBoxId());
        }
        int channel = ch != null ? Math.max(1, Math.min(4, ch)) : 1;

        String boxId = message.getBoxId();
        if (boxId == null || boxId.trim().isEmpty()) {
            boxId = getBoxIdForChannel(channel);
        }

        Integer rawVal = message.getValue();
        int value = rawVal != null ? Math.max(1, Math.min(6, rawVal)) : 6;

        PendingNumberStore.PendingCommand command = new PendingNumberStore.PendingCommand(
                reqId,
                boxId,
                channel,
                value,
                clientId,
                System.currentTimeMillis()
        );

        Log.i(TAG, "Ingested Controller number selection: " + command);
        pendingNumberStore.enqueue(command);
    }

    // --- Two-Phase Dice Roll Interception & NUMBER_RESULT Delivery ---

    /**
     * Step 1: Prepares the dice roll for the rolling channel.
     * Polls the next pending command if available, or retains fallbackRandom.
     * Does NOT transmit NUMBER_RESULT until confirmApplied() is invoked.
     */
    @NonNull
    public AppliedRoll prepareDiceRoll(int channel, int fallbackRandom) {
        PendingNumberStore.PendingCommand cmd = pendingNumberStore.pollForChannel(channel);
        if (cmd != null) {
            Log.i(TAG, "Prepared intercepted dice roll for channel " + channel + " (" + cmd.getBoxId() + "): value " + cmd.getValue() + " (reqId: " + cmd.getRequestId() + ")");
            return new AppliedRoll(cmd.getValue(), channel, cmd);
        } else {
            return new AppliedRoll(fallbackRandom, channel, null);
        }
    }

    /**
     * Convenience method combining prepareDiceRoll and confirmApplied.
     * Useful for synchronous execution and unit tests.
     */
    public int consumeDiceRoll(int channel, int fallbackRandom) {
        AppliedRoll roll = prepareDiceRoll(channel, fallbackRandom);
        roll.confirmApplied();
        return roll.getValue();
    }

    private void sendNumberResult(@NonNull PendingNumberStore.PendingCommand cmd, int value) {
        try {
            LudoHostManager hostManager = LudoHostManager.getInstance(appContext);
            LudoProtocolMessage resultMsg = LudoProtocolMessage.createNumberResult(
                    cmd.getRequestId(),
                    cmd.getBoxId(),
                    cmd.getChannel(),
                    value
            );

            // Send to originating client if still connected, and broadcast for controllers/monitors
            boolean sentToClient = false;
            if (cmd.getClientId() != null) {
                sentToClient = hostManager.sendToClient(cmd.getClientId(), resultMsg);
            }
            if (!sentToClient) {
                hostManager.broadcast(resultMsg);
            }
            Log.i(TAG, "Delivered NUMBER_RESULT: " + resultMsg.toJson());
        } catch (Exception e) {
            Log.e(TAG, "Failed delivering NUMBER_RESULT: " + e.getMessage(), e);
        }
    }

    // --- Game Lifecycle Tracking ---

    public void onGameStarted(int numberOfPlayers) {
        this.currentActivePlayers = Math.max(2, Math.min(4, numberOfPlayers));
        this.isGameRunning = true;
        Log.i(TAG, "Ludo Game Started with " + currentActivePlayers + " players.");
        // Always advertise 4 boxes to the Controller so that non-adjacent yards
        // (such as B1 Red and B3 Yellow in 2-player mode) remain fully addressable on the Controller UI.
        try {
            LudoHostManager.getInstance(appContext).setActiveBoxCount(LudoProtocol.DEFAULT_BOX_COUNT);
        } catch (Exception ignored) {}
    }

    public void onGameEnded() {
        this.isGameRunning = false;
        Log.i(TAG, "Ludo Game Ended.");
    }

    public boolean isGameRunning() {
        return isGameRunning;
    }

    public int getCurrentActivePlayers() {
        return currentActivePlayers;
    }

    // --- Observers ---

    public void addObserver(@NonNull DiceRollObserver observer) {
        observers.add(observer);
    }

    public void removeObserver(@NonNull DiceRollObserver observer) {
        observers.remove(observer);
    }

    private void notifyObservers(int channel, @NonNull String boxId, int val, boolean fromController, @Nullable String reqId) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            for (DiceRollObserver obs : observers) {
                try {
                    obs.onDiceRolled(channel, boxId, val, fromController, reqId);
                } catch (Exception ignored) {}
            }
        } else {
            mainHandler.post(() -> {
                for (DiceRollObserver obs : observers) {
                    try {
                        obs.onDiceRolled(channel, boxId, val, fromController, reqId);
                    } catch (Exception ignored) {}
                }
            });
        }
    }
}
