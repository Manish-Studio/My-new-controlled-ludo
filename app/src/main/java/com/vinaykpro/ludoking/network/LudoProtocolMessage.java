package com.vinaykpro.ludoking.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

/**
 * Universal protocol message model for Ludo Host and Controller communication.
 * Uses native Android org.json.JSONObject with robust type checking and field validation.
 */
public class LudoProtocolMessage {

    @NonNull
    private final LudoProtocol.MessageType type;
    private final int protocolVersion;
    @NonNull
    private final String role;
    @Nullable
    private final String requestId;
    private final long timestamp;
    @Nullable
    private final String status;
    @Nullable
    private final Integer value;
    @Nullable
    private final String boxId;
    @Nullable
    private final Integer channel;
    @Nullable
    private final Integer boxCount;
    @Nullable
    private final String message;
    @Nullable
    private final String errorCode;
    @Nullable
    private final String reason;
    @Nullable
    private final String rawJson;

    public LudoProtocolMessage(
            @NonNull LudoProtocol.MessageType type,
            int protocolVersion,
            @NonNull String role,
            @Nullable String requestId,
            long timestamp,
            @Nullable String status,
            @Nullable Integer value,
            @Nullable String boxId,
            @Nullable Integer channel,
            @Nullable Integer boxCount,
            @Nullable String message,
            @Nullable String errorCode,
            @Nullable String reason,
            @Nullable String rawJson
    ) {
        this.type = Objects.requireNonNull(type, "MessageType cannot be null");
        this.protocolVersion = protocolVersion;
        this.role = role != null ? role : LudoProtocol.ROLE_LUDO_HOST;
        this.requestId = requestId;
        this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
        this.status = status;
        this.value = value;
        this.boxId = boxId;
        this.channel = channel;
        this.boxCount = boxCount;
        this.message = message;
        this.errorCode = errorCode;
        this.reason = reason;
        this.rawJson = rawJson;
    }

