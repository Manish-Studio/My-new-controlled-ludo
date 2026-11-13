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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Application-scoped Manager for the Ludo Host server.
 *
 * Responsibilities:
 * - Thread-safe server lifecycle state management (STOPPED, STARTING, LISTENING, STOPPING, ERROR).
 * - Safe concurrency primitives preventing start/stop/restart races.
 * - Application-level singleton preventing Activity recreation leaks.
 * - Integration with LudoTcpServer for client socket tracking, message framing, and thread safety.
 * - Auto-responder for protocol verification (Handshake, Ping/Pong, Get Config, Number Selection ACK).
 * - Observable state and client-count listeners posted to the Main UI thread without holding Activity/View references.
 * - Strict isolation from game engine / dice rules until subsequent phases.
 */
public class LudoHostManager implements LudoTcpServer.ServerListener {

    private static final String TAG = "LudoHostManager";
    public static final int DEFAULT_PORT = LudoProtocol.DEFAULT_PORT;

    public enum ServerState {
        STOPPED,
        STARTING,
        LISTENING,
        STOPPING,
        ERROR
    }

    public interface StateListener {
        void onStateChanged(@NonNull ServerState newState, @Nullable String message);
    }

    public interface ClientListener {
        void onClientCountChanged(int activeCount, @NonNull List<String> clientIds);
    }

    public interface MessageListener {
        void onMessageReceived(@NonNull String clientId, @NonNull LudoProtocolMessage message);
    }

    private static volatile LudoHostManager sInstance;

    private final Context appContext;
    private final Object lifecycleLock = new Object();
    private final AtomicReference<ServerState> currentState = new AtomicReference<>(ServerState.STOPPED);
    private final CopyOnWriteArrayList<StateListener> stateListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ClientListener> clientListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MessageListener> messageListeners = new CopyOnWriteArrayList<>();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LudoTcpServer tcpServer;
    private final LudoNsdHelper nsdHelper;
    private ExecutorService backgroundExecutor;
    private volatile String lastErrorMessage = null;
    private volatile int configuredPort = DEFAULT_PORT;
    private volatile int activeBoxCount = LudoProtocol.DEFAULT_BOX_COUNT;
    private volatile List<String> activeBoxIds = Arrays.asList("B1", "B2", "B3", "B4");

    /**
     * Retrieves the Application-scoped singleton instance.
     * Guaranteed to use the ApplicationContext to avoid Activity/View memory leaks.
     */
    @NonNull
    public static LudoHostManager getInstance(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (LudoHostManager.class) {
                if (sInstance == null) {
                    sInstance = new LudoHostManager(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    /**
     * Constructor for Application instantiation or testing.
     */
    public LudoHostManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.tcpServer = new LudoTcpServer(this);
        this.nsdHelper = new LudoNsdHelper(this.appContext);
        LudoGameBridge.getInstance(this.appContext);
        Log.i(TAG, "LudoHostManager initialized with ApplicationContext: " + this.appContext.getPackageName());
    }

    @NonNull
    public LudoNsdHelper getNsdHelper() {
        return nsdHelper;
    }

    @NonNull
    public LudoGameBridge getGameBridge() {
        return LudoGameBridge.getInstance(appContext);
    }

    /**
     * Provides a resilient background single-thread executor that automatically
     * recreates itself if it was previously shut down (e.g. across test cycles).
     */
    @NonNull
    private synchronized ExecutorService getExecutor() {
        if (backgroundExecutor == null || backgroundExecutor.isShutdown() || backgroundExecutor.isTerminated()) {
            backgroundExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "LudoHostManager-Worker");
                t.setDaemon(true);
                return t;
            });
        }
        return backgroundExecutor;
    }

    // --- State and Metric Inspection ---

    @NonNull
    public ServerState getState() {
        return currentState.get();
    }

    public boolean isListening() {
        return currentState.get() == ServerState.LISTENING;
    }

    public boolean isActive() {
        ServerState state = currentState.get();
        return state == ServerState.STARTING || state == ServerState.LISTENING;
    }

    @Nullable
    public String getLastError() {
        return lastErrorMessage;
    }

    public int getConfiguredPort() {
        return configuredPort;
    }

    public int getBoundPort() {
        return tcpServer.getBoundPort();
    }

    public int getClientCount() {
        return tcpServer.getActiveClientCount();
    }

