package com.vinaykpro.ludoking.board;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Pure layout math for Ludo King-style overlapping token fanning and Z-order prioritization
 * within a single board cell.
 */
public final class TokenOverlapLayoutHelper {

    public static final class TokenState {
        public final int id;
        public final String color;
        public final boolean activePlayer;
        public final boolean eligibleToMove;

        public TokenState(int id, String color, boolean activePlayer, boolean eligibleToMove) {
            this.id = id;
            this.color = color;
            this.activePlayer = activePlayer;
            this.eligibleToMove = eligibleToMove;
        }
    }

    public static final class SlotPlacement {
        public final int tokenId;
        public final float scale;
        public final float translationX;
        public final float translationY;
        public final float elevation;

        public SlotPlacement(int tokenId, float scale, float translationX, float translationY, float elevation) {
            this.tokenId = tokenId;
            this.scale = scale;
            this.translationX = translationX;
            this.translationY = translationY;
            this.elevation = elevation;
        }
    }

    private TokenOverlapLayoutHelper() {}

    public static float computeBaseRowElevation(int currBlock) {
        if (currBlock >= 24 && currBlock <= 51) {
            return 51f - currBlock;
        } else if (currBlock >= 64 && currBlock <= 69) {
            return 70f - currBlock;
        }
        return Math.max(0f, (float) currBlock);
    }

    public static float computeScaleForCount(int count) {
        if (count <= 1) {
            return 1.0f;
        }
        if (count == 2) {
            return 0.78f;
        }
        if (count == 3) {
            return 0.68f;
        }
        if (count == 4) {
            return 0.60f;
        }
        return Math.max(0.46f, 1.0f / (0.28f * count + 0.65f));
    }

    /**
     * Computes clean, centered fan/cluster coordinates and Z-order elevations for all tokens
     * sharing one cell. Active player's eligible tokens are placed last (highest Z-order).
     */
    public static List<SlotPlacement> computeCellPlacements(
            List<TokenState> tokensInCell,
            int targetBox,
            float baseTranslationX,
            float baseTranslationY,
            float blockSize,
            boolean isHorizontalTrack
    ) {
        if (tokensInCell == null || tokensInCell.isEmpty()) {
            return Collections.emptyList();
        }

        List<TokenState> ordered = new ArrayList<>(tokensInCell);
        // Sort so opponents render underneath, active non-movable next, and active eligible tokens on top.
        Collections.sort(ordered, new Comparator<TokenState>() {
            @Override
            public int compare(TokenState a, TokenState b) {
                int rankA = priorityRank(a);
                int rankB = priorityRank(b);
                if (rankA != rankB) {
                    return Integer.compare(rankA, rankB);
                }
                return Integer.compare(a.id, b.id);
            }
        });

        int n = ordered.size();
        float rowElevation = computeBaseRowElevation(targetBox);
        float scale = computeScaleForCount(n);
        float safeBlock = blockSize > 0f ? blockSize : 60f;

        List<SlotPlacement> result = new ArrayList<>(n);
        if (n == 1) {
            TokenState t = ordered.get(0);
            float elev = rowElevation + (t.eligibleToMove && t.activePlayer ? 80f : 0f);
            result.add(new SlotPlacement(t.id, 1.0f, baseTranslationX, baseTranslationY, elev));
            return result;
        }

        float[][] offsets = computeOffsets(n, safeBlock, isHorizontalTrack);
        for (int i = 0; i < n; i++) {
            TokenState t = ordered.get(i);
            float dx = offsets[i][0];
            float dy = offsets[i][1];
            float elev = rowElevation + (i * 2.0f) + (t.activePlayer && t.eligibleToMove ? 80f : (t.activePlayer ? 20f : 0f));
            result.add(new SlotPlacement(t.id, scale, baseTranslationX + dx, baseTranslationY + dy, elev));
        }
        return result;
    }

    private static int priorityRank(TokenState t) {
        if (t.activePlayer && t.eligibleToMove) {
            return 2;
        }
        if (t.activePlayer) {
            return 1;
        }
        return 0;
    }

    private static float[][] computeOffsets(int n, float blockSize, boolean isHorizontalTrack) {
        float[][] offsets = new float[n][2];
        if (n == 2) {
            if (isHorizontalTrack) {
                float sx = blockSize * 0.22f;
                float sy = blockSize * 0.04f;
                offsets[0][0] = -sx;
                offsets[0][1] = -sy;
                offsets[1][0] = sx;
                offsets[1][1] = sy;
            } else {
                float sx = blockSize * 0.16f;
                float sy = blockSize * 0.14f;
                offsets[0][0] = -sx;
                offsets[0][1] = -sy;
                offsets[1][0] = sx;
                offsets[1][1] = sy;
            }
            return offsets;
        }
        if (n == 3) {
            float sx = blockSize * 0.23f;
            float syTop = -blockSize * 0.14f;
            float syBottom = blockSize * 0.16f;
            offsets[0][0] = -sx;
            offsets[0][1] = syTop;
            offsets[1][0] = sx;
            offsets[1][1] = syTop;
            offsets[2][0] = 0f;
            offsets[2][1] = syBottom;
            return offsets;
        }
        if (n == 4) {
            float sx = blockSize * 0.22f;
            float sy = blockSize * 0.16f;
            offsets[0][0] = -sx;
            offsets[0][1] = -sy;
            offsets[1][0] = sx;
            offsets[1][1] = -sy;
            offsets[2][0] = -sx;
            offsets[2][1] = sy;
            offsets[3][0] = sx;
            offsets[3][1] = sy;
            return offsets;
        }

        // 5+ tokens: compact evenly spaced multi-row grid inside cell
        int cols = (int) Math.ceil(Math.sqrt(n));
        int rows = (int) Math.ceil(n / (double) cols);
        float maxSpanX = blockSize * 0.56f;
        float maxSpanY = blockSize * 0.44f;
        float stepX = cols > 1 ? maxSpanX / (cols - 1) : 0f;
        float stepY = rows > 1 ? maxSpanY / (rows - 1) : 0f;
        for (int i = 0; i < n; i++) {
            int col = i % cols;
            int row = i / cols;
            offsets[i][0] = -maxSpanX / 2f + col * stepX;
            offsets[i][1] = -maxSpanY / 2f + row * stepY;
        }
        return offsets;
    }
}
