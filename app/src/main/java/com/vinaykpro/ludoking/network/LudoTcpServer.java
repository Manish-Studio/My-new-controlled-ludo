package com.vinaykpro.ludoking.network;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Robust TCP Server for Ludo Host.
 *
 * Handles:
 * - Asynchronous ServerSocket binding and accept loop on background threads.
 * - Thread-safe tracking and cleanup of multiple connected Controller clients.
 * - Per-client synchronized writes with newline framing.
 * - Safe disconnect detection, resource reclamation, and error callbacks.
 * - Guaranteed single stop notification per lifecycle cycle.
 */
public class LudoTcpServer {

    private static final String TAG = "LudoTcpServer";
    public static final int MAX_CLIENTS = 8;
    private static final int SO_TIMEOUT_MS = 0; // 0 = indefinite for socket reads

    public interface ServerListener {
        void onServerStarted(int port);
        void onServerStopped();
        void onServerError(@NonNull String error, @Nullable Throwable cause);
        void onClientConnected(@NonNull String clientId, @NonNull String remoteAddress);
        void onClientDisconnected(@NonNull String clientId, @NonNull String reason);
        void onMessageReceived(@NonNull String clientId, @NonNull LudoProtocolMessage message);
    }

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isStoppedNotified = new AtomicBoolean(true);
    private final AtomicInteger clientSequence = new AtomicInteger(1);
    private final Map<String, ClientConnection> activeClients = new ConcurrentHashMap<>();

    private ServerSocket serverSocket;
    private ExecutorService serverExecutor;
    private ServerListener listener;
    private int boundPort = 0;

    public LudoTcpServer(@Nullable ServerListener listener) {
        this.listener = listener;
    }

