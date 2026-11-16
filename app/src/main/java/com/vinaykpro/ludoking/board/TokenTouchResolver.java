package com.vinaykpro.ludoking.board;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, testable hit-testing and priority resolution engine for Ludo board tokens.
 * <p>
 * Guarantees that when tokens of 2, 3, or 4 players overlap on a board cell,
 * tapping anywhere on the visible stack or cell reliably selects the active human
 * player's eligible token rather than letting an opponent's overlapping view block the touch.
 */
public final class TokenTouchResolver {

    public static final class TokenCandidate {
        public final int id;
        public final String color;
        public final int currBlock;
        public final boolean alive;
        public final boolean eligibleToMove;
        public final boolean belongsToActiveHuman;
        public final float centerX;
        public final float centerY;
        public final float hitLeft;
        public final float hitTop;
        public final float hitRight;
        public final float hitBottom;
        public final float elevation;

        public TokenCandidate(
                int id,
                String color,
                int currBlock,
                boolean alive,
                boolean eligibleToMove,
                boolean belongsToActiveHuman,
                float centerX,
                float centerY,
                float hitLeft,
                float hitTop,
                float hitRight,
                float hitBottom,
                float elevation
        ) {
            this.id = id;
            this.color = color;
            this.currBlock = currBlock;
            this.alive = alive;
            this.eligibleToMove = eligibleToMove;
            this.belongsToActiveHuman = belongsToActiveHuman;
            this.centerX = centerX;
            this.centerY = centerY;
            this.hitLeft = hitLeft;
            this.hitTop = hitTop;
            this.hitRight = hitRight;
            this.hitBottom = hitBottom;
            this.elevation = elevation;
        }

        public boolean contains(float x, float y) {
            return x >= hitLeft && x <= hitRight && y >= hitTop && y <= hitBottom;
        }

        public float distanceSquaredTo(float x, float y) {
            float dx = x - centerX;
            float dy = y - centerY;
            return dx * dx + dy * dy;
        }
    }

    public static final class CellBounds {
        public final int cellIndex;
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;

        public CellBounds(int cellIndex, float left, float top, float right, float bottom) {
            this.cellIndex = cellIndex;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public boolean contains(float x, float y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }
    }

    public enum ActionType {
        NONE,
        CHEAT_LOCK_FIVE,
        SELECT_TOKEN_TO_MOVE
    }

    public static final class ResolutionResult {
        public final ActionType actionType;
        public final TokenCandidate selectedToken;

        public ResolutionResult(ActionType actionType, TokenCandidate selectedToken) {
            this.actionType = actionType;
            this.selectedToken = selectedToken;
        }
    }

    private TokenTouchResolver() {}

    /**
     * Computes expanded hit-test bounds for a visual token centered at (centerX, centerY)
     * with rendered size (visualW, visualH), guaranteeing at least (minHitW, minHitH).
     */
    public static float[] computeExpandedHitBounds(
            float centerX,
            float centerY,
            float visualW,
            float visualH,
            float minHitW,
            float minHitH
    ) {
        float halfW = Math.max(visualW * 0.58f, minHitW * 0.5f);
        float halfH = Math.max(visualH * 0.58f, minHitH * 0.5f);
        return new float[] {
                centerX - halfW,
                centerY - halfH,
                centerX + halfW,
                centerY + halfH
        };
    }

