package com.vinaykpro.ludoking.cheating;

import androidx.annotation.NonNull;

/**
 * Pure monotonic-clock touch-gesture tracker for distinguishing a normal short dice tap
 * from a continuous > 3-second dice hold (long press), guaranteeing at most ONE roll
 * per physical touch gesture.
 */
public class DiceHoldTracker {

    public static final long LONG_PRESS_DURATION_MS = 3000L;

    public enum TouchUpOutcome {
        NONE,
        SHORT_TAP,
        LONG_PRESS
    }

    public enum TimeoutOutcome {
        IGNORED,
        NEED_MORE_WAIT,
        TRIGGER_LONG_PRESS
    }

    public static final class TouchUpResult {
        @NonNull
        public final TouchUpOutcome outcome;
        public final long holdDurationMs;
        public final float touchX;
        public final float touchY;

        public TouchUpResult(@NonNull TouchUpOutcome outcome, long holdDurationMs, float touchX, float touchY) {
            this.outcome = outcome;
            this.holdDurationMs = holdDurationMs;
            this.touchX = touchX;
            this.touchY = touchY;
        }
    }

    private final PlayerCheatingManager.TimeSource timeSource;

    private boolean touchActive = false;
    private boolean movedOutsideBounds = false;
    private boolean longPressHandledForTouch = false;
    private long downElapsedMs = 0L;
    private float lastX = -1f;
    private float lastY = -1f;
    private long activeTouchToken = 0L;

    public DiceHoldTracker() {
        this(android.os.SystemClock::elapsedRealtime);
    }

    public DiceHoldTracker(@NonNull PlayerCheatingManager.TimeSource timeSource) {
        this.timeSource = timeSource;
    }

    /**
     * Records ACTION_DOWN and returns a unique token identifying this touch sequence.
     */
    public synchronized long onTouchDown(float x, float y, int viewWidth, int viewHeight) {
        touchActive = true;
        longPressHandledForTouch = false;
        downElapsedMs = timeSource.elapsedRealtime();
        lastX = x;
        lastY = y;
        movedOutsideBounds = !isInsideBounds(x, y, viewWidth, viewHeight);
        activeTouchToken++;
        return activeTouchToken;
    }

    /**
     * Tracks finger movement and invalidates continuous hold if the touch leaves the dice view bounds.
     */
    public synchronized void onTouchMove(float x, float y, int viewWidth, int viewHeight) {
        if (!touchActive) return;
        if (!isInsideBounds(x, y, viewWidth, viewHeight)) {
            movedOutsideBounds = true;
        } else if (!movedOutsideBounds) {
            lastX = x;
            lastY = y;
        }
    }

    /**
     * Evaluates whether the scheduled long-press timeout (> 3000ms continuous hold) has been satisfied.
     */
    @NonNull
    public synchronized TimeoutOutcome onLongPressTimeout(long touchToken) {
        if (!touchActive || touchToken != activeTouchToken || movedOutsideBounds || longPressHandledForTouch) {
            return TimeoutOutcome.IGNORED;
        }
        long elapsed = timeSource.elapsedRealtime() - downElapsedMs;
        if (elapsed <= LONG_PRESS_DURATION_MS) {
            return TimeoutOutcome.NEED_MORE_WAIT;
        }
        longPressHandledForTouch = true;
        touchActive = false;
        return TimeoutOutcome.TRIGGER_LONG_PRESS;
    }

    public synchronized long getRemainingLongPressWaitMs() {
        if (!touchActive) return 0L;
        long elapsed = timeSource.elapsedRealtime() - downElapsedMs;
        return Math.max(1L, (LONG_PRESS_DURATION_MS + 1L) - elapsed);
    }

    /**
     * Resolves ACTION_UP. If the long-press timer already triggered a roll for this touch,
     * returns TouchUpOutcome.NONE so a second roll cannot fire on release.
     */
    @NonNull
    public synchronized TouchUpResult onTouchUp(float x, float y, int viewWidth, int viewHeight) {
        if (longPressHandledForTouch) {
            touchActive = false;
            return new TouchUpResult(TouchUpOutcome.NONE, 0L, lastX, lastY);
        }
        if (!touchActive) {
            return new TouchUpResult(TouchUpOutcome.NONE, 0L, lastX, lastY);
        }

        if (!isInsideBounds(x, y, viewWidth, viewHeight)) {
            movedOutsideBounds = true;
        } else if (!movedOutsideBounds) {
            lastX = x;
            lastY = y;
        }

        touchActive = false;
        if (movedOutsideBounds) {
            return new TouchUpResult(TouchUpOutcome.NONE, 0L, lastX, lastY);
        }

        long elapsed = Math.max(0L, timeSource.elapsedRealtime() - downElapsedMs);
        if (elapsed > LONG_PRESS_DURATION_MS) {
            longPressHandledForTouch = true;
            return new TouchUpResult(TouchUpOutcome.LONG_PRESS, elapsed, lastX, lastY);
        }
        return new TouchUpResult(TouchUpOutcome.SHORT_TAP, elapsed, lastX, lastY);
    }

    public synchronized void onTouchCancel() {
        touchActive = false;
        movedOutsideBounds = true;
        longPressHandledForTouch = false;
    }

    /**
     * Returns true if the current touch already produced a long-press roll and any trailing
     * View.OnClickListener callback must be suppressed.
     */
    public synchronized boolean shouldSuppressClickCallback() {
        return longPressHandledForTouch;
    }

    public synchronized boolean isTouchActive() {
        return touchActive;
    }

    public synchronized float getLastX() {
        return lastX;
    }

    public synchronized float getLastY() {
        return lastY;
    }

    public synchronized long getActiveTouchToken() {
        return activeTouchToken;
    }

    private static boolean isInsideBounds(float x, float y, int viewWidth, int viewHeight) {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return true;
        }
        return x >= 0f && x <= viewWidth && y >= 0f && y <= viewHeight;
    }
}