    public synchronized void setListener(@Nullable ServerListener listener) {
        this.listener = listener;
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    public int getBoundPort() {
        return boundPort;
    }

    public int getActiveClientCount() {
        return activeClients.size();
    }

    @NonNull
    public List<String> getConnectedClientIds() {
        return new ArrayList<>(activeClients.keySet());
    }

    /**
     * Starts the TCP Server asynchronously on the specified port.
     * Port <= 0 uses an ephemeral kernel-assigned port (safe for testing).
     */
    public synchronized boolean start(int port) {
        if (isRunning.get()) {
            Log.w(TAG, "Server is already running on port " + boundPort);
            return false;
        }

        final int targetPort = port > 0 ? port : 0;
        isRunning.set(true);
        isStoppedNotified.set(false);

        serverExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "LudoTcpServer-Pool");
            t.setDaemon(true);
            return t;
        });

        serverExecutor.execute(() -> {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(targetPort));
                serverSocket = ss;
                boundPort = ss.getLocalPort();
                Log.i(TAG, "Ludo TCP Server successfully listening on port " + boundPort);

                notifyServerStarted(boundPort);

                while (isRunning.get() && !ss.isClosed()) {
                    try {
                        Socket clientSocket = ss.accept();
                        clientSocket.setTcpNoDelay(true);
                        clientSocket.setKeepAlive(true);
                        clientSocket.setSoTimeout(SO_TIMEOUT_MS);

                        if (activeClients.size() >= MAX_CLIENTS) {
                            Log.w(TAG, "Max clients limit reached (" + MAX_CLIENTS + "). Rejecting connection.");
                            try {
                                BufferedWriter rejectWriter = new BufferedWriter(
                                        new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8)
                                );
                                LudoProtocolMessage rejectMsg = LudoProtocolMessage.createError(
                                        null, "MAX_CLIENTS_REACHED", "Server connection limit reached"
                                );
                                rejectWriter.write(rejectMsg.toTransmissionString());
                                rejectWriter.flush();
                            } catch (Exception ignored) {}
                            clientSocket.close();
                            continue;
                        }

                        String clientId = "client_" + clientSequence.getAndIncrement();
                        ClientConnection connection = new ClientConnection(clientId, clientSocket);
                        activeClients.put(clientId, connection);

                        notifyClientConnected(clientId, clientSocket.getRemoteSocketAddress().toString());

                        // Dispatch reader loop to thread pool
                        ExecutorService exec = serverExecutor;
                        if (exec != null && !exec.isShutdown()) {
                            exec.execute(connection::runReaderLoop);
                        }
                    } catch (SocketException e) {
                        if (!isRunning.get() || ss.isClosed()) {
                            Log.d(TAG, "ServerSocket closed normally for shutdown");
                            break;
                        }
                        Log.e(TAG, "SocketException in accept loop: " + e.getMessage());
                    } catch (IOException e) {
                        if (isRunning.get()) {
                            Log.e(TAG, "IOException in accept loop: " + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Fatal failure starting TCP server on port " + port + ": " + e.getMessage(), e);
                isRunning.set(false);
                isStoppedNotified.set(true);
                notifyServerError("Failed to bind port " + port + ": " + e.getMessage(), e);
            } finally {
                cleanupInternal();
            }
        });

        return true;
    }

    /**
     * Stops the TCP server, closes all client connections, and shuts down the thread pool.
     */
    public synchronized void stop() {
        if (!isRunning.getAndSet(false)) {
            // Already stopped or in the process of stopping
            cleanupInternal();
            return;
        }
        Log.i(TAG, "Stopping Ludo TCP server on port " + boundPort);
        cleanupInternal();
    }

    private void cleanupInternal() {
        // Close ServerSocket to unblock accept()
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
            serverSocket = null;
        }

        // Close all active client connections
        for (ClientConnection client : activeClients.values()) {
            client.disconnect("Server shutting down");
        }
        activeClients.clear();

        // Shutdown executor
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
            serverExecutor = null;
        }

        boundPort = 0;

        // Ensure onServerStopped is called exactly once per running session
        if (!isStoppedNotified.getAndSet(true)) {
            notifyServerStopped();
        }
    }

    /**
     * Closes an individual client connection.
     */
    public void disconnectClient(@NonNull String clientId, @NonNull String reason) {
        ClientConnection client = activeClients.get(clientId);
        if (client != null) {
            client.disconnect(reason);
        }
    }

    /**
     * Sends a protocol message to a specific client.
     */
    public boolean sendToClient(@NonNull String clientId, @NonNull LudoProtocolMessage message) {
        ClientConnection client = activeClients.get(clientId);
        if (client != null) {
            return client.send(message.toTransmissionString());
        }
        Log.w(TAG, "sendToClient failed: client '" + clientId + "' not found");
        return false;
    }

    /**
     * Broadcasts a protocol message to all currently connected clients.
     */
    public void broadcast(@NonNull LudoProtocolMessage message) {
        String framed = message.toTransmissionString();
        for (ClientConnection client : activeClients.values()) {
            client.send(framed);
        }
    }

    // --- Listener Dispatch Helpers ---

    private void notifyServerStarted(int port) {
        ServerListener l = listener;
        if (l != null) l.onServerStarted(port);
    }

    private void notifyServerStopped() {
        ServerListener l = listener;
        if (l != null) l.onServerStopped();
    }

    private void notifyServerError(@NonNull String err, @Nullable Throwable cause) {
        ServerListener l = listener;
        if (l != null) l.onServerError(err, cause);
    }

    private void notifyClientConnected(@NonNull String clientId, @NonNull String addr) {
        ServerListener l = listener;
        if (l != null) l.onClientConnected(clientId, addr);
    }

    private void notifyClientDisconnected(@NonNull String clientId, @NonNull String reason) {
        ServerListener l = listener;
        if (l != null) l.onClientDisconnected(clientId, reason);
    }

    private void notifyMessageReceived(@NonNull String clientId, @NonNull LudoProtocolMessage msg) {
        ServerListener l = listener;
        if (l != null) l.onMessageReceived(clientId, msg);
    }

    /**
     * Internal wrapper managing an individual connected Controller socket.
     */
    private class ClientConnection {
        private final String clientId;
        private final Socket socket;
        private final Object writeLock = new Object();
        private final AtomicBoolean isClosed = new AtomicBoolean(false);

        private BufferedReader reader;
        private BufferedWriter writer;

        ClientConnection(String clientId, Socket socket) {
            this.clientId = clientId;
            this.socket = socket;
        }

        void runReaderLoop() {
            try {
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

                char[] charBuf = new char[2048];
                LudoMessageFramer framer = new LudoMessageFramer();
                int readChars;

                while (!isClosed.get() && (readChars = reader.read(charBuf)) != -1) {
                    String chunk = new String(charBuf, 0, readChars);
                    List<String> frames = framer.feedChunk(chunk);

                    for (String frame : frames) {
                        LudoProtocolMessage msg = LudoProtocolMessage.fromJson(frame);
                        if (msg != null) {
                            notifyMessageReceived(clientId, msg);
                        } else {
                            Log.w(TAG, "Client [" + clientId + "] sent malformed JSON frame (" + frame.length() + " chars)");
                        }
                    }
                }
                handleDisconnect("Client closed connection (EOF)");
            } catch (SocketException e) {
                handleDisconnect(isClosed.get() ? "Closed by host" : "Connection reset: " + e.getMessage());
            } catch (Exception e) {
                handleDisconnect("Read error: " + e.getMessage());
            } finally {
                disconnect("Loop finished");
            }
        }

        boolean send(@NonNull String framedPayload) {
            if (isClosed.get() || socket.isClosed()) return false;
            synchronized (writeLock) {
                try {
                    if (writer != null) {
                        writer.write(framedPayload);
                        writer.flush();
                        return true;
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Write failed to client [" + clientId + "]: " + e.getMessage());
                    handleDisconnect("Write failed: " + e.getMessage());
                }
                return false;
            }
        }

        private void handleDisconnect(String reason) {
            if (isClosed.compareAndSet(false, true)) {
                Log.i(TAG, "Client [" + clientId + "] disconnected: " + reason);
                activeClients.remove(clientId);
                closeSocket();
                notifyClientDisconnected(clientId, reason);
            }
        }

        void disconnect(String reason) {
            handleDisconnect(reason);
        }

        private void closeSocket() {
            try {
                if (reader != null) reader.close();
            } catch (Exception ignored) {}
            try {
                if (writer != null) writer.close();
            } catch (Exception ignored) {}
            try {
                if (!socket.isClosed()) socket.close();
            } catch (Exception ignored) {}
        }
    }
}
