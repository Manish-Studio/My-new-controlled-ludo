package com.vinaykpro.ludoking.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Message framer for newline-delimited stream protocol.
 *
 * Capabilities:
 * - Accumulates byte/char chunks across fragmented socket reads.
 * - Extracts multiple complete JSON frames delivered in a single socket read.
 * - Supports both Unix LF (\n) and Windows CRLF (\r\n) framing.
 * - Enforces max frame limit (64KB) to prevent memory exhaustion attacks.
 * - Discards oversized frames safely while continuing to parse subsequent frames.
 */
public class LudoMessageFramer {

    private final StringBuilder buffer = new StringBuilder();
    private final int maxFrameLength;
    private boolean isDiscardingOversized = false;

    public LudoMessageFramer() {
        this(LudoProtocol.MAX_FRAME_LENGTH);
    }

    public LudoMessageFramer(int maxFrameLength) {
        this.maxFrameLength = maxFrameLength > 0 ? maxFrameLength : LudoProtocol.MAX_FRAME_LENGTH;
    }

    /**
     * Appends an incoming chunk of data from a socket read and extracts any completed frames.
     *
     * @param chunk The text read from socket.
     * @return List of complete message frames without trailing newline delimiters.
     */
    @NonNull
    public synchronized List<String> feedChunk(@Nullable String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> frames = new ArrayList<>();
        int length = chunk.length();

        for (int i = 0; i < length; i++) {
            char c = chunk.charAt(i);

            if (c == '\n') {
                if (isDiscardingOversized) {
                    // Reached end of discarded oversized frame; reset state
                    isDiscardingOversized = false;
                    buffer.setLength(0);
                } else {
                    String frame = buffer.toString();
                    buffer.setLength(0);

                    // Strip optional carriage return \r if CRLF
                    if (frame.endsWith("\r")) {
                        frame = frame.substring(0, frame.length() - 1);
                    }

                    String trimmed = frame.trim();
                    if (!trimmed.isEmpty()) {
                        frames.add(trimmed);
                    }
                }
            } else {
                if (!isDiscardingOversized) {
                    buffer.append(c);
                    if (buffer.length() > maxFrameLength) {
                        // Frame exceeded maximum permissible limit
                        isDiscardingOversized = true;
                        buffer.setLength(0);
                    }
                }
            }
        }

        return frames;
    }

    /**
     * Returns any remaining un-framed data in the buffer (e.g. for inspection upon disconnect).
     */
    @NonNull
    public synchronized String getRemainingBuffer() {
        return buffer.toString();
    }

    /**
     * Clears buffer state.
     */
    public synchronized void reset() {
        buffer.setLength(0);
        isDiscardingOversized = false;
    }
}
