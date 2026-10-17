package com.vinaykpro.ludoking.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Protocol constants and Message Types for Ludo Host <-> Controller communication.
 * Strictly aligned with the Controller's verified protocol specification.
 */
public final class LudoProtocol {

    private LudoProtocol() {}

    public static final int PROTOCOL_VERSION = 1;
    public static final String ROLE_CONTROLLER = "CONTROLLER";
    public static final String ROLE_LUDO_HOST = "LUDO_HOST";

    public static final int DEFAULT_PORT = 8888;
    public static final String NSD_SERVICE_NAME = "LudoHost";
    public static final String NSD_SERVICE_TYPE = "_ludo._tcp.";

    public static final int MIN_BOX_COUNT = 2;
    public static final int MAX_BOX_COUNT = 4;
    public static final int DEFAULT_BOX_COUNT = 4;

    public static final int MIN_DICE_VALUE = 1;
    public static final int MAX_DICE_VALUE = 6;

    public static final int MAX_FRAME_LENGTH = 65536; // 64 KB safety bound

    /**
     * Message types supported by the protocol.
     */
    public enum MessageType {
        HANDSHAKE,
        HANDSHAKE_ACK,
        PING,
        PONG,
        ACK,
        ERROR,
        GET_CONFIG,
        CONFIG,
        STATE_SYNC,
        NUMBER_SELECTION,
        NUMBER_RESULT,
        GAME_EVENT,
        DISCONNECT,
        RECONNECT,
        UNKNOWN;

        @NonNull
        public static MessageType fromString(@Nullable String typeStr) {
            if (typeStr == null) return UNKNOWN;
            String clean = typeStr.trim().toUpperCase();
            switch (clean) {
                case "HANDSHAKE":
                    return HANDSHAKE;
                case "HANDSHAKE_ACK":
                case "HANDSHAKEACK":
                    return HANDSHAKE_ACK;
                case "PING":
                    return PING;
                case "PONG":
                    return PONG;
                case "ACK":
                case "NUMBER_SELECTION_ACK":
                    return ACK;
                case "ERROR":
                case "NACK":
                    return ERROR;
                case "GET_CONFIG":
                case "REQUEST_CONFIG":
                    return GET_CONFIG;
                case "CONFIG":
                case "GAME_CONFIGURATION":
                    return CONFIG;
                case "STATE_SYNC":
                case "SYNC":
                    return STATE_SYNC;
                case "NUMBER_SELECTION":
                case "SELECT_NUMBER":
                    return NUMBER_SELECTION;
                case "NUMBER_RESULT":
                case "RESULT":
                    return NUMBER_RESULT;
                case "GAME_EVENT":
                case "LUDO_EVENT":
                    return GAME_EVENT;
                case "DISCONNECT":
                    return DISCONNECT;
                case "RECONNECT":
                    return RECONNECT;
                default:
                    return UNKNOWN;
            }
        }
    }
}
