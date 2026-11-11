package com.vinaykpro.ludoking.network;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
    private final CopyOnWriteArrayList<String> activeBoxIds = new CopyOnWriteArrayList<>(Arrays.asList("B1", "B2", "B3", "B4"));
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

    @NonNull
    public List<String> getActiveBoxIds() {
        return new ArrayList<>(activeBoxIds);
    }

    public boolean isBoxActive(@NonNull String boxId) {
        String clean = boxId.trim().toUpperCase();
        return activeBoxIds.contains(clean);
    }

    // --- Controller Message Ingestion ---

    /**
     * Ingests a validated NUMBER_SELECTION message from the Controller.
     * Enforces active-box validation, duplicate-rejection, and immediate ACK/ERROR responses.
     */
    public boolean handleNumberSelection(@Nullable String clientId, @NonNull LudoProtocolMessage message) {
        if (com.vinaykpro.ludoking.cheating.PlayerCheatingManager.getInstance().isAnyPlayerSelected()) {
            Log.w(TAG, "Rejected NUMBER_SELECTION: Player Cheating Selection is active on host.");
            String reqId = message.getRequestId();
            if (reqId == null || reqId.trim().isEmpty()) reqId = "req_blocked";
            LudoProtocolMessage errMsg = LudoProtocolMessage.createError(
                    reqId,
                    "CONTROLLER_BLOCKED",
                    "Controller number selection is disabled while Player Cheating is active",
                    message.getBoxId(),
                    message.getChannel() != null ? message.getChannel() : 1
            );
            sendDirectOrBroadcast(clientId, errMsg);
            return false;
        }
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
        boxId = boxId.trim().toUpperCase();

        Integer rawVal = message.getValue();
        int value = rawVal != null ? Math.max(1, Math.min(6, rawVal)) : 6;

        // Validation 1: Inactive box check (reject if game is running and box is not active)
        if (isGameRunning && !isBoxActive(boxId)) {
            Log.w(TAG, "Rejected NUMBER_SELECTION for inactive box " + boxId + " (active: " + activeBoxIds + ")");
            LudoProtocolMessage errMsg = LudoProtocolMessage.createError(
                    reqId,
                    "INACTIVE_BOX",
                    "Box " + boxId + " is not active in current game",
                    boxId,
                    channel
            );
            sendDirectOrBroadcast(clientId, errMsg);
            return false;
        }

        PendingNumberStore.PendingCommand command = new PendingNumberStore.PendingCommand(
                reqId,
                boxId,
                channel,
                value,
                clientId,
                System.currentTimeMillis()
        );

        // Validation 2: Duplicate requestId check
        boolean enqueued = pendingNumberStore.enqueue(command);
        if (!enqueued) {
            Log.w(TAG, "Rejected duplicate command for reqId=" + reqId);
            LudoProtocolMessage errMsg = LudoProtocolMessage.createError(
                    reqId,
                    "DUPLICATE_REQUEST",
                    "Duplicate requestId already active or completed: " + reqId,
                    boxId,
                    channel
            );
            sendDirectOrBroadcast(clientId, errMsg);
            return false;
        }

        // Send confirmed ACK indicating successful receipt and queuing
        LudoProtocolMessage ack = LudoProtocolMessage.createAck(reqId, "RECEIVED", boxId, channel, value);
        sendDirectOrBroadcast(clientId, ack);
        Log.i(TAG, "Ingested and ACKed Controller selection: " + command);
        return true;
    }

    // --- Two-Phase Dice Roll Interception & NUMBER_RESULT Delivery ---

    /**
     * Step 1: Prepares the dice roll for the rolling channel.
     * Polls the next pending command if available, or retains fallbackRandom.
     * Transmits an APPLYING acknowledgement to notify Controller that dice roll has commenced.
     * Does NOT transmit NUMBER_RESULT until confirmApplied() is invoked.
     */
    @NonNull
    public AppliedRoll prepareDiceRoll(int channel, int fallbackRandom) {
        PendingNumberStore.PendingCommand cmd = pendingNumberStore.pollForChannel(channel);
        if (cmd != null) {
            Log.i(TAG, "Prepared intercepted dice roll for channel " + channel + " (" + cmd.getBoxId() + "): value " + cmd.getValue() + " (reqId: " + cmd.getRequestId() + ")");
            // Notify Controller that the game has begun applying this selection to the dice roll
            try {
                LudoProtocolMessage applyingAck = LudoProtocolMessage.createAck(
                        cmd.getRequestId(),
                        "APPLYING",
                        cmd.getBoxId(),
                        channel,
                        cmd.getValue()
                );
                sendDirectOrBroadcast(cmd.getClientId(), applyingAck);
            } catch (Exception e) {
                Log.w(TAG, "Failed sending APPLYING notification: " + e.getMessage());
            }
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
        LudoProtocolMessage resultMsg = LudoProtocolMessage.createNumberResult(
                cmd.getRequestId(),
                cmd.getBoxId(),
                cmd.getChannel(),
                value
        );
        sendDirectOrBroadcast(cmd.getClientId(), resultMsg);
        Log.i(TAG, "Delivered NUMBER_RESULT: " + resultMsg.toJson());
    }

    private void sendDirectOrBroadcast(@Nullable String clientId, @NonNull LudoProtocolMessage message) {
        try {
            LudoHostManager hostManager = LudoHostManager.getInstance(appContext);
            boolean sentToClient = false;
            if (clientId != null) {
                sentToClient = hostManager.sendToClient(clientId, message);
            }
            if (!sentToClient) {
                hostManager.broadcast(message);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed sending protocol message: " + e.getMessage(), e);
        }
    }

    // --- Game Lifecycle Tracking ---

    public void onGameStarted(int numberOfPlayers, @NonNull List<String> activeBoxes) {
        this.currentActivePlayers = Math.max(2, Math.min(4, numberOfPlayers));
        this.isGameRunning = true;
        this.activeBoxIds.clear();
        for (String b : activeBoxes) {
            String clean = b.trim().toUpperCase();
            if (!this.activeBoxIds.contains(clean)) {
                this.activeBoxIds.add(clean);
            }
        }
        // Purge pending queue of commands for inactive channels
        for (int ch = 1; ch <= 4; ch++) {
            String bId = getBoxIdForChannel(ch);
            if (!this.activeBoxIds.contains(bId)) {
                pendingNumberStore.clearChannel(ch);
            }
        }
        Log.i(TAG, "Ludo Game Started with " + currentActivePlayers + " players. Active boxes: " + activeBoxIds);
        try {
            LudoHostManager.getInstance(appContext).setActiveBoxes(currentActivePlayers, this.activeBoxIds);
        } catch (Exception ignored) {}
    }

    public void onGameStarted(int numberOfPlayers) {
        List<String> defaultBoxes;
        if (numberOfPlayers == 2) {
            defaultBoxes = Arrays.asList("B1", "B3");
        } else if (numberOfPlayers == 3) {
            defaultBoxes = Arrays.asList("B1", "B2", "B3");
        } else {
            defaultBoxes = Arrays.asList("B1", "B2", "B3", "B4");
        }
        onGameStarted(numberOfPlayers, defaultBoxes);
    }

    public void onGameEnded() {
        this.isGameRunning = false;
        this.activeBoxIds.clear();
        this.activeBoxIds.addAll(Arrays.asList("B1", "B2", "B3", "B4"));
        this.pendingNumberStore.clear();
        try {
            LudoHostManager.getInstance(appContext).setActiveBoxes(4, this.activeBoxIds);
        } catch (Exception ignored) {}
        Log.i(TAG, "Ludo Game Ended. Reset active boxes to default.");
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