    @NonNull
    public List<String> getConnectedClientIds() {
        return tcpServer.getConnectedClientIds();
    }

    public int getActiveBoxCount() {
        return activeBoxCount;
    }

    @NonNull
    public List<String> getActiveBoxIds() {
        return activeBoxIds != null ? new ArrayList<>(activeBoxIds) : Arrays.asList("B1", "B2", "B3", "B4");
    }

    /**
     * Updates active boxes and broadcasts CONFIG to connected controllers.
     */
    public void setActiveBoxes(int count, @NonNull List<String> boxIds) {
        this.activeBoxCount = count;
        this.activeBoxIds = new ArrayList<>(boxIds);
        LudoProtocolMessage config = LudoProtocolMessage.createConfig(this.activeBoxCount, this.activeBoxIds);
        broadcast(config);
        Log.i(TAG, "Broadcasting updated CONFIG: count=" + count + ", activeBoxIds=" + this.activeBoxIds);
    }

    /**
     * Updates the active box count within the verified Controller range (2..4).
     * Returns false if out of range.
     */
    public boolean setActiveBoxCount(int count) {
        if (count >= LudoProtocol.MIN_BOX_COUNT && count <= LudoProtocol.MAX_BOX_COUNT) {
            this.activeBoxCount = count;
            List<String> boxes = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                boxes.add("B" + i);
            }
            this.activeBoxIds = boxes;
            broadcast(LudoProtocolMessage.createConfig(count, this.activeBoxIds));
            return true;
        }
        Log.w(TAG, "Invalid activeBoxCount rejected: " + count + " (Allowed: " +
                LudoProtocol.MIN_BOX_COUNT + ".." + LudoProtocol.MAX_BOX_COUNT + ")");
        return false;
    }

    public boolean setConfiguredPort(int port) {
        if (port < 1024 || port > 65535) {
            Log.w(TAG, "Invalid port rejected: " + port);
            return false;
        }
        synchronized (lifecycleLock) {
            if (isActive()) {
                Log.w(TAG, "Cannot change port while server is active (" + currentState.get() + ")");
                return false;
            }
            this.configuredPort = port;
            return true;
        }
    }

    // --- Listener Registration (Main Thread Dispatched) ---

    public void registerStateListener(@NonNull StateListener listener) {
        if (!stateListeners.contains(listener)) {
            stateListeners.add(listener);
            final ServerState state = currentState.get();
            final String msg = lastErrorMessage;
            mainHandler.post(() -> listener.onStateChanged(state, msg));
        }
    }

    public void unregisterStateListener(@NonNull StateListener listener) {
        stateListeners.remove(listener);
    }

    public void registerListener(@NonNull StateListener listener) {
        registerStateListener(listener);
    }

    public void unregisterListener(@NonNull StateListener listener) {
        unregisterStateListener(listener);
    }

    public void registerClientListener(@NonNull ClientListener listener) {
        if (!clientListeners.contains(listener)) {
            clientListeners.add(listener);
            final int count = getClientCount();
            final List<String> ids = getConnectedClientIds();
            mainHandler.post(() -> listener.onClientCountChanged(count, ids));
        }
    }

    public void unregisterClientListener(@NonNull ClientListener listener) {
        clientListeners.remove(listener);
    }

    public void registerMessageListener(@NonNull MessageListener listener) {
        if (!messageListeners.contains(listener)) {
            messageListeners.add(listener);
        }
    }

    public void unregisterMessageListener(@NonNull MessageListener listener) {
        messageListeners.remove(listener);
    }

    // --- Lifecycle Operations ---

    /**
     * Initiates host server startup on a background thread.
     * Guards against duplicate startup requests and invalid transitions.
     */
    public void startHost() {
        synchronized (lifecycleLock) {
            if (com.vinaykpro.ludoking.cheating.PlayerCheatingManager.getInstance().isAnyPlayerSelected()) {
                Log.w(TAG, "startHost blocked: Player Cheating Selection is active.");
                return;
            }
            ServerState state = currentState.get();
            if (state == ServerState.STARTING || state == ServerState.LISTENING) {
                Log.w(TAG, "Duplicate startHost request ignored. Server is already " + state);
                return;
            }

            if (state == ServerState.STOPPING) {
                Log.w(TAG, "Cannot start while server is in STOPPING state. Await completion.");
                return;
            }

            lastErrorMessage = null;
            updateState(ServerState.STARTING, "Starting Ludo Host server on port " + configuredPort + "...");

            final int portToBind = configuredPort;
            getExecutor().execute(() -> {
                try {
                    Log.d(TAG, "Executing TCP server start on port " + portToBind);
                    tcpServer.start(portToBind);
                } catch (Exception e) {
                    Log.e(TAG, "Host startup failed: " + e.getMessage(), e);
                    lastErrorMessage = e.getMessage();
                    updateState(ServerState.ERROR, "Startup failed: " + e.getMessage());
                }
            });
        }
    }

    /**
     * Initiates host server shutdown on a background thread.
     * Guards against redundant shutdown calls and handles repeated calls safely.
     */
    public void stopHost() {
        synchronized (lifecycleLock) {
            ServerState state = currentState.get();
            if (state == ServerState.STOPPED || state == ServerState.STOPPING) {
                Log.d(TAG, "stopHost request ignored. Server is already " + state);
                return;
            }

            updateState(ServerState.STOPPING, "Stopping Ludo Host server...");

            getExecutor().execute(() -> {
                try {
                    Log.d(TAG, "Executing TCP server stop on background executor");
                    tcpServer.stop();
                } catch (Exception e) {
                    Log.e(TAG, "Host shutdown encountered error: " + e.getMessage(), e);
                    lastErrorMessage = e.getMessage();
                    updateState(ServerState.ERROR, "Shutdown error: " + e.getMessage());
                }
            });
        }
    }

    /**
     * Restarts the host server cleanly.
     * Coordinates sequential stop followed by start on the background executor to prevent race conditions.
     */
    public void restartHost() {
        synchronized (lifecycleLock) {
            if (com.vinaykpro.ludoking.cheating.PlayerCheatingManager.getInstance().isAnyPlayerSelected()) {
                Log.w(TAG, "restartHost blocked: Player Cheating Selection is active.");
                ServerState state = currentState.get();
                if (state != ServerState.STOPPED && state != ServerState.STOPPING) {
                    stopHost();
                }
                return;
            }
            ServerState state = currentState.get();
            if (state == ServerState.STOPPED) {
                startHost();
                return;
            }

            updateState(ServerState.STOPPING, "Restarting Ludo Host server...");
            final int portToBind = configuredPort;

            getExecutor().execute(() -> {
                try {
                    Log.d(TAG, "Sequential restart: Stopping TCP server...");
                    tcpServer.stop();

                    if (com.vinaykpro.ludoking.cheating.PlayerCheatingManager.getInstance().isAnyPlayerSelected()) {
                        Log.w(TAG, "Sequential restart aborted: Player Cheating Selection is active.");
                        updateState(ServerState.STOPPED, "Blocked while Player Cheating Selection is active");
                        return;
                    }

                    Log.d(TAG, "Sequential restart: Starting TCP server on port " + portToBind);
                    updateState(ServerState.STARTING, "Restarting server on port " + portToBind + "...");
                    tcpServer.start(portToBind);
                } catch (Exception e) {
                    Log.e(TAG, "Restart failed: " + e.getMessage(), e);
                    lastErrorMessage = e.getMessage();
                    updateState(ServerState.ERROR, "Restart error: " + e.getMessage());
                }
            });
        }
    }

    // --- Message Transmission ---

    public boolean sendToClient(@NonNull String clientId, @NonNull LudoProtocolMessage message) {
        return tcpServer.sendToClient(clientId, message);
    }

    public void broadcast(@NonNull LudoProtocolMessage message) {
        tcpServer.broadcast(message);
    }

    // --- LudoTcpServer.ServerListener Callbacks ---

    @Override
    public void onServerStarted(int port) {
        if (com.vinaykpro.ludoking.cheating.PlayerCheatingManager.getInstance().isAnyPlayerSelected()) {
            Log.w(TAG, "onServerStarted aborted: Player Cheating Selection is active. Stopping host without NSD registration.");
            stopHost();
            return;
        }
        Log.i(TAG, "TCP Server bound on port: " + port);
        updateState(ServerState.LISTENING, "Listening on port " + port);
        nsdHelper.registerService(port);
    }

    @Override
    public void onServerStopped() {
        Log.i(TAG, "TCP Server fully stopped");
        nsdHelper.unregisterService();
        updateState(ServerState.STOPPED, "Server stopped");
        notifyClientCountChanged();
    }

    @Override
    public void onServerError(@NonNull String error, @Nullable Throwable cause) {
        Log.e(TAG, "TCP Server error: " + error, cause);
        lastErrorMessage = error;
        nsdHelper.unregisterService();
        updateState(ServerState.ERROR, error);
    }

    @Override
    public void onClientConnected(@NonNull String clientId, @NonNull String remoteAddress) {
        Log.i(TAG, "Client connected: " + clientId + " from " + remoteAddress);
        notifyClientCountChanged();
    }

    @Override
    public void onClientDisconnected(@NonNull String clientId, @NonNull String reason) {
        Log.i(TAG, "Client disconnected: " + clientId + " (" + reason + ")");
        notifyClientCountChanged();
    }

    @Override
    public void onMessageReceived(@NonNull String clientId, @NonNull LudoProtocolMessage message) {
        Log.d(TAG, "Message received from " + clientId + ": " + message.getType());

        // Protocol Auto-Responses for verification and diagnostics
        switch (message.getType()) {
            case HANDSHAKE:
                String hsReqId = message.getRequestId() != null ? message.getRequestId() : "hs_ack";
                LudoProtocolMessage hsAck = LudoProtocolMessage.createHandshakeAck(hsReqId, "OK");
                sendToClient(clientId, hsAck);
                break;

            case PING:
                LudoProtocolMessage pong = LudoProtocolMessage.createPong(message.getRequestId());
                sendToClient(clientId, pong);
                break;

            case GET_CONFIG:
                LudoProtocolMessage config = LudoProtocolMessage.createConfig(activeBoxCount, activeBoxIds);
                sendToClient(clientId, config);
                break;

            case NUMBER_SELECTION:
                // Handled with full validation, specific status ACK or rejection ERROR in LudoGameBridge
                LudoGameBridge.getInstance(appContext).handleNumberSelection(clientId, message);
                break;

            case DISCONNECT:
                Log.i(TAG, "Client [" + clientId + "] sent DISCONNECT (" + message.getReason() + "). Closing connection.");
                tcpServer.disconnectClient(clientId, "Client requested disconnect");
                break;

            default:
                break;
        }

        // Notify registered external listeners
        mainHandler.post(() -> {
            for (MessageListener l : messageListeners) {
                try {
                    l.onMessageReceived(clientId, message);
                } catch (Exception e) {
                    Log.e(TAG, "Error notifying MessageListener: " + e.getMessage(), e);
                }
            }
        });
    }

    // --- Internal State & Listener Helpers ---

    private void updateState(@NonNull ServerState newState, @Nullable String message) {
        currentState.set(newState);
        Log.i(TAG, "ServerState -> " + newState + (message != null ? " (" + message + ")" : ""));
        mainHandler.post(() -> {
            for (StateListener listener : stateListeners) {
                try {
                    listener.onStateChanged(newState, message);
                } catch (Exception e) {
                    Log.e(TAG, "Error notifying state listener: " + e.getMessage(), e);
                }
            }
        });
    }

    private void notifyClientCountChanged() {
        final int count = getClientCount();
        final List<String> ids = getConnectedClientIds();
        mainHandler.post(() -> {
            for (ClientListener listener : clientListeners) {
                try {
                    listener.onClientCountChanged(count, ids);
                } catch (Exception e) {
                    Log.e(TAG, "Error notifying client listener: " + e.getMessage(), e);
                }
            }
        });
    }

    /**
     * Teardown hook for application termination or testing.
     * Explicit Semantics:
     * - Only to be called on whole application termination or automated test cleanup.
     * - Normal Activity navigation (e.g. HomeActivity <-> MainActivity onDestroy) must NEVER call destroy().
     */
    public void destroy() {
        Log.i(TAG, "Destroying LudoHostManager instance...");
        stateListeners.clear();
        clientListeners.clear();
        messageListeners.clear();
        nsdHelper.unregisterService();
        LudoGameBridge.getInstance(appContext).getPendingNumberStore().clear();
        synchronized (lifecycleLock) {
            tcpServer.stop();
            if (backgroundExecutor != null) {
                backgroundExecutor.shutdownNow();
                backgroundExecutor = null;
            }
            currentState.set(ServerState.STOPPED);
        }
    }
}
