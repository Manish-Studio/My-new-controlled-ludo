package com.vinaykpro.ludoking.network;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe pending number store for Controller-selected dice numbers.
 *
 * Rules:
 * - Supports multiple independent pending commands across channels (1..4).
 * - Per-channel FIFO queue: multiple commands sent to the same channel are consumed in arrival order.
 * - Thread-safe insertion from TCP reader threads and thread-safe consumption from the UI game loop.
 * - Strict correlation data preserved: requestId, boxId, channel, value, clientId, and timestamp.
 * - Strict duplicate rejection: duplicate requestIds (active or recently completed) are rejected.
 * - Observable listener for UI updates.
 */
public class PendingNumberStore {
    private static final String TAG = "PendingNumberStore";
    private static final int MAX_COMPLETED_HISTORY = 500;

    public static class PendingCommand {
        @NonNull
        private final String requestId;
        @NonNull
        private final String boxId;
        private final int channel;
        private final int value;
        @Nullable
        private final String clientId;
        private final long timestamp;

        public PendingCommand(
                @NonNull String requestId,
                @NonNull String boxId,
                int channel,
                int value,
                @Nullable String clientId,
                long timestamp
        ) {
            this.requestId = requestId;
            this.boxId = boxId;
            this.channel = channel;
            this.value = Math.max(1, Math.min(6, value));
            this.clientId = clientId;
            this.timestamp = timestamp;
        }

        @NonNull
        public String getRequestId() {
            return requestId;
        }

        @NonNull
        public String getBoxId() {
            return boxId;
        }

        public int getChannel() {
            return channel;
        }

        public int getValue() {
            return value;
        }

        @Nullable
        public String getClientId() {
            return clientId;
        }

        public long getTimestamp() {
            return timestamp;
        }

        @NonNull
        @Override
        public String toString() {
            return "PendingCommand{" +
                    "reqId='" + requestId + '\'' +
                    ", boxId='" + boxId + '\'' +
                    ", ch=" + channel +
                    ", val=" + value +
                    '}';
        }
    }

    public interface OnPendingChangeListener {
        void onPendingChanged(int totalPending, @NonNull List<PendingCommand> currentCommands);
    }

    private final ConcurrentHashMap<Integer, ConcurrentLinkedQueue<PendingCommand>> queuesByChannel = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<OnPendingChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger totalPendingCount = new AtomicInteger(0);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Tracking active and completed requestIds to guard against duplicate application
    private final Set<String> activeRequestIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> completedRequestIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ConcurrentLinkedQueue<String> completedRequestHistory = new ConcurrentLinkedQueue<>();

    public PendingNumberStore() {
        for (int ch = 1; ch <= 4; ch++) {
            queuesByChannel.put(ch, new ConcurrentLinkedQueue<>());
        }
    }

    /**
     * Enqueues a command for the given channel.
     * Returns true if successfully enqueued, false if duplicate requestId rejected.
     */
    public boolean enqueue(@NonNull PendingCommand command) {
        String reqId = command.getRequestId();
        if (activeRequestIds.contains(reqId) || completedRequestIds.contains(reqId)) {
            Log.w(TAG, "Duplicate command rejected for requestId: " + reqId);
            return false;
        }

        int ch = command.getChannel();
        activeRequestIds.add(reqId);
        ConcurrentLinkedQueue<PendingCommand> queue = queuesByChannel.computeIfAbsent(ch, k -> new ConcurrentLinkedQueue<>());
        queue.offer(command);
        totalPendingCount.incrementAndGet();
        Log.i(TAG, "Enqueued pending command for channel " + ch + ": val=" + command.getValue() + ", req=" + reqId + " (Total pending: " + totalPendingCount.get() + ")");
        notifyListeners();
        return true;
    }

    /**
     * Polls (retrieves and removes) the next pending command for the given channel.
     * Returns null if no command is pending for this channel.
     */
    @Nullable
    public PendingCommand pollForChannel(int channel) {
        ConcurrentLinkedQueue<PendingCommand> queue = queuesByChannel.get(channel);
        if (queue == null) return null;

        PendingCommand cmd = queue.poll();
        if (cmd != null) {
            activeRequestIds.remove(cmd.getRequestId());
            totalPendingCount.decrementAndGet();
            Log.i(TAG, "Polled command for channel " + channel + ": val=" + cmd.getValue() + ", req=" + cmd.getRequestId() + " (Remaining pending: " + totalPendingCount.get() + ")");
            notifyListeners();
        }
        return cmd;
    }