    public static ResolutionResult resolveTap(
            float touchX,
            float touchY,
            List<TokenCandidate> allVisibleTokens,
            List<CellBounds> occupiedCellBounds,
            boolean cheatFiveLockReady
    ) {
        if (allVisibleTokens == null || allVisibleTokens.isEmpty()) {
            return new ResolutionResult(ActionType.NONE, null);
        }

        // Priority 1: Pre-roll Player Cheating Selection single-tap on any visible board token locks 5
        if (cheatFiveLockReady) {
            TokenCandidate hitAny = pickClosestMatchingToken(touchX, touchY, allVisibleTokens, false);
            if (hitAny != null) {
                return new ResolutionResult(ActionType.CHEAT_LOCK_FIVE, hitAny);
            }
            return new ResolutionResult(ActionType.NONE, null);
        }

        // Collect all eligible tokens of the active human player
        List<TokenCandidate> activeEligible = new ArrayList<>();
        for (TokenCandidate c : allVisibleTokens) {
            if (c.belongsToActiveHuman && c.eligibleToMove) {
                activeEligible.add(c);
            }
        }
        if (activeEligible.isEmpty()) {
            return new ResolutionResult(ActionType.NONE, null);
        }

        // Step A: Direct hit against any active eligible token's expanded hitbox.
        // Choose the token whose center is closest to the tap (handles fanned same-player stacks cleanly).
        TokenCandidate directHit = pickClosestMatchingToken(touchX, touchY, activeEligible, true);
        if (directHit != null) {
            return new ResolutionResult(ActionType.SELECT_TOKEN_TO_MOVE, directHit);
        }

        // Step B: Stack / Cell priority over overlapping opponent tokens.
        // If user tapped on an opponent token sharing a cell with an active eligible token,
        // or tapped inside that cell's padded bounds, select the active eligible token in that cell!
        List<Integer> tappedCells = new ArrayList<>();
        for (TokenCandidate c : allVisibleTokens) {
            if (c.alive && c.currBlock >= 0 && c.contains(touchX, touchY)) {
                if (!tappedCells.contains(c.currBlock)) {
                    tappedCells.add(c.currBlock);
                }
            }
        }
        if (occupiedCellBounds != null) {
            for (CellBounds cell : occupiedCellBounds) {
                if (cell.contains(touchX, touchY) && !tappedCells.contains(cell.cellIndex)) {
                    tappedCells.add(cell.cellIndex);
                }
            }
        }

        if (!tappedCells.isEmpty()) {
            List<TokenCandidate> cellCandidates = new ArrayList<>();
            for (TokenCandidate c : activeEligible) {
                if (c.alive && tappedCells.contains(c.currBlock)) {
                    cellCandidates.add(c);
                }
            }
            if (!cellCandidates.isEmpty()) {
                TokenCandidate best = pickClosestByCenter(touchX, touchY, cellCandidates);
                if (best != null) {
                    return new ResolutionResult(ActionType.SELECT_TOKEN_TO_MOVE, best);
                }
            }
        }

        return new ResolutionResult(ActionType.NONE, null);
    }

    /**
     * Quick check used on ACTION_DOWN to decide whether this touch starts inside a relevant
     * token/cell area (so empty board taps do not unnecessarily intercept unrelated gestures).
     */
    public static boolean shouldInterceptTouchDown(
            float touchX,
            float touchY,
            List<TokenCandidate> allVisibleTokens,
            List<CellBounds> occupiedCellBounds,
            boolean cheatFiveLockReady
    ) {
        ResolutionResult preview = resolveTap(touchX, touchY, allVisibleTokens, occupiedCellBounds, cheatFiveLockReady);
        return preview.actionType != ActionType.NONE;
    }

    private static TokenCandidate pickClosestMatchingToken(
            float x,
            float y,
            List<TokenCandidate> candidates,
            boolean checkOnlyDirectHits
    ) {
        List<TokenCandidate> hits = new ArrayList<>();
        for (TokenCandidate c : candidates) {
            if (c.contains(x, y)) {
                hits.add(c);
            }
        }
        if (hits.isEmpty()) {
            return null;
        }
        return pickClosestByCenter(x, y, hits);
    }

    private static TokenCandidate pickClosestByCenter(float x, float y, List<TokenCandidate> candidates) {
        TokenCandidate best = null;
        float bestDist = Float.MAX_VALUE;
        for (TokenCandidate c : candidates) {
            float dist = c.distanceSquaredTo(x, y);
            if (best == null || dist < bestDist - 1.0f || (Math.abs(dist - bestDist) <= 1.0f && c.elevation > best.elevation)) {
                best = c;
                bestDist = dist;
            }
        }
        return best;
    }
}