    @NonNull
    public LudoProtocol.MessageType getType() {
        return type;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    @NonNull
    public String getRole() {
        return role;
    }

    @Nullable
    public String getRequestId() {
        return requestId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Nullable
    public String getStatus() {
        return status;
    }

    @Nullable
    public Integer getValue() {
        return value;
    }

    @Nullable
    public String getBoxId() {
        return boxId;
    }

    @Nullable
    public Integer getChannel() {
        return channel;
    }

    @Nullable
    public Integer getBoxCount() {
        return boxCount;
    }

    @Nullable
    public String getMessage() {
        return message;
    }

    @Nullable
    public String getErrorCode() {
        return errorCode;
    }

    @Nullable
    public String getReason() {
        return reason;
    }

    @Nullable
    public String getRawJson() {
        return rawJson;
    }

    /**
     * Serializes this message to a native org.json.JSONObject.
     */
    @NonNull
    public JSONObject toJsonObject() {
        JSONObject json = new JSONObject();
        try {
            json.put("type", type.name());
            json.put("protocolVersion", protocolVersion);
            json.put("role", role);
            json.put("timestamp", timestamp);

            if (requestId != null) json.put("requestId", requestId);
            if (status != null) json.put("status", status);
            if (value != null) json.put("value", value.intValue());
            if (boxId != null) json.put("boxId", boxId);
            if (channel != null) json.put("channel", channel.intValue());
            if (boxCount != null) json.put("boxCount", boxCount.intValue());
            if (message != null) json.put("message", message);
            if (errorCode != null) json.put("errorCode", errorCode);
            if (reason != null) json.put("reason", reason);
        } catch (JSONException e) {
            // Should not occur with standard valid types
            throw new RuntimeException("Error serializing LudoProtocolMessage to JSON", e);
        }
        return json;
    }

    /**
     * Serializes to JSON String without trailing newline.
     */
    @NonNull
    public String toJson() {
        return toJsonObject().toString();
    }

    /**
     * Formats for socket transmission with trailing newline framing.
     */
    @NonNull
    public String toTransmissionString() {
        return toJson() + "\n";
    }

    /**
     * Safely parses a JSON string into a LudoProtocolMessage.
     * Returns null if string is empty, invalid, or malformed.
     */
    @Nullable
    public static LudoProtocolMessage fromJson(@Nullable String jsonStr) {
        if (jsonStr == null) return null;
        String clean = jsonStr.trim();
        if (clean.isEmpty()) return null;

        try {
            JSONObject json = new JSONObject(clean);
            String typeStr = json.optString("type", null);
            LudoProtocol.MessageType type = LudoProtocol.MessageType.fromString(typeStr);

            int version = json.optInt("protocolVersion", LudoProtocol.PROTOCOL_VERSION);
            String role = json.optString("role", LudoProtocol.ROLE_CONTROLLER);
            String requestId = json.has("requestId") && !json.isNull("requestId") ? json.getString("requestId") : null;
            long timestamp = json.optLong("timestamp", System.currentTimeMillis());
            String status = json.has("status") && !json.isNull("status") ? json.getString("status") : null;

            Integer value = null;
            if (json.has("value") && !json.isNull("value")) {
                value = json.getInt("value");
            } else if (json.has("result") && !json.isNull("result")) {
                value = json.getInt("result");
            } else if (json.has("number") && !json.isNull("number")) {
                value = json.getInt("number");
            }

            String boxId = null;
            if (json.has("boxId") && !json.isNull("boxId")) {
                boxId = json.getString("boxId");
            } else if (json.has("controllerBoxId") && !json.isNull("controllerBoxId")) {
                boxId = json.getString("controllerBoxId");
            }

            Integer channel = null;
            if (json.has("channel") && !json.isNull("channel")) {
                channel = json.getInt("channel");
            } else if (boxId != null) {
                channel = parseChannelFromBoxId(boxId);
            }

            Integer boxCount = null;
            if (json.has("boxCount") && !json.isNull("boxCount")) {
                boxCount = json.getInt("boxCount");
            } else if (json.has("count") && !json.isNull("count")) {
                boxCount = json.getInt("count");
            } else if (json.has("totalBoxes") && !json.isNull("totalBoxes")) {
                boxCount = json.getInt("totalBoxes");
            }

            String message = json.has("message") && !json.isNull("message") ? json.getString("message") : null;
            String errorCode = json.has("errorCode") && !json.isNull("errorCode") ? json.getString("errorCode") : null;
            String reason = json.has("reason") && !json.isNull("reason") ? json.getString("reason") : null;

            return new LudoProtocolMessage(
                    type,
                    version,
                    role,
                    requestId,
                    timestamp,
                    status,
                    value,
                    boxId,
                    channel,
                    boxCount,
                    message,
                    errorCode,
                    reason,
                    clean
            );
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    public static Integer parseChannelFromBoxId(@NonNull String rawBoxId) {
        String clean = rawBoxId.trim().toUpperCase();
        if (clean.startsWith("B") || clean.startsWith("R")) {
            try {
                return Integer.parseInt(clean.substring(1));
            } catch (NumberFormatException ignored) {}
        }
        try {
            return Integer.parseInt(clean);
        } catch (NumberFormatException ignored) {}
        return null;
    }

    // --- Common Factory Methods for Ludo Host ---

    @NonNull
    public static LudoProtocolMessage createHandshakeAck(@NonNull String requestId, @NonNull String status) {
        return new LudoProtocolMessage(
                LudoProtocol.MessageType.HANDSHAKE_ACK,
                LudoProtocol.PROTOCOL_VERSION,
                LudoProtocol.ROLE_LUDO_HOST,
                requestId,
                System.currentTimeMillis(),
                status,
                null, null, null, null, null, null, null, null
        );
    }

    @NonNull
    public static LudoProtocolMessage createPong(@Nullable String requestId) {
        return new LudoProtocolMessage(
                LudoProtocol.MessageType.PONG,
                LudoProtocol.PROTOCOL_VERSION,
                LudoProtocol.ROLE_LUDO_HOST,
                requestId,
                System.currentTimeMillis(),
                "OK",
                null, null, null, null, null, null, null, null
        );
    }

    @NonNull
    public static LudoProtocolMessage createAck(@NonNull String requestId, @NonNull String status) {
        return new LudoProtocolMessage(
                LudoProtocol.MessageType.ACK,
                LudoProtocol.PROTOCOL_VERSION,
                LudoProtocol.ROLE_LUDO_HOST,
                requestId,
                System.currentTimeMillis(),
                status,
                null, null, null, null, null, null, null, null
        );
    }

    @NonNull
    public static LudoProtocolMessage createConfig(int boxCount) {
        return new LudoProtocolMessage(
                LudoProtocol.MessageType.CONFIG,
                LudoProtocol.PROTOCOL_VERSION,
                LudoProtocol.ROLE_LUDO_HOST,
                null,
                System.currentTimeMillis(),
                null,
                null, null, null, boxCount, null, null, null, null
        );
    }

    @NonNull
    public static LudoProtocolMessage createNumberResult(
            @NonNull String requestId,
            @NonNull String boxId,
            int channel,
            int value
    ) {
        return new LudoProtocolMessage(
                LudoProtocol.MessageType.NUMBER_RESULT,
                LudoProtocol.PROTOCOL_VERSION,
                LudoProtocol.ROLE_LUDO_HOST,
                requestId,
                System.currentTimeMillis(),
                "OK",
                value,
                boxId,
                channel,
                null, null, null, null, null
        );
    }

    @NonNull
    public static LudoProtocolMessage createError(@Nullable String requestId, @NonNull String errorCode, @NonNull String message) {
        return new LudoProtocolMessage(
                LudoProtocol.MessageType.ERROR,
                LudoProtocol.PROTOCOL_VERSION,
                LudoProtocol.ROLE_LUDO_HOST,
                requestId,
                System.currentTimeMillis(),
                "ERROR",
                null, null, null, null, message, errorCode, null, null
        );
    }

    @NonNull
    public static LudoProtocolMessage createDisconnect(@NonNull String reason) {
        return new LudoProtocolMessage(
                LudoProtocol.MessageType.DISCONNECT,
                LudoProtocol.PROTOCOL_VERSION,
                LudoProtocol.ROLE_LUDO_HOST,
                null,
                System.currentTimeMillis(),
                null,
                null, null, null, null, null, null, reason, null
        );
    }

    @Override
    public String toString() {
        return "LudoProtocolMessage{" +
                "type=" + type +
                ", reqId='" + requestId + '\'' +
                ", boxId='" + boxId + '\'' +
                ", ch=" + channel +
                ", val=" + value +
                ", count=" + boxCount +
                '}';
    }
}