    /**
     * Marks a command's requestId as permanently completed.
     * Prevents future duplicate packets with this requestId from enqueuing.
     */
    public void markCompleted(@NonNull String requestId) {
        activeRequestIds.remove(requestId);
        if (completedRequestIds.add(requestId)) {
            completedRequestHistory.offer(requestId);
            if (completedRequestHistory.size() > MAX_COMPLETED_HISTORY) {
                String oldest = completedRequestHistory.poll();
                if (oldest != null) {
                    completedRequestIds.remove(oldest);
                }
            }
        }
    }

    public boolean isCompleted(@NonNull String requestId) {
        return completedRequestIds.contains(requestId);
    }

    public boolean isActive(@NonNull String requestId) {
        return activeRequestIds.contains(requestId);
    }

    /**
     * Peeks at the next pending command for the given channel without removing it.
     */
    @Nullable
    public PendingCommand peekForChannel(int channel) {
        ConcurrentLinkedQueue<PendingCommand> queue = queuesByChannel.get(channel);
        return queue != null ? queue.peek() : null;
    }

    /**
     * Checks if a channel has pending commands.
     */
    public boolean hasPendingForChannel(int channel) {
        ConcurrentLinkedQueue<PendingCommand> queue = queuesByChannel.get(channel);
        return queue != null && !queue.isEmpty();
    }

    /**
     * Clears pending commands for a specific channel.
     */
    public void clearChannel(int channel) {
        ConcurrentLinkedQueue<PendingCommand> q = queuesByChannel.get(channel);
        if (q != null) {
            PendingCommand cmd;
            while ((cmd = q.poll()) != null) {
                activeRequestIds.remove(cmd.getRequestId());
                totalPendingCount.decrementAndGet();
            }
            notifyListeners();
        }
    }

    /**
     * Clears all pending commands across all channels.
     * Note: Completed history is preserved to continue preventing duplicate replay.
     */
    public void clear() {
        for (ConcurrentLinkedQueue<PendingCommand> q : queuesByChannel.values()) {
            q.clear();
        }
        activeRequestIds.clear();
        totalPendingCount.set(0);
        Log.i(TAG, "All pending commands cleared.");
        notifyListeners();
    }

    /**
     * Removes a pending command by its unique requestId, if present.
     */
    public boolean removeByRequestId(@NonNull String requestId) {
        for (ConcurrentLinkedQueue<PendingCommand> q : queuesByChannel.values()) {
            for (PendingCommand cmd : q) {
                if (requestId.equals(cmd.getRequestId())) {
                    if (q.remove(cmd)) {
                        activeRequestIds.remove(requestId);
                        totalPendingCount.decrementAndGet();
                        notifyListeners();
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Returns the total count of pending commands across all channels.
     */
    public int getTotalPendingCount() {
        return Math.max(0, totalPendingCount.get());
    }

    /**
     * Returns an unmodifiable snapshot of all currently queued commands.
     */
    @NonNull
    public List<PendingCommand> getAllPending() {
        List<PendingCommand> list = new ArrayList<>();
        for (ConcurrentLinkedQueue<PendingCommand> q : queuesByChannel.values()) {
            list.addAll(q);
        }
        return Collections.unmodifiableList(list);
    }

    public void addListener(@NonNull OnPendingChangeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NonNull OnPendingChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        if (listeners.isEmpty()) return;
        final int count = getTotalPendingCount();
        final List<PendingCommand> snapshot = getAllPending();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            for (OnPendingChangeListener l : listeners) {
                try {
                    l.onPendingChanged(count, snapshot);
                } catch (Exception e) {
                    Log.e(TAG, "Error in OnPendingChangeListener: " + e.getMessage(), e);
                }
            }
        } else {
            mainHandler.post(() -> {
                for (OnPendingChangeListener l : listeners) {
                    try {
                        l.onPendingChanged(count, snapshot);
                    } catch (Exception e) {
                        Log.e(TAG, "Error in OnPendingChangeListener: " + e.getMessage(), e);
                    }
                }
            });
        }
    }
}
